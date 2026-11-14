package org.uet.dse.ocl2cypher.execution;

import java.util.*;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.CypherArtifacts;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphKey;
import org.uet.dse.ocl2cypher.graph.GraphObservation;
import org.uet.dse.ocl2cypher.runtime.OclEquality;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.execution.Neo4jExecutionAdapter.ExecutionResult;

/** Physical parameters and tagged results; no connection or graph writes. */
final class Neo4jValueCodec {
    private Neo4jValueCodec() { }

    static ExecutionResult decodeRecords(List<org.neo4j.driver.Record> records,
                                                   CypherAst.ResultContract contract,
                                                   CypherAst.GeneratedArtifact artifact) {
        if (contract.shape() == CypherAst.ResultShape.IDS) {
            List<String> ids = new ArrayList<>();
            for (var record : records) {
                String id = record.get(contract.resultVariable()).asString(null);
                if (id != null) {
                    ids.add(id);
                }
            }
            Collections.sort(ids);
            return new ExecutionResult(List.copyOf(ids), null, artifact);
        }
        if (records.size() != 1) {
            throw new IllegalArgumentException("VALUE query must return exactly one row, found "
                    + records.size());
        }
        Object raw = records.get(0).get(contract.resultVariable()).asObject();
        OclValue decoded = decodeTagged(raw, parseTypeTag(contract.elementTypeTag()));
        return new ExecutionResult(List.of(), decoded, artifact);
    }

