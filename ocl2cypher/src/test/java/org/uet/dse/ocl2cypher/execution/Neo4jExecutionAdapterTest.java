package org.uet.dse.ocl2cypher.execution;

import java.math.BigInteger;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.graph.GraphKey;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;

import static org.junit.jupiter.api.Assertions.*;

class Neo4jExecutionAdapterTest {

    @Test
    void recommendedIndexesCoverEveryGeneratedModelScopedLookupKey() {
        String ddl = String.join("\n",
                Neo4jExecutionAdapter.recommendedIndexStatements());
        assertTrue(ddl.contains("`Object`) ON (n.`modelKey`, n.`use_id`)"));
        assertTrue(ddl.contains("`UmlClass`) ON (n.`modelKey`, n.`classKey`)"));
        assertTrue(ddl.contains("`Attribute`) ON (n.`modelKey`, n.`attributeKey`)"));
        assertTrue(ddl.contains("`AttributeValue`) ON (n.`modelKey`, n.`attributeKey`)"));
        assertTrue(ddl.contains("`OCL2CypherManaged`) ON (n.`modelKey`)"));
    }

    @Test
    void materializationUsesManagementLabelAndKeepsCaptionPropertiesVisible() {
        GraphModel graph = new GraphModel("FamiliesValidation");
        GraphModel.Node object = new GraphModel.Node(
                "object/flanders", "FamiliesValidation", GraphModel.Projection.INSTANCE,
                "OBJECT", List.of("Object", "Family"), Map.of(
                        "modelKey", "FamiliesValidation",
                        "objectKey", "flanders",
                        "use_id", "flanders",
                        Neo4jExecutionAdapter.MANAGED_PROPERTY, "true"));

        List<String> labels = Neo4jExecutionAdapter.materializedNodeLabels(object);
        Map<String, Object> properties =
                Neo4jExecutionAdapter.materializedNodeProperties(graph, object);

        assertEquals(List.of("Object", "Family", Neo4jExecutionAdapter.MANAGED_LABEL), labels);
        assertEquals("flanders", properties.get("use_id"));
        assertEquals("flanders", properties.get("name"));
        assertEquals("flanders", Neo4jExecutionAdapter.displayLabel(object));
        assertEquals(GraphKey.object("FamiliesValidation", "flanders"),
                properties.get("objectKey"));
        assertEquals("object/flanders", properties.get("_stableKey"));
        assertFalse(properties.containsKey(Neo4jExecutionAdapter.MANAGED_PROPERTY));
    }

    @Test
    void materializationUsesReadableCaptionsInsteadOfTheSharedModelNamespace() {
        GraphModel graph = new GraphModel("FamiliesValidation");
        GraphModel.Node model = new GraphModel.Node(
                "model/FamiliesValidation", "FamiliesValidation",
                GraphModel.Projection.REPOSITORY_CONTROL, "MODEL",
                List.of("Model"), Map.of("modelKey", "FamiliesValidation"));
        GraphModel.Node member = new GraphModel.Node(
                "object/homer", "FamiliesValidation", GraphModel.Projection.INSTANCE,
                "OBJECT", List.of("Object", "FamilyMember"), Map.of(
                        "modelKey", "FamiliesValidation",
                        "objectKey", "homer",
                        "use_id", "homer"));
        GraphModel.Node slot = new GraphModel.Node(
                "slot/homer/name", "FamiliesValidation", GraphModel.Projection.INSTANCE,
                "ATTRIBUTE_VALUE", List.of("AttributeValue"), Map.of(
                        "modelKey", "FamiliesValidation",
                        "slotKey", "slot/homer/name",
                        "attributeKey", "FamilyMember::name",
                        "valueState", "DEFINED",
                        "value", "Homer"));
        graph.addNode(model);
        graph.addNode(member);
        graph.addNode(slot);

        assertEquals("FamiliesValidation", Neo4jExecutionAdapter.displayLabel(model));
        assertEquals("homer", Neo4jExecutionAdapter.displayLabel(member));
        assertEquals("name = Homer", Neo4jExecutionAdapter.displayLabel(slot));
        assertEquals("homer",
                Neo4jExecutionAdapter.materializedNodeProperties(graph, member).get("name"));
        assertEquals("name = Homer",
                Neo4jExecutionAdapter.materializedNodeProperties(graph, slot).get("name"));
        assertEquals("2",
                Neo4jExecutionAdapter.materializedNodeProperties(graph, model)
                        .get("materializationVersion"));
    }

