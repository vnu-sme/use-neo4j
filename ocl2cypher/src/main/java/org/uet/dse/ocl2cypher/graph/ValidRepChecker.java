package org.uet.dse.ocl2cypher.graph;

import static org.uet.dse.ocl2cypher.graph.RepositorySchemaValidator.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.Stage;
import org.uet.dse.ocl2cypher.runtime.OclEquality;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.model.UmlQualifier;

/** Independent executable check of the finite ValidRep obligations. */
public final class ValidRepChecker {
    private ValidRepChecker() {
    }

    /** The checked one-to-one source-object to graph-node correspondence. */
    public record Witness(Map<String, String> objectCorrespondence) {
        public Witness {
            objectCorrespondence = Map.copyOf(objectCorrespondence);
        }
    }

    public static Result<Witness> check(SchemaModel sm, Snapshot sn, GraphModel g) {
        try {
            Result<Uml2GraphEncodingContract.Witness> contract =
                    Uml2GraphEncodingContract.validate(g);
            if (contract.isFailure()) {
                return failure(contract.primaryDiagnostic().code(),
                        contract.primaryDiagnostic().message());
            }
            if (!sm.modelKey().equals(g.modelKey())) {
                return failure("VALIDREP_MODEL_KEY", "schema and graph modelKey differ");
            }
            // The encoding contract above already checks every element's model scope.
            Result<Witness> repositoryAndSchema = checkRepositoryAndSchema(sm, g);
            if (repositoryAndSchema.isFailure()) {
                return repositoryAndSchema;
            }

            Set<String> sourceIds = new LinkedHashSet<>();
            Map<String, String> mu = new LinkedHashMap<>();
            for (Snapshot.ObjectDef o : sn.objects()) {
                if (!sourceIds.add(o.stableId())) {
                    return failure("VALIDREP_DUPLICATE_SOURCE_ID", "duplicate object id " + o.stableId());
                }
                if (!sm.hasClass(o.dynamicClassKey())) {
                    return failure("VALIDREP_DYNAMIC_TYPE", "unknown dynamic class " + o.dynamicClassKey());
                }
                if (sm.clazz(o.dynamicClassKey()).isAbstract()) {
                    return failure("VALIDREP_DYNAMIC_TYPE",
                            "object instantiates abstract class " + o.dynamicClassKey());
                }
                String nodeKey = GraphKey.object(g.modelKey(), o.stableId());
                GraphModel.Node n;
                try {
                    n = g.node(nodeKey);
                } catch (RuntimeException e) {
                    return failure("VALIDREP_MISSING_OBJECT", "missing graph object " + o.stableId());
                }
                String expectedRole = sm.clazz(o.dynamicClassKey()).isAssociationClass()
                        ? "ASSOCIATION_CLASS_OBJECT" : "OBJECT";
                if (!expectedRole.equals(n.observationRole())
                        || !o.stableId().equals(n.properties().get(
                                Uml2GraphEncodingContract.Property.OBJECT_KEY))
                        || !o.stableId().equals(n.properties().get(
                                Uml2GraphEncodingContract.Property.USE_ID))
                        || !n.labels().contains("Object")
                        || !n.labels().contains(Uml2GraphEncodingContract.simpleClassLabel(
                                sm.clazz(o.dynamicClassKey()).qualifiedName()))) {
                    return failure("VALIDREP_IDENTITY", "identity mismatch for " + o.stableId());
                }
                if (!o.dynamicClassKey().equals(GraphObservation.directType(g, o.stableId()))) {
                    return failure("VALIDREP_DYNAMIC_TYPE", "dynamic type mismatch for " + o.stableId());
                }
                GraphModel.Relationship typing = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.OBJECT_TYPING,
                                o.stableId()));
                if (!GraphModel.OBJECT_INSTANCE_OF.equals(typing.physicalType())
                        || typing.projection() != GraphModel.Projection.TYPING
                        || !nodeKey.equals(typing.sourceKey())
                        || !GraphKey.clazz(g.modelKey(), o.dynamicClassKey())
                                .equals(typing.targetKey())) {
                    return failure("VALIDREP_OBJECT_TYPING",
                            "object typing differs for " + o.stableId());
                }
                mu.put(o.stableId(), nodeKey);
            }