    /** Decode the tagged carrier returned by R/S without collapsing bottom into null/empty. */
    static OclValue decodeTagged(Object raw, OclType expectedType) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("tagged OCL value must be a map");
        }
        Object bottomRaw = map.get("__oclBottom");
        if (!(bottomRaw instanceof Boolean bottom)) {
            throw new IllegalArgumentException("tagged OCL value lacks Boolean __oclBottom");
        }
        Object encodedTypeRaw = map.get(CypherArtifacts.OCL_TYPE);
        if (!(encodedTypeRaw instanceof String encodedType)) {
            throw new IllegalArgumentException("tagged OCL value lacks String __oclType");
        }
        String expectedTag = CypherArtifacts.carrierTypeTag(expectedType, bottom);
        if (!expectedTag.equals(encodedType)) {
            throw new IllegalArgumentException("tagged type mismatch: expected " + expectedTag
                    + ", found " + encodedType);
        }
        if (bottom) {
            if (expectedType.isCollection()) {
                requireExactCarrierKeys(map, Set.of(CypherArtifacts.OCL_BOTTOM,
                        CypherArtifacts.OCL_KIND, CypherArtifacts.OCL_TYPE,
                        CypherArtifacts.OCL_ITEMS));
                String expectedKind = expectedType.kind() == OclType.Kind.SET ? "SET" : "BAG";
                if (!expectedKind.equals(map.get(CypherArtifacts.OCL_KIND))) {
                    throw new IllegalArgumentException("bottom collection kind mismatch");
                }
                Object rawItems = map.get(CypherArtifacts.OCL_ITEMS);
                if (!(rawItems instanceof List<?> items) || !items.isEmpty()) {
                    throw new IllegalArgumentException(
                            "bottom collection must have empty __oclItems");
                }
            } else {
                requireExactCarrierKeys(map, Set.of(CypherArtifacts.OCL_BOTTOM,
                        CypherArtifacts.OCL_TYPE, CypherArtifacts.OCL_VALUE));
                if (map.get(CypherArtifacts.OCL_VALUE) != null) {
                    throw new IllegalArgumentException(
                            "bottom scalar must have null __oclValue");
                }
            }
            return new OclValue.BottomValue(expectedType);
        }
        if (expectedType.isCollection()) {
            requireExactCarrierKeys(map, Set.of(CypherArtifacts.OCL_BOTTOM,
                    CypherArtifacts.OCL_KIND, CypherArtifacts.OCL_TYPE,
                    CypherArtifacts.OCL_ITEMS));
            String expectedKind = expectedType.kind() == OclType.Kind.SET ? "SET" : "BAG";
            if (!expectedKind.equals(map.get(CypherArtifacts.OCL_KIND))) {
                throw new IllegalArgumentException("tagged collection kind mismatch");
            }
            Object rawItems = map.get(CypherArtifacts.OCL_ITEMS);
            if (!(rawItems instanceof List<?> items)) {
                throw new IllegalArgumentException("tagged collection lacks __oclItems list");
            }
            List<OclValue> decoded = items.stream()
                    .map(item -> decodeTagged(item, expectedType.elementType()))
                    .toList();
            if (expectedType.kind() == OclType.Kind.SET) {
                requireUniqueSetItems(decoded,
                        "tagged Set contains a duplicate under typed OCL equality");
                return new OclValue.SetValue(expectedType, decoded);
            }
            return new OclValue.BagValue(expectedType, decoded);
        }
        requireExactCarrierKeys(map, Set.of(CypherArtifacts.OCL_BOTTOM,
                CypherArtifacts.OCL_TYPE, CypherArtifacts.OCL_VALUE));
        Object payload = map.get(CypherArtifacts.OCL_VALUE);
        if (payload == null) {
            throw new IllegalArgumentException("defined tagged value has null payload");
        }
        return switch (expectedType.kind()) {
            case BOOLEAN -> {
                if (!(payload instanceof Boolean value)) {
                    throw new IllegalArgumentException("Boolean payload expected");
                }
                yield new OclValue.BooleanValue(OclType.BOOLEAN,
                        value ? OclValue.BooleanValue.Bool3.TRUE
                                : OclValue.BooleanValue.Bool3.FALSE);
            }
            case INTEGER -> {
                if (payload instanceof java.math.BigInteger bi) {
                    if (bi.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0
                            || bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                        throw new IllegalArgumentException(
                                "Integer payload lies outside signed Cypher INT64");
                    }
                    yield new OclValue.IntegerValue(bi);
                }
                if (payload instanceof Long || payload instanceof Integer
                        || payload instanceof Short || payload instanceof Byte) {
                    yield new OclValue.IntegerValue(
                            java.math.BigInteger.valueOf(((Number) payload).longValue()));
                }
                throw new IllegalArgumentException(
                        "Integer payload must be an integral type, found "
                                + payload.getClass().getSimpleName() + ": " + payload);
            }
            case REAL -> {
                if (payload instanceof java.math.BigDecimal bd) {
                    requireExactFiniteBinary64(bd);
                    yield new OclValue.RealValue(bd);
                }
                if (payload instanceof Double d) {
                    if (!Double.isFinite(d)) {
                        throw new IllegalArgumentException(
                                "Real payload must be finite, found " + d);
                    }
                    requireExactFiniteBinary64(java.math.BigDecimal.valueOf(d));
                    yield new OclValue.RealValue(java.math.BigDecimal.valueOf(d));
                }
                if (payload instanceof Float f) {
                    if (!Float.isFinite(f)) {
                        throw new IllegalArgumentException(
                                "Real payload must be finite, found " + f);
                    }
                    java.math.BigDecimal value = java.math.BigDecimal.valueOf(f.doubleValue());
                    requireExactFiniteBinary64(value);
                    yield new OclValue.RealValue(value);
                }
                throw new IllegalArgumentException(
                        "Real payload must be Double/Float/BigDecimal, found "
                                + payload.getClass().getSimpleName() + ": " + payload);
            }
            case STRING -> {
                if (!(payload instanceof String value)) {
                    throw new IllegalArgumentException("String payload expected");
                }
                yield new OclValue.StringValue(value);
            }
            case CLASS -> {
                if (!(payload instanceof String value) || value.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Object payload must be a non-empty stable id");
                }
                yield new OclValue.ObjectValue(expectedType, value);
            }
            case SET, BAG -> throw new IllegalStateException("collection handled above");
        };
    }

    private static void requireUniqueSetItems(List<OclValue> items, String message) {
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                if (OclEquality.equal(items.get(i), items.get(j)) == OclEquality.BoolKind.TRUE) {
                    throw new IllegalArgumentException(message);
                }
            }
        }
    }

    private static void requireExactCarrierKeys(Map<?, ?> map, Set<String> expected) {
        if (!map.keySet().equals(expected)) {
            throw new IllegalArgumentException("tagged carrier keys mismatch: expected "
                    + expected + ", found " + map.keySet());
        }
    }

    private static void requireExactFiniteBinary64(java.math.BigDecimal value) {
        double encoded = value.doubleValue();
        if (!Double.isFinite(encoded)
                || new java.math.BigDecimal(encoded).compareTo(value) != 0) {
            throw new IllegalArgumentException(
                    "Real payload is not exactly representable as finite binary64: " + value);
        }
    }

    private static OclType parseTypeTag(String tag) {
        if (tag == null) {
            throw new IllegalArgumentException("result contract has no element type");
        }
        if (tag.equals("Boolean") || tag.equals("Boolean3")) return OclType.BOOLEAN;
        if (tag.equals("Integer")) return OclType.INTEGER;
        if (tag.equals("Real")) return OclType.REAL;
        if (tag.equals("String")) return OclType.STRING;
        if (tag.startsWith("Class(") && tag.endsWith(")")) {
            return OclType.clazz(tag.substring(6, tag.length() - 1));
        }
        // CypherArtifacts uses the compact Class:<key> spelling for tagged
        // object values.  Accept it here as well as the parenthesized spelling
        // used by older artifacts, otherwise VALUE decoding of object results
        // would fail even though the generated query is semantically valid.
        if (tag.startsWith("Class:") && tag.length() > "Class:".length()) {
            return OclType.clazz(tag.substring("Class:".length()));
        }
        if ((tag.startsWith("Set(") || tag.startsWith("Bag(")) && tag.endsWith(")")) {
            boolean set = tag.startsWith("Set(");
            OclType element = parseTypeTag(tag.substring(4, tag.length() - 1));
            return set ? OclType.set(element) : OclType.bag(element);
        }
        // Rule/06 angle-bracket format: Set<...> / Bag<...>
        if ((tag.startsWith("Set<") || tag.startsWith("Bag<")) && tag.endsWith(">")) {
            boolean set = tag.startsWith("Set<");
            OclType element = parseTypeTag(tag.substring(4, tag.length() - 1));
            return set ? OclType.set(element) : OclType.bag(element);
        }
        throw new IllegalArgumentException("unsupported result type tag: " + tag);
    }

    static Map<String, Object> buildParamMap(CypherAst.GeneratedArtifact artifact,
                                             Map<String, Object> extra) {
        return buildParamMap(artifact, extra, null);
    }

    static Map<String, Object> buildParamMap(CypherAst.GeneratedArtifact artifact,
                                             Map<String, Object> extra,
                                             GraphModel graph) {
        return buildParamMap(artifact.parameters(), extra, graph);
    }

    static Map<String, Object> buildParamMap(
            List<CypherAst.QueryParameter> parameters,
            Map<String, Object> extra,
            GraphModel graph) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, CypherAst.QueryParameter> declared = new LinkedHashMap<>();
        for (CypherAst.QueryParameter p : parameters) {
            if (declared.putIfAbsent(p.name(), p) != null) {
                throw new IllegalArgumentException("duplicate parameter declaration: " + p.name());
            }
            if (p.origin() == CypherAst.QueryParameter.Origin.GENERATED) {
                if (p.canonicalValue() == null) {
                    throw new IllegalArgumentException(
                            "generated parameter has no canonical value: " + p.name());
                }
                requireGeneratedParameterKind(p);
                out.put(p.name(), physicalGeneratedValue(p, graph));
            }
        }
        if (extra != null) {
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                CypherAst.QueryParameter p = declared.get(entry.getKey());
                if (p == null) {
                    throw new IllegalArgumentException(
                            "undeclared public parameter: " + entry.getKey());
                }
                if (p.origin() != CypherAst.QueryParameter.Origin.PUBLIC) {
                    throw new IllegalArgumentException(
                            "caller cannot override generated parameter: " + entry.getKey());
                }
                requireParameterKind(p, entry.getValue(), graph);
                out.put(entry.getKey(), entry.getValue());
            }
        }
        for (CypherAst.QueryParameter p : parameters) {
            if (p.origin() == CypherAst.QueryParameter.Origin.PUBLIC
                    && !out.containsKey(p.name())) {
                throw new IllegalArgumentException("missing public parameter: " + p.name());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object physicalGeneratedValue(CypherAst.QueryParameter parameter,
                                                  GraphModel graph) {
        Object value = parameter.canonicalValue();
        if (graph == null || !(value instanceof String text)) return value;
        return switch (parameter.logicalTypeTag()) {
            case "Physical:ClassKey" -> GraphKey.clazz(graph.modelKey(), text);
            case "Physical:AttributeKey" -> GraphKey.attribute(graph.modelKey(), text);
            default -> value;
        };
    }

    private static void requireParameterKind(CypherAst.QueryParameter parameter, Object value,
                                             GraphModel graph) {
        String tag = parameter.logicalTypeTag();
        if (value == null) {
            throw new IllegalArgumentException("public parameter cannot be null: "
                    + parameter.name());
        }
        if (isPhysicalParameterType(tag)) {
            if (value instanceof String) {
                return;
            }
            throw new IllegalArgumentException("wrong value kind for " + parameter.name()
                    + ": expected " + tag + ", found " + value.getClass().getSimpleName());
        }
        OclType expected = parseTypeTag(tag);
        if (!CypherArtifacts.typeTag(expected).equals(tag)) {
            throw new IllegalArgumentException("non-canonical public parameter type tag: " + tag);
        }
        decodePublicParameter(value, expected, graph);
    }

    private static boolean isPhysicalParameterType(String tag) {
        return switch (tag == null ? "" : tag) {
            case "Physical:StableObjectId", "Physical:ModelKey", "Physical:ClassKey",
                    "Physical:AttributeKey", "Physical:Role" -> true;
            default -> false;
        };
    }

    private static void requireGeneratedParameterKind(CypherAst.QueryParameter parameter) {
        String tag = parameter.logicalTypeTag();
        if (!isPhysicalParameterType(tag)) {
            throw new IllegalArgumentException("unknown generated physical parameter type: "
                    + tag);
        }
    }

    /** Decode and validate the exact public wire contract before Cypher execution. */
    static OclValue decodePublicParameter(Object raw, OclType expectedType, GraphModel graph) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("public OCL parameter must be a tagged map");
        }
        Object bottomRaw = map.get(CypherArtifacts.OCL_BOTTOM);
        if (!(bottomRaw instanceof Boolean bottom)) {
            throw new IllegalArgumentException("public parameter lacks Boolean __oclBottom");
        }
        String expectedTag = expectedType.isCollection() && !bottom
                ? CypherArtifacts.typeTag(expectedType.elementType())
                : CypherArtifacts.typeTag(expectedType);
        if (!expectedTag.equals(map.get(CypherArtifacts.OCL_TYPE))) {
            throw new IllegalArgumentException("public parameter type mismatch: expected "
                    + expectedTag + ", found " + map.get(CypherArtifacts.OCL_TYPE));
        }
        if (bottom) {
            if (expectedType.isCollection()) {
                requireCollectionShape(map, expectedType, true);
            } else {
                if (!map.containsKey(CypherArtifacts.OCL_VALUE)
                        || map.get(CypherArtifacts.OCL_VALUE) != null
                        || map.containsKey(CypherArtifacts.OCL_KIND)
                        || map.containsKey(CypherArtifacts.OCL_ITEMS)) {
                    throw new IllegalArgumentException("malformed scalar parameter bottom");
                }
            }
            return new OclValue.BottomValue(expectedType);
        }
        if (expectedType.isCollection()) {
            List<?> items = requireCollectionShape(map, expectedType, false);
            List<OclValue> decoded = new ArrayList<>(items.size());
            for (Object item : items) {
                decoded.add(decodePublicParameter(item, expectedType.elementType(), graph));
            }
            if (expectedType.kind() == OclType.Kind.SET) {
                requireUniqueSetItems(decoded,
                        "public Set parameter contains a typed-equality duplicate");
                return new OclValue.SetValue(expectedType, decoded);
            }
            return new OclValue.BagValue(expectedType, decoded);
        }
        if (map.containsKey(CypherArtifacts.OCL_KIND)
                || map.containsKey(CypherArtifacts.OCL_ITEMS)
                || !map.containsKey(CypherArtifacts.OCL_VALUE)) {
            throw new IllegalArgumentException("malformed defined scalar parameter");
        }
        Object payload = map.get(CypherArtifacts.OCL_VALUE);
        if (payload == null) {
            throw new IllegalArgumentException("defined public parameter has null payload");
        }
        return decodePublicScalar(payload, expectedType, graph);
    }

    private static List<?> requireCollectionShape(Map<?, ?> map, OclType expectedType,
                                                  boolean bottom) {
        String kind = expectedType.kind() == OclType.Kind.SET ? "SET" : "BAG";
        if (!kind.equals(map.get(CypherArtifacts.OCL_KIND))) {
            throw new IllegalArgumentException("public collection parameter kind mismatch");
        }
        Object rawItems = map.get(CypherArtifacts.OCL_ITEMS);
        if (!(rawItems instanceof List<?> items)) {
            throw new IllegalArgumentException("public collection parameter lacks item list");
        }
        if (map.containsKey(CypherArtifacts.OCL_VALUE) || bottom && !items.isEmpty()) {
            throw new IllegalArgumentException("malformed public collection parameter");
        }
        return items;
    }

    private static OclValue decodePublicScalar(Object payload, OclType expectedType,
                                               GraphModel graph) {
        return switch (expectedType.kind()) {
            case BOOLEAN -> {
                if (!(payload instanceof Boolean value)) {
                    throw new IllegalArgumentException("Boolean public payload expected");
                }
                yield new OclValue.BooleanValue(OclType.BOOLEAN,
                        value ? OclValue.BooleanValue.Bool3.TRUE
                                : OclValue.BooleanValue.Bool3.FALSE);
            }
            case INTEGER -> {
                if (!(payload instanceof String text)
                        || !text.matches("-?(0|[1-9][0-9]*)") || text.equals("-0")) {
                    throw new IllegalArgumentException(
                            "Integer public payload must be a canonical decimal string");
                }
                java.math.BigInteger value = new java.math.BigInteger(text);
                if (value.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0
                        || value.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new IllegalArgumentException(
                            "Integer public payload is outside the certified INT64 domain");
                }
                yield new OclValue.IntegerValue(value);
            }
            case REAL -> {
                if (!(payload instanceof String text)
                        || !text.matches("-?(0|[1-9][0-9]*)([.][0-9]+)?")) {
                    throw new IllegalArgumentException(
                            "Real public payload must be a canonical finite decimal string");
                }
                java.math.BigDecimal decimal = new java.math.BigDecimal(text);
                double binary = decimal.doubleValue();
                if (!Double.isFinite(binary)
                        || new java.math.BigDecimal(binary).compareTo(decimal) != 0) {
                    throw new IllegalArgumentException(
                            "Real public payload has no exact binary64 certificate");
                }
                yield new OclValue.RealValue(decimal);
            }
            case STRING -> {
                if (!(payload instanceof String text)) {
                    throw new IllegalArgumentException("String public payload expected");
                }
                yield new OclValue.StringValue(text);
            }
            case CLASS -> {
                if (!(payload instanceof String stableId)) {
                    throw new IllegalArgumentException("Object public payload must be a stable ID");
                }
                if (graph == null) {
                    throw new IllegalArgumentException(
                            "logical object parameter requires a graph-scoped execution request");
                }
                String direct;
                try {
                    direct = GraphObservation.directType(graph, stableId);
                } catch (RuntimeException missing) {
                    throw new IllegalArgumentException(
                            "object parameter does not resolve in model scope: " + stableId,
                            missing);
                }
                String expectedClass = GraphKey.clazz(graph.modelKey(), expectedType.className());
                String directClass = GraphKey.clazz(graph.modelKey(), direct);
                if (!graph.subclassClosure(expectedClass).contains(directClass)) {
                    throw new IllegalArgumentException("object parameter " + stableId
                            + " does not conform to " + expectedType.className());
                }
                yield new OclValue.ObjectValue(expectedType, stableId);
            }
            case SET, BAG -> throw new IllegalStateException("collection handled above");
        };
    }
}