    @Test
    void materializationPartitionsSchemaBeforeObjectSnapshot() {
        GraphModel.Node model = node("model", GraphModel.Projection.REPOSITORY_CONTROL);
        GraphModel.Node umlClass = node("class", GraphModel.Projection.SCHEMA);
        GraphModel.Node object = node("object", GraphModel.Projection.INSTANCE);
        GraphModel.Relationship generalization = relationship(
                "extends", GraphModel.Projection.SCHEMA);
        GraphModel.Relationship typing = relationship(
                "typing", GraphModel.Projection.TYPING);
        GraphModel.Relationship link = relationship(
                "link", GraphModel.Projection.INSTANCE);

        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.SCHEMA,
                Neo4jExecutionAdapter.phaseOf(model));
        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.SCHEMA,
                Neo4jExecutionAdapter.phaseOf(umlClass));
        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.SCHEMA,
                Neo4jExecutionAdapter.phaseOf(generalization));
        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.OBJECT_SNAPSHOT,
                Neo4jExecutionAdapter.phaseOf(object));
        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.OBJECT_SNAPSHOT,
                Neo4jExecutionAdapter.phaseOf(typing));
        assertEquals(Neo4jExecutionAdapter.MaterializationPhase.OBJECT_SNAPSHOT,
                Neo4jExecutionAdapter.phaseOf(link));
    }

    @Test
    void databaseSelectionIsExplicitAndBlankUsesTheProjectDefault() {
        assertEquals("analytics", new Neo4jExecutionAdapter.ExecutionConfig(
                "bolt://localhost:7687", " analytics ", "neo4j", "password")
                .database());
        assertEquals(Neo4jExecutionAdapter.DEFAULT_DATABASE,
                new Neo4jExecutionAdapter.ExecutionConfig(
                        "bolt://localhost:7687", null, "neo4j", "password")
                        .database());
        assertEquals(Neo4jExecutionAdapter.DEFAULT_DATABASE,
                Neo4jExecutionAdapter.ExecutionRequest.of(artifact(),
                        new GraphModel("model")).database());
    }

    private static CypherAst.GeneratedArtifact artifact() {
        CypherAst.CypherQuery query = new CypherAst.CypherQuery(List.of(
                new CypherAst.ReturnClause(false, List.of(
                        new CypherAst.ProjectionItem(
                                new CypherAst.IntegerLiteral(BigInteger.ONE), "result")))), true);
        return new CypherAst.GeneratedArtifact(CypherAst.Dialect.CYPHER_5, query,
                new CypherAst.ResultContract(CypherAst.ResultShape.SCALAR,
                        "result", "Integer", false, null),
                List.of(
                        new CypherAst.QueryParameter("__oclClass", "Physical:ClassKey",
                                CypherAst.QueryParameter.Origin.GENERATED, "Person"),
                        new CypherAst.QueryParameter("__oclContextId",
                                "Physical:StableObjectId",
                                CypherAst.QueryParameter.Origin.PUBLIC, null)));
    }

    @Test
    void generatedAndPublicParameterDomainsAreDisjoint() {
        Map<String, Object> params = Neo4jValueCodec.buildParamMap(artifact(),
                Map.of("__oclContextId", "p1"));
        assertEquals("Person", params.get("__oclClass"));
        assertEquals("p1", params.get("__oclContextId"));

        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.buildParamMap(artifact(), Map.of(
                        "__oclContextId", "p1", "__oclClass", "AttackerClass")));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.buildParamMap(artifact(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.buildParamMap(artifact(),
                        Map.of("__oclContextId", 7)));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.buildParamMap(artifact(), Map.of(
                        "__oclContextId", "p1", "unknown", "x")));
    }

    @Test
    void graphScopedExecutionEncodesPhysicalClassKeysButKeepsStableIdsLogical() {
        GraphModel graph = new GraphModel("model-a");
        Map<String, Object> params = Neo4jExecutionAdapter.buildParamMap(
                artifact(), Map.of("__oclContextId", "p1"), graph);

        assertEquals(GraphKey.clazz("model-a", "Person"), params.get("__oclClass"));
        assertEquals("p1", params.get("__oclContextId"));
    }

    @Test
    void taggedDecoderDistinguishesEmptyElementBottomAndWholeBottom() {
        Map<String, Object> bottomInteger = taggedBottom("Integer");
        Map<String, Object> definedOne = Map.of(
                "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1L);
        Map<String, Object> emptySet = Map.of(
                "__oclBottom", false, "__oclKind", "SET", "__oclType", "Integer",
                "__oclItems", List.of());
        Map<String, Object> setWithBottom = Map.of(
                "__oclBottom", false, "__oclKind", "SET", "__oclType", "Integer",
                "__oclItems", List.of(bottomInteger));
        Map<String, Object> bagWithDuplicates = Map.of(
                "__oclBottom", false, "__oclKind", "BAG", "__oclType", "Integer",
                "__oclItems", List.of(definedOne, definedOne));
        Map<String, Object> wholeSetBottom = taggedBottom("Set<Integer>");

        OclValue empty = Neo4jValueCodec.decodeTagged(emptySet,
                OclType.set(OclType.INTEGER));
        OclValue elementBottom = Neo4jValueCodec.decodeTagged(setWithBottom,
                OclType.set(OclType.INTEGER));
        OclValue wholeBottom = Neo4jValueCodec.decodeTagged(wholeSetBottom,
                OclType.set(OclType.INTEGER));
        OclValue bag = Neo4jValueCodec.decodeTagged(bagWithDuplicates,
                OclType.bag(OclType.INTEGER));

        assertEquals(0, ((OclValue.SetValue) empty).size());
        assertEquals(1, ((OclValue.SetValue) elementBottom).size());
        assertTrue(((OclValue.SetValue) elementBottom).members().get(0).isBottom());
        assertTrue(wholeBottom.isBottom());
        assertEquals(2, ((OclValue.BagValue) bag).size());
    }

    @Test
    void taggedDecoderAcceptsCanonicalClassTypeTag() {
        OclValue decoded = Neo4jValueCodec.decodeTagged(Map.of(
                "__oclBottom", false,
                "__oclType", "Class:Person",
                "__oclValue", "p1"), OclType.clazz("Person"));
        assertEquals(new OclValue.ObjectValue(OclType.clazz("Person"), "p1"), decoded);
    }

    @Test
    void taggedDecoderRejectsNonCanonicalSetButPreservesBagOccurrences() {
        Map<String, Object> one = Map.of(
                "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1L);
        Map<String, Object> duplicateSet = Map.of(
                "__oclBottom", false, "__oclKind", "SET", "__oclType", "Integer",
                "__oclItems", List.of(one, one));
        Map<String, Object> duplicateBag = Map.of(
                "__oclBottom", false, "__oclKind", "BAG", "__oclType", "Integer",
                "__oclItems", List.of(one, one));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(duplicateSet,
                        OclType.set(OclType.INTEGER)));
        assertTrue(error.getMessage().contains("duplicate"));
        OclValue.BagValue bag = (OclValue.BagValue) Neo4jValueCodec.decodeTagged(
                duplicateBag, OclType.bag(OclType.INTEGER));
        assertEquals(2, bag.size());
    }

    @Test
    void taggedDecoderRejectsMissingOrInconsistentTags() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclType", "Integer", "__oclValue", 1L), OclType.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "String", "__oclValue", 1L),
                        OclType.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclKind", "BAG", "__oclType", "Integer",
                        "__oclItems", List.of()), OclType.set(OclType.INTEGER)));
    }

    @Test
    void taggedDecoderRejectsNonIntegralIntegerPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1.5d),
                        OclType.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1.5f),
                        OclType.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer",
                        "__oclValue", new java.math.BigDecimal("1.5")), OclType.INTEGER));
    }

    @Test
    void taggedDecoderAcceptsIntegralIntegerPayloads() {
        OclValue fromLong = Neo4jValueCodec.decodeTagged(Map.of(
                "__oclBottom", false, "__oclType", "Integer", "__oclValue", 42L),
                OclType.INTEGER);
        assertEquals(new OclValue.IntegerValue(java.math.BigInteger.valueOf(42)), fromLong);

        OclValue fromBig = Neo4jValueCodec.decodeTagged(Map.of(
                "__oclBottom", false, "__oclType", "Integer",
                "__oclValue", java.math.BigInteger.valueOf(Long.MAX_VALUE)),
                OclType.INTEGER);
        assertEquals(new OclValue.IntegerValue(
                java.math.BigInteger.valueOf(Long.MAX_VALUE)), fromBig);

        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer",
                        "__oclValue", java.math.BigInteger.valueOf(Long.MAX_VALUE)
                                .add(java.math.BigInteger.ONE)), OclType.INTEGER));
    }

    @Test
    void taggedDecoderRejectsNonFiniteOrWrongRealPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Real", "__oclValue", Double.NaN),
                        OclType.REAL));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Real", "__oclValue", Double.POSITIVE_INFINITY),
                        OclType.REAL));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Real", "__oclValue", 1L),
                        OclType.REAL));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Real", "__oclValue", 0.1d),
                        OclType.REAL));
    }

    @Test
    void taggedDecoderAcceptsExactlyRepresentableFiniteRealPayloads() {
        OclValue fromDouble = Neo4jValueCodec.decodeTagged(Map.of(
                "__oclBottom", false, "__oclType", "Real", "__oclValue", 0.5d),
                OclType.REAL);
        assertEquals(new OclValue.RealValue(new java.math.BigDecimal("0.5")), fromDouble);

        OclValue fromBigDecimal = Neo4jValueCodec.decodeTagged(Map.of(
                "__oclBottom", false, "__oclType", "Real",
                "__oclValue", new java.math.BigDecimal("0.25")), OclType.REAL);
        assertEquals(new OclValue.RealValue(new java.math.BigDecimal("0.25")), fromBigDecimal);
    }

    @Test
    void taggedDecoderRejectsMalformedBottomShapes() {
        // scalar bottom with non-null payload
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", true, "__oclType", "Integer", "__oclValue", 1L),
                        OclType.INTEGER));
        // scalar bottom with collection fields
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", true, "__oclType", "Integer",
                        "__oclKind", "SET", "__oclItems", List.of()),
                        OclType.INTEGER));
        // collection bottom with non-empty items
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", true, "__oclKind", "SET", "__oclType", "Set<Integer>",
                        "__oclItems", List.of(Map.of(
                                "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1L))),
                        OclType.set(OclType.INTEGER)));
        // collection bottom with __oclValue
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", true, "__oclKind", "SET", "__oclType", "Set<Integer>",
                        "__oclItems", List.of(), "__oclValue", 1L),
                        OclType.set(OclType.INTEGER)));
    }

    @Test
    void taggedDecoderRejectsScalarWithCollectionFields() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1L,
                        "__oclKind", "SET", "__oclItems", List.of()),
                        OclType.INTEGER));
    }

    @Test
    void taggedDecoderRejectsCoercedScalarPayloadsAndExtraFields() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "String", "__oclValue", 7L),
                        OclType.STRING));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Class:Person", "__oclValue", 7L),
                        OclType.clazz("Person")));
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jValueCodec.decodeTagged(Map.of(
                        "__oclBottom", false, "__oclType", "Integer", "__oclValue", 1L,
                        "unexpected", true), OclType.INTEGER));
    }

    private static Map<String, Object> taggedBottom(String type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("__oclBottom", true);
        if (type.startsWith("Set<") || type.startsWith("Bag<")) {
            String kind = type.startsWith("Set<") ? "SET" : "BAG";
            map.put("__oclKind", kind);
            map.put("__oclType", type);
            map.put("__oclItems", List.of());
        } else {
            map.put("__oclType", type);
            map.put("__oclValue", null);
        }
        return map;
    }

    private static GraphModel.Node node(String key, GraphModel.Projection projection) {
        return new GraphModel.Node(key, "model", projection, "test",
                List.of("Test"), Map.of("modelKey", "model"));
    }

    private static GraphModel.Relationship relationship(
            String key, GraphModel.Projection projection) {
        return new GraphModel.Relationship(key, "model", projection, "TestRelation",
                "source", "target", Map.of("modelKey", "model"));
    }
}