            Set<String> graphIds = new LinkedHashSet<>();
            for (GraphModel.Node n : g.nodes()) {
                if ("OBJECT".equals(n.observationRole())
                        || "ASSOCIATION_CLASS_OBJECT".equals(n.observationRole())) {
                    String id = n.properties().get(
                            Uml2GraphEncodingContract.Property.OBJECT_KEY);
                    if (id == null || !graphIds.add(id)) {
                        return failure("VALIDREP_GHOST_OBJECT", "missing or duplicate graph object identity");
                    }
                }
            }
            if (!graphIds.equals(sourceIds)) {
                return failure("VALIDREP_GHOST_OBJECT", "source and graph object sets differ");
            }
            long typingCount = g.relationships().stream()
                    .filter(r -> GraphModel.OBJECT_INSTANCE_OF.equals(r.physicalType()))
                    .count();
            if (typingCount != sn.objects().size()) {
                return failure("VALIDREP_OBJECT_TYPING",
                        "source and graph object-typing counts differ");
            }

            Result<Witness> attributes = checkAttributes(sm, sn, g, mu);
            if (attributes.isFailure()) {
                return attributes;
            }
            Result<Witness> links = checkLinks(sm, sn, g, mu);
            if (links.isFailure()) {
                return links;
            }
            return Result.success(new Witness(mu));
        } catch (GraphValueCodec.CodecException e) {
            return failure(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return failure("VALIDREP_OBSERVER_FAILURE", e.getMessage());
        }
    }

    private static Result<Witness> checkAttributes(SchemaModel sm, Snapshot sn, GraphModel g,
                                                    Map<String, String> mu) {
        int expectedSlots = 0;
        for (Snapshot.ObjectDef o : sn.objects()) {
            for (Map.Entry<String, OclValue> slot : sn.attributeSlots(o.stableId()).entrySet()) {
                UmlAttribute declared = GraphObservation.nearestAttribute(sm, o.dynamicClassKey(), slot.getKey());
                if (declared == null) {
                    return failure("VALIDREP_UNKNOWN_SLOT", "unknown slot " + o.stableId()
                            + "." + slot.getKey());
                }
                if (!slot.getValue().type().equals(declared.declaredType())) {
                    return failure("VALIDREP_SLOT_TYPE", "slot type mismatch for "
                            + o.stableId() + "." + slot.getKey());
                }
                String slotKey = GraphKey.slot(g.modelKey(), o.stableId(), declared.key());
                GraphModel.Node slotNode = node(g, slotKey);
                GraphValueCodec.EncodedValue encoded = GraphValueCodec.encode(
                        declared.declaredType(), slot.getValue());
                Map<String, String> properties = slotNode.properties();
                // Preserve the codec's precise fail-closed diagnostic (for
                // example G_CODEC_ID/G_CODEC_BOOLEAN) before reporting a
                // generic representation-metadata mismatch.
                GraphValueCodec.decode(declared.declaredType(), properties);
                if (slotNode.projection() != GraphModel.Projection.INSTANCE
                        || !"ATTRIBUTE_VALUE".equals(slotNode.observationRole())
                        || !slotNode.labels().contains("AttributeValue")
                        || !slotKey.equals(properties.get("slotKey"))
                        || !declared.key().equals(properties.get("attributeKey"))
                        || !encoded.state().equals(
                                properties.get(GraphValueCodec.VALUE_STATE))
                        || !encoded.typeTag().equals(
                                properties.get(GraphValueCodec.VALUE_TYPE))
                        || !encoded.codecId().equals(
                                properties.get(GraphValueCodec.CODEC_ID))
                        || (GraphValueCodec.DEFINED.equals(encoded.state())
                                ? !encoded.payload().equals(
                                        properties.get(GraphValueCodec.PAYLOAD))
                                : properties.containsKey(GraphValueCodec.PAYLOAD))) {
                    return failure("VALIDREP_SLOT_ENCODING",
                            "slot encoding differs for " + o.stableId() + "."
                                    + slot.getKey());
                }
                GraphModel.Relationship ownership = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.SLOT_OWNERSHIP,
                                o.stableId(), declared.key()));
                if (!GraphModel.OBJECT_HAS_ATTRIBUTE.equals(ownership.physicalType())
                        || ownership.projection() != GraphModel.Projection.INSTANCE
                        || !mu.get(o.stableId()).equals(ownership.sourceKey())
                        || !slotKey.equals(ownership.targetKey())) {
                    return failure("VALIDREP_SLOT_ENCODING",
                            "slot ownership differs for " + o.stableId() + "."
                                    + slot.getKey());
                }
                GraphModel.Relationship typing = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.SLOT_TYPING,
                                o.stableId(), declared.key()));
                if (!GraphModel.INSTANCE_OF.equals(typing.physicalType())
                        || typing.projection() != GraphModel.Projection.TYPING
                        || !slotKey.equals(typing.sourceKey())
                        || !GraphKey.attribute(g.modelKey(), declared.key())
                                .equals(typing.targetKey())) {
                    return failure("VALIDREP_SLOT_ENCODING",
                            "slot typing differs for " + o.stableId() + "."
                                    + slot.getKey());
                }
                expectedSlots++;
            }
            for (UmlAttribute attr : applicableAttributes(sm, o.dynamicClassKey())) {
                OclValue expected = sn.attributeSlot(o.stableId(), attr.name())
                        .orElseGet(() -> new OclValue.BottomValue(attr.declaredType()));
                OclValue observed = GraphObservation.attribute(g, sm, o.stableId(),
                        attr.ownerClassKey(), attr.name());
                if (OclEquality.equal(expected, observed) != OclEquality.BoolKind.TRUE) {
                    return failure("VALIDREP_ATTRIBUTE", "attribute observation differs for "
                            + o.stableId() + "." + attr.name());
                }
            }
        }
        long actualSlots = g.nodes().stream()
                .filter(n -> "ATTRIBUTE_VALUE".equals(n.observationRole())).count();
        if (actualSlots != expectedSlots) {
            return failure("VALIDREP_GHOST_SLOT", "source and graph slot counts differ");
        }
        long actualOwnership = g.relationships().stream()
                .filter(r -> GraphModel.OBJECT_HAS_ATTRIBUTE.equals(r.physicalType()))
                .count();
        long actualTyping = g.relationships().stream()
                .filter(r -> GraphModel.INSTANCE_OF.equals(r.physicalType())).count();
        if (actualOwnership != expectedSlots || actualTyping != expectedSlots) {
            return failure("VALIDREP_SLOT_ENCODING",
                    "source and graph slot-edge counts differ");
        }
        return Result.success(new Witness(mu));
    }

    private static Result<Witness> checkLinks(SchemaModel sm, Snapshot sn, GraphModel g,
                                               Map<String, String> mu) {
        Map<LinkSignature, Integer> expected = new HashMap<>();
        for (Snapshot.LinkDef link : sn.links()) {
            UmlAssociation assoc = resolveAssociation(sm, link.associationName);
            if (assoc == null) {
                return failure("VALIDREP_UNKNOWN_ASSOCIATION", "unknown association "
                        + link.associationName);
            }
            if (!sn.hasObject(link.sourceStableId) || !sn.hasObject(link.targetStableId)) {
                return failure("VALIDREP_DANGLING_LINK", "link endpoint is absent");
            }
            if (!sm.conforms(sn.object(link.sourceStableId).dynamicClassKey(), assoc.sourceClassKey())
                    || !sm.conforms(sn.object(link.targetStableId).dynamicClassKey(), assoc.targetClassKey())) {
                return failure("VALIDREP_LINK_TYPE", "link endpoints do not conform to " + assoc.name());
            }
            List<org.uet.dse.ocl2cypher.source.model.QualifierValue> sourceQualifiers =
                    link.qualifierValuesForEnd(assoc, true);
            List<org.uet.dse.ocl2cypher.source.model.QualifierValue> targetQualifiers =
                    link.qualifierValuesForEnd(assoc, false);
            if (sourceQualifiers.size() != assoc.sourceEndQualifiers().size()
                    || targetQualifiers.size() != assoc.targetEndQualifiers().size()) {
                return failure("VALIDREP_QUALIFIER_ARITY", "qualifier arity differs for " + assoc.name());
            }
            List<String> qualifiers = new ArrayList<>();
            Result<Witness> sourceQualifierCheck = validateQualifierValues(sm, sn, assoc,
                    assoc.sourceEndQualifiers(), sourceQualifiers, qualifiers);
            if (sourceQualifierCheck.isFailure()) return sourceQualifierCheck;
            Result<Witness> targetQualifierCheck = validateQualifierValues(sm, sn, assoc,
                    assoc.targetEndQualifiers(), targetQualifiers, qualifiers);
            if (targetQualifierCheck.isFailure()) return targetQualifierCheck;
            increment(expected, new LinkSignature(assoc.key(),
                    GraphKey.object(g.modelKey(), link.sourceStableId),
                    GraphKey.object(g.modelKey(), link.targetStableId), qualifiers));
        }

        Map<String, UmlAssociation> associationsByKey = new HashMap<>();
        for (UmlAssociation association : sm.associations()) {
            associationsByKey.put(association.key(), association);
        }
        Map<LinkSignature, Integer> actual = new HashMap<>();
        for (GraphModel.Relationship r : g.relationships()) {
            if (!isBinaryLinkType(r.physicalType())) {
                continue;
            }
            UmlAssociation assoc = associationsByKey.get(r.properties().get(
                    Uml2GraphEncodingContract.Property.ASSOCIATION_KEY));
            if (assoc == null
                    || !MetamodelMapping.linkRelationshipType(assoc.associationKind()).equals(r.physicalType())
                    || !r.stableKey().equals(r.properties().get(
                            Uml2GraphEncodingContract.Property.LINK_KEY))
                    || !assoc.name().equals(r.properties().get(
                            Uml2GraphEncodingContract.Property.ASSOCIATION_NAME))
                    || !assoc.sourceRole().equals(r.properties().get(
                            Uml2GraphEncodingContract.Property.SOURCE_ROLE))
                    || !assoc.targetRole().equals(r.properties().get(
                            Uml2GraphEncodingContract.Property.TARGET_ROLE))) {
                return failure("VALIDREP_LINK_METADATA", "link metadata differs: " + r.stableKey());
            }
            List<String> qualifiers = new ArrayList<>();
            Result<Witness> storedQualifiers = appendStoredQualifierValues(r,
                    "sourceQualifier::", assoc.sourceEndQualifiers().size(), qualifiers);
            if (storedQualifiers.isFailure()) return storedQualifiers;
            storedQualifiers = appendStoredQualifierValues(r, "targetQualifier::",
                    assoc.targetEndQualifiers().size(), qualifiers);
            if (storedQualifiers.isFailure()) return storedQualifiers;
            increment(actual, new LinkSignature(assoc.key(), r.sourceKey(), r.targetKey(), qualifiers));
        }
        for (Snapshot.LinkDef link : sn.links()) {
            if (link.associationClassObjectStableId == null) continue;
            UmlAssociation assoc = resolveAssociation(sm, link.associationName);
            String occurrenceKey = GraphKey.object(
                    g.modelKey(), link.associationClassObjectStableId);
            List<GraphModel.Relationship> sourceEdges = g.outgoing(occurrenceKey,
                    GraphModel.ASSOCIATION_CLASS_SOURCE_PARTICIPANT);
            List<GraphModel.Relationship> targetEdges = g.outgoing(occurrenceKey,
                    GraphModel.ASSOCIATION_CLASS_TARGET_PARTICIPANT);
            if (sourceEdges.size() != 1 || targetEdges.size() != 1
                    || !GraphKey.object(g.modelKey(), link.sourceStableId)
                            .equals(sourceEdges.get(0).targetKey())
                    || !GraphKey.object(g.modelKey(), link.targetStableId)
                            .equals(targetEdges.get(0).targetKey())
                    || !associationClassLinkMetadataMatches(assoc, link,
                            sourceEdges.get(0), targetEdges.get(0))) {
                return failure("VALIDREP_ASSOCIATION_CLASS",
                        "association-class participants differ for "
                                + link.associationClassObjectStableId);
            }
            List<String> qualifiers = new ArrayList<>();
            for (String prefix : List.of("sourceQualifier::", "targetQualifier::")) {
                int size = prefix.startsWith("source")
                        ? assoc.sourceEndQualifiers().size()
                        : assoc.targetEndQualifiers().size();
                for (int i = 0; i < size; i++) {
                    String value = sourceEdges.get(0).properties().get(prefix + i);
                    if (value == null || !value.equals(
                            targetEdges.get(0).properties().get(prefix + i))) {
                        return failure("VALIDREP_ASSOCIATION_CLASS",
                                "association-class qualifier differs for "
                                        + link.associationClassObjectStableId);
                    }
                    qualifiers.add(value);
                }
            }
            increment(actual, new LinkSignature(assoc.key(),
                    sourceEdges.get(0).targetKey(), targetEdges.get(0).targetKey(), qualifiers));
        }
        if (!expected.equals(actual)) {
            return failure("VALIDREP_NAVIGATION", "source and graph link multisets differ");
        }
        Result<Witness> multiplicity = checkMultiplicity(sm, sn);
        if (multiplicity.isFailure()) {
            return multiplicity;
        }
        return Result.success(new Witness(mu));
    }

    private static boolean associationClassLinkMetadataMatches(
            UmlAssociation association, Snapshot.LinkDef link,
            GraphModel.Relationship source, GraphModel.Relationship target) {
        Map<String, String> sourceProperties = source.properties();
        Map<String, String> targetProperties = target.properties();
        String linkKey = sourceProperties.get(Uml2GraphEncodingContract.Property.LINK_KEY);
        return association.key().equals(sourceProperties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_KEY))
                && association.key().equals(targetProperties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_KEY))
                && association.name().equals(sourceProperties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_NAME))
                && association.name().equals(targetProperties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_NAME))
                && association.sourceRole().equals(sourceProperties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_ROLE))
                && association.sourceRole().equals(targetProperties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_ROLE))
                && association.targetRole().equals(sourceProperties.get(
                        Uml2GraphEncodingContract.Property.TARGET_ROLE))
                && association.targetRole().equals(targetProperties.get(
                        Uml2GraphEncodingContract.Property.TARGET_ROLE))
                && link.associationClassObjectStableId.equals(
                        sourceProperties.get(
                                Uml2GraphEncodingContract.Property.ASSOCIATION_CLASS_OBJECT_KEY))
                && link.associationClassObjectStableId.equals(
                        targetProperties.get(
                                Uml2GraphEncodingContract.Property.ASSOCIATION_CLASS_OBJECT_KEY))
                && linkKey != null
                && linkKey.equals(targetProperties.get(
                        Uml2GraphEncodingContract.Property.LINK_KEY))
                && GraphKey.of(source.modelKey(),
                        GraphKey.Kind.ASSOCIATION_CLASS_PARTICIPANT,
                        linkKey, "source").equals(source.stableKey())
                && GraphKey.of(target.modelKey(),
                        GraphKey.Kind.ASSOCIATION_CLASS_PARTICIPANT,
                        linkKey, "target").equals(target.stableKey());
    }

    /** Check UML end bounds on the observed link occurrence multiset. */
    private static Result<Witness> checkMultiplicity(SchemaModel sm, Snapshot sn) {
        for (UmlAssociation assoc : sm.associations()) {
            Map<Group, Integer> outgoing = new HashMap<>();
            Map<Group, Integer> incoming = new HashMap<>();
            Map<Group, Set<String>> uniqueTargets = new HashMap<>();
            for (Snapshot.LinkDef link : sn.links()) {
                UmlAssociation actual = resolveAssociation(sm, link.associationName);
                if (actual == null || !actual.key().equals(assoc.key())) {
                    continue;
                }
                Group out = new Group(link.sourceStableId,
                        qualifierPayloadsForEnd(assoc, link, false));
                Group in = new Group(link.targetStableId,
                        qualifierPayloadsForEnd(assoc, link, true));
                increment(outgoing, out);
                increment(incoming, in);
                if (assoc.isUnique()
                        && !uniqueTargets.computeIfAbsent(out, k -> new LinkedHashSet<>())
                                .add(link.targetStableId)) {
                    return failure("VALIDREP_SET_DUPLICATE",
                            "duplicate target occurrence in unique association " + assoc.name());
                }
            }

            Result<Witness> outgoingBounds = checkDirectionMultiplicity(sm, sn, assoc,
                    outgoing, assoc.sourceClassKey(), assoc.targetEndQualifiers(),
                    assoc.targetLower(), assoc.targetUpper(), "target");
            if (outgoingBounds.isFailure()) return outgoingBounds;
            Result<Witness> incomingBounds = checkDirectionMultiplicity(sm, sn, assoc,
                    incoming, assoc.targetClassKey(), assoc.sourceEndQualifiers(),
                    assoc.sourceLower(), assoc.sourceUpper(), "source");
            if (incomingBounds.isFailure()) return incomingBounds;
        }
        return Result.success(new Witness(Map.of()));
    }

    private static Result<Witness> checkDirectionMultiplicity(
            SchemaModel sm, Snapshot sn, UmlAssociation association,
            Map<Group, Integer> groups, String endpointClassKey,
            List<UmlQualifier> qualifiers, int lower, int upper, String endName) {
        if (qualifiers.isEmpty()) {
            for (Snapshot.ObjectDef endpoint : sn.objects()) {
                if (!sm.conforms(endpoint.dynamicClassKey(), endpointClassKey)) continue;
                Result<Witness> result = bound(groups.getOrDefault(
                                new Group(endpoint.stableId(), List.of()), 0),
                        lower, upper, association.name(), endName);
                if (result.isFailure()) return result;
            }
            return Result.success(new Witness(Map.of()));
        }
        boolean finiteDomainKnown = qualifiers.stream()
                .allMatch(UmlQualifier::finiteDomainComplete);
        if (!finiteDomainKnown && lower > 0) {
            return failure("VALIDREP_QUALIFIER_DOMAIN_REQUIRED",
                    "cannot check positive lower multiplicity of qualified " + endName
                            + " end of " + association.name()
                            + " without complete finite qualifier domains");
        }
        for (Map.Entry<Group, Integer> entry : groups.entrySet()) {
            Result<Witness> result = bound(entry.getValue(), lower, upper,
                    association.name(), endName + "/qualifier");
            if (result.isFailure()) return result;
        }
        if (!finiteDomainKnown) return Result.success(new Witness(Map.of()));
        List<List<String>> keys = qualifierKeys(qualifiers);
        if (keys.isEmpty() && lower > 0) {
            return failure("VALIDREP_MULTIPLICITY",
                    "positive lower multiplicity on " + association.name() + " "
                            + endName + " end is impossible with an empty qualifier domain");
        }
        for (Snapshot.ObjectDef endpoint : sn.objects()) {
            if (!sm.conforms(endpoint.dynamicClassKey(), endpointClassKey)) continue;
            for (List<String> key : keys) {
                Result<Witness> result = bound(groups.getOrDefault(
                                new Group(endpoint.stableId(), key), 0),
                        lower, upper, association.name(), endName + "/qualifier");
                if (result.isFailure()) return result;
            }
        }
        return Result.success(new Witness(Map.of()));
    }

    private static List<List<String>> qualifierKeys(List<UmlQualifier> declarations) {
        List<List<String>> keys = new ArrayList<>();
        keys.add(List.of());
        for (UmlQualifier declaration : declarations) {
            List<List<String>> expanded = new ArrayList<>();
            for (List<String> prefix : keys) {
                for (OclValue value : declaration.finiteDomain()) {
                    List<String> key = new ArrayList<>(prefix);
                    key.add(GraphValueCodec.qualifierPayload(declaration.declaredType(), value));
                    expanded.add(List.copyOf(key));
                }
            }
            keys = expanded;
        }
        return List.copyOf(keys);
    }

    private static Result<Witness> bound(int count, int lower, int upper,
                                         String association, String end) {
        if (count < lower || (upper != -1 && count > upper)) {
            return failure("VALIDREP_MULTIPLICITY",
                    association + " " + end + " multiplicity " + count
                            + " outside [" + lower + "," + (upper == -1 ? "*" : upper) + "]");
        }
        return Result.success(new Witness(Map.of()));
    }

    private record Group(String objectId, List<String> qualifiers) {
        private Group {
            qualifiers = List.copyOf(qualifiers);
        }
    }

    private record LinkSignature(String associationKey, String sourceKey, String targetKey,
                                 List<String> qualifiers) {
        private LinkSignature {
            qualifiers = List.copyOf(qualifiers);
        }
    }

    private static <T> void increment(Map<T, Integer> counts, T key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static UmlAssociation resolveAssociation(SchemaModel sm, String nameOrRole) {
        UmlAssociation a = sm.associationByName(nameOrRole);
        return a != null ? a : sm.associationByRole(nameOrRole);
    }

    private static Collection<UmlAttribute> applicableAttributes(SchemaModel sm, String classKey) {
        List<UmlAttribute> attributes = new ArrayList<>();
        for (UmlAttribute attr : sm.attributes()) {
            if (sm.conforms(classKey, attr.ownerClassKey())) {
                attributes.add(attr);
            }
        }
        return attributes;
    }

    private static List<String> qualifierPayloadsForEnd(UmlAssociation association,
                                                         Snapshot.LinkDef link,
                                                         boolean sourceEnd) {
        List<String> result = new ArrayList<>();
        List<UmlQualifier> declarations = sourceEnd
                ? association.sourceEndQualifiers() : association.targetEndQualifiers();
        List<org.uet.dse.ocl2cypher.source.model.QualifierValue> values =
                link.qualifierValuesForEnd(association, sourceEnd);
        for (int i = 0; i < values.size(); i++) {
            Object raw = values.get(i).value();
            if (!(raw instanceof OclValue value)) {
                throw new GraphValueCodec.CodecException("G_CODEC_CARRIER",
                        "qualifier value is not an OCL scalar");
            }
            result.add(GraphValueCodec.qualifierPayload(declarations.get(i).declaredType(), value));
        }
        return List.copyOf(result);
    }

    private static Result<Witness> validateQualifierValues(
            SchemaModel sm, Snapshot sn, UmlAssociation association,
            List<UmlQualifier> declarations,
            List<org.uet.dse.ocl2cypher.source.model.QualifierValue> values,
            List<String> payloads) {
        for (int index = 0; index < values.size(); index++) {
            UmlQualifier declaration = declarations.get(index);
            var stored = values.get(index);
            if (!declaration.name().equals(stored.name())) {
                return failure("VALIDREP_QUALIFIER_NAME",
                        "qualifier name differs for " + association.name());
            }
            Object raw = stored.value();
            if (!(raw instanceof OclValue value) || value.isBottom()
                    || !declaration.declaredType().equals(value.type())) {
                return failure("VALIDREP_QUALIFIER_TYPE",
                        "qualifier type differs for " + association.name()
                                + "." + declaration.name());
            }
            if (declaration.finiteDomainComplete()
                    && declaration.finiteDomain().stream().noneMatch(domainValue ->
                            OclEquality.equal(domainValue, value)
                                    == OclEquality.BoolKind.TRUE)) {
                return failure("VALIDREP_QUALIFIER_DOMAIN",
                        "qualifier value is outside the declared finite domain for "
                                + association.name() + "." + declaration.name());
            }
            GraphValueCodec.validateObjectReference(declaration.declaredType(), value, sm, sn);
            payloads.add(GraphValueCodec.qualifierPayload(declaration.declaredType(), value));
        }
        return Result.success(new Witness(Map.of()));
    }

    private static Result<Witness> appendStoredQualifierValues(
            GraphModel.Relationship relationship, String prefix, int count,
            List<String> values) {
        for (int index = 0; index < count; index++) {
            String value = relationship.properties().get(prefix + index);
            if (value == null) {
                return failure("VALIDREP_QUALIFIER_VALUE",
                        "missing qualifier on " + relationship.stableKey());
            }
            values.add(value);
        }
        return Result.success(new Witness(Map.of()));
    }

    private static boolean isBinaryLinkType(String type) {
        return GraphModel.LINK_ASSOCIATE_WITH.equals(type)
                || GraphModel.LINK_AGGREGATES.equals(type)
                || GraphModel.LINK_COMPOSE_OF.equals(type);
    }

    static Result<Witness> failure(String code, String message) {
        return Result.failure(Stage.F_G, code, message == null ? code : message);
    }
}
