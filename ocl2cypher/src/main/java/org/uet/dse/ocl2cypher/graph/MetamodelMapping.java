package org.uet.dse.ocl2cypher.graph;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation.AssociationKind;

/**
 * The closed M2 witness {@code rules(T_MM)} for the supported UMLMM profile.
 *
 * <p>This object contains vocabulary only.  It has no M1 declaration keys and
 * creates no graph elements.  {@link MetamodelTranslator} instantiates these
 * templates for one {@code SchemaModel}; {@link GraphBuilder} then consumes
 * that catalogue instead of embedding physical PGMM names in its rules.
 */
public final class MetamodelMapping {

    public enum NodeKind {
        MODEL,
        CLASS,
        ATTRIBUTE,
        OBJECT,
        ASSOCIATION_CLASS_OBJECT,
        ATTRIBUTE_VALUE
    }

    public enum RelationshipKind {
        GENERALIZATION,
        CLASS_ATTRIBUTE,
        BINARY_ASSOCIATION,
        ASSOCIATION_CLASS_SCHEMA_SOURCE,
        ASSOCIATION_CLASS_SCHEMA_TARGET,
        ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
        ASSOCIATION_CLASS_TARGET_PARTICIPANT,
        OBJECT_TYPING,
        OBJECT_ATTRIBUTE,
        SLOT_TYPING,
        BINARY_LINK
    }

    public enum KeyKind {
        MODEL_KEY,
        CLASS_KEY,
        ATTRIBUTE_KEY,
        ASSOCIATION_KEY,
        OBJECT_KEY,
        SLOT_KEY,
        GENERALIZATION_KEY,
        LINK_KEY
    }

    public record NodeBinding(NodeKind sourceKind,
                              GraphModel.Projection projection,
                              String observationRole,
                              List<String> labels,
                              KeyKind keyKind) {
        public NodeBinding {
            Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(projection);
            Objects.requireNonNull(observationRole);
            labels = List.copyOf(labels);
            if (labels.isEmpty()) {
                throw new IllegalArgumentException("node binding requires a label");
            }
            Objects.requireNonNull(keyKind);
        }
    }

    public record RelationshipBinding(RelationshipKind sourceReference,
                                      GraphModel.Projection projection,
                                      String physicalType,
                                      NodeKind sourceKind,
                                      NodeKind targetKind,
                                      KeyKind keyKind) {
        public RelationshipBinding {
            Objects.requireNonNull(sourceReference);
            Objects.requireNonNull(projection);
            Objects.requireNonNull(physicalType);
            Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(targetKind);
            Objects.requireNonNull(keyKind);
        }
    }

    public record PropertyBinding(OclType.Kind sourceKind,
                                  String physicalName,
                                  String codecId) {
        public PropertyBinding {
            Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(physicalName);
            Objects.requireNonNull(codecId);
        }
    }

    private final Map<NodeKind, NodeBinding> nodes;
    private final Map<RelationshipKind, RelationshipBinding> relationships;
    private final Map<OclType.Kind, PropertyBinding> properties;

    private MetamodelMapping(Map<NodeKind, NodeBinding> nodes,
                             Map<RelationshipKind, RelationshipBinding> relationships,
                             Map<OclType.Kind, PropertyBinding> properties) {
        this.nodes = immutableEnumMap(NodeKind.class, nodes);
        this.relationships = immutableEnumMap(RelationshipKind.class, relationships);
        this.properties = immutableEnumMap(OclType.Kind.class, properties);
        requireComplete(NodeKind.values(), this.nodes, "node");
        requireComplete(RelationshipKind.values(), this.relationships, "relationship");
        for (OclType.Kind kind : List.of(OclType.Kind.BOOLEAN, OclType.Kind.INTEGER,
                OclType.Kind.REAL, OclType.Kind.STRING, OclType.Kind.CLASS)) {
            if (!this.properties.containsKey(kind)) {
                throw new IllegalArgumentException("missing property binding for " + kind);
            }
        }
    }

    /** Canonical supported UMLMM-to-PGMM profile. */
    public static MetamodelMapping canonical() {
        Map<NodeKind, NodeBinding> nodes = new EnumMap<>(NodeKind.class);
        nodes.put(NodeKind.MODEL, node(NodeKind.MODEL,
                GraphModel.Projection.REPOSITORY_CONTROL, "MODEL_ROOT",
                Uml2GraphEncodingContract.Label.MODEL,
                KeyKind.MODEL_KEY));
        nodes.put(NodeKind.CLASS, node(NodeKind.CLASS,
                GraphModel.Projection.SCHEMA, "UML_CLASS",
                Uml2GraphEncodingContract.Label.UML_CLASS,
                KeyKind.CLASS_KEY));
        nodes.put(NodeKind.ATTRIBUTE, node(NodeKind.ATTRIBUTE,
                GraphModel.Projection.SCHEMA, "ATTRIBUTE_DECLARATION",
                Uml2GraphEncodingContract.Label.ATTRIBUTE,
                KeyKind.ATTRIBUTE_KEY));
        nodes.put(NodeKind.OBJECT, node(NodeKind.OBJECT,
                GraphModel.Projection.INSTANCE, "OBJECT",
                Uml2GraphEncodingContract.Label.OBJECT, KeyKind.OBJECT_KEY));
        nodes.put(NodeKind.ASSOCIATION_CLASS_OBJECT,
                new NodeBinding(NodeKind.ASSOCIATION_CLASS_OBJECT,
                        GraphModel.Projection.INSTANCE, "ASSOCIATION_CLASS_OBJECT",
                        List.of(Uml2GraphEncodingContract.Label.OBJECT,
                                Uml2GraphEncodingContract.Label.ASSOCIATION_CLASS_OBJECT),
                        KeyKind.OBJECT_KEY));
        nodes.put(NodeKind.ATTRIBUTE_VALUE, node(NodeKind.ATTRIBUTE_VALUE,
                GraphModel.Projection.INSTANCE, "ATTRIBUTE_VALUE",
                Uml2GraphEncodingContract.Label.ATTRIBUTE_VALUE,
                KeyKind.SLOT_KEY));

        Map<RelationshipKind, RelationshipBinding> relationships =
                new EnumMap<>(RelationshipKind.class);
        relationships.put(RelationshipKind.GENERALIZATION, relationship(
                RelationshipKind.GENERALIZATION, GraphModel.Projection.SCHEMA,
                GraphModel.EXTENDS, NodeKind.CLASS, NodeKind.CLASS,
                KeyKind.GENERALIZATION_KEY));
        relationships.put(RelationshipKind.CLASS_ATTRIBUTE, relationship(
                RelationshipKind.CLASS_ATTRIBUTE, GraphModel.Projection.SCHEMA,
                GraphModel.HAS_ATTRIBUTE, NodeKind.CLASS, NodeKind.ATTRIBUTE,
                KeyKind.ATTRIBUTE_KEY));
        relationships.put(RelationshipKind.BINARY_ASSOCIATION, relationship(
                RelationshipKind.BINARY_ASSOCIATION, GraphModel.Projection.SCHEMA,
                GraphModel.ASSOCIATE_WITH, NodeKind.CLASS, NodeKind.CLASS,
                KeyKind.ASSOCIATION_KEY));
        relationships.put(RelationshipKind.ASSOCIATION_CLASS_SCHEMA_SOURCE, relationship(
                RelationshipKind.ASSOCIATION_CLASS_SCHEMA_SOURCE,
                GraphModel.Projection.SCHEMA,
                GraphModel.ASSOCIATION_CLASS_SCHEMA_SOURCE,
                NodeKind.CLASS, NodeKind.CLASS,
                KeyKind.ASSOCIATION_KEY));
        relationships.put(RelationshipKind.ASSOCIATION_CLASS_SCHEMA_TARGET, relationship(
                RelationshipKind.ASSOCIATION_CLASS_SCHEMA_TARGET,
                GraphModel.Projection.SCHEMA,
                GraphModel.ASSOCIATION_CLASS_SCHEMA_TARGET,
                NodeKind.CLASS, NodeKind.CLASS,
                KeyKind.ASSOCIATION_KEY));
        relationships.put(RelationshipKind.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
                relationship(RelationshipKind.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
                        GraphModel.Projection.INSTANCE, "$Association_SourceParticipant",
                        NodeKind.ASSOCIATION_CLASS_OBJECT, NodeKind.OBJECT,
                        KeyKind.LINK_KEY));
        relationships.put(RelationshipKind.ASSOCIATION_CLASS_TARGET_PARTICIPANT,
                relationship(RelationshipKind.ASSOCIATION_CLASS_TARGET_PARTICIPANT,
                        GraphModel.Projection.INSTANCE, "$Association_TargetParticipant",
                        NodeKind.ASSOCIATION_CLASS_OBJECT, NodeKind.OBJECT,
                        KeyKind.LINK_KEY));
        relationships.put(RelationshipKind.OBJECT_TYPING, relationship(
                RelationshipKind.OBJECT_TYPING, GraphModel.Projection.TYPING,
                GraphModel.OBJECT_INSTANCE_OF, NodeKind.OBJECT, NodeKind.CLASS,
                KeyKind.OBJECT_KEY));
        relationships.put(RelationshipKind.OBJECT_ATTRIBUTE, relationship(
                RelationshipKind.OBJECT_ATTRIBUTE, GraphModel.Projection.INSTANCE,
                GraphModel.OBJECT_HAS_ATTRIBUTE, NodeKind.OBJECT,
                NodeKind.ATTRIBUTE_VALUE, KeyKind.SLOT_KEY));
        relationships.put(RelationshipKind.SLOT_TYPING, relationship(
                RelationshipKind.SLOT_TYPING, GraphModel.Projection.TYPING,
                GraphModel.INSTANCE_OF, NodeKind.ATTRIBUTE_VALUE, NodeKind.ATTRIBUTE,
                KeyKind.SLOT_KEY));
        relationships.put(RelationshipKind.BINARY_LINK, relationship(
                RelationshipKind.BINARY_LINK, GraphModel.Projection.INSTANCE,
                GraphModel.LINK_ASSOCIATE_WITH, NodeKind.OBJECT, NodeKind.OBJECT,
                KeyKind.LINK_KEY));

        Map<OclType.Kind, PropertyBinding> properties = new EnumMap<>(OclType.Kind.class);
        for (OclType type : List.of(OclType.BOOLEAN, OclType.INTEGER,
                OclType.REAL, OclType.STRING, OclType.clazz("$Class"))) {
            properties.put(type.kind(), new PropertyBinding(type.kind(),
                    GraphValueCodec.PAYLOAD, GraphValueCodec.codecId(type)));
        }
        return new MetamodelMapping(nodes, relationships, properties);
    }

    static String schemaRelationshipType(AssociationKind kind) {
        return switch (kind) {
            case ASSOCIATION -> GraphModel.ASSOCIATE_WITH;
            case AGGREGATION -> GraphModel.AGGREGATES;
            case COMPOSITION -> GraphModel.COMPOSE_OF;
        };
    }

    static String linkRelationshipType(AssociationKind kind) {
        return switch (kind) {
            case ASSOCIATION -> GraphModel.LINK_ASSOCIATE_WITH;
            case AGGREGATION -> GraphModel.LINK_AGGREGATES;
            case COMPOSITION -> GraphModel.LINK_COMPOSE_OF;
        };
    }

    public NodeBinding node(NodeKind kind) {
        return required(nodes, kind, "node");
    }

    public RelationshipBinding relationship(RelationshipKind kind) {
        return required(relationships, kind, "relationship");
    }

    /** Instantiate an association-class participant template with its physical type. */
    public RelationshipBinding associationClassParticipant(RelationshipKind kind) {
        RelationshipBinding template = relationship(kind);
        String physicalType = switch (kind) {
            case ASSOCIATION_CLASS_SOURCE_PARTICIPANT ->
                    GraphModel.ASSOCIATION_CLASS_SOURCE_PARTICIPANT;
            case ASSOCIATION_CLASS_TARGET_PARTICIPANT ->
                    GraphModel.ASSOCIATION_CLASS_TARGET_PARTICIPANT;
            default -> throw new IllegalArgumentException(
                    "not an association-class participant template: " + kind);
        };
        return new RelationshipBinding(template.sourceReference(), template.projection(),
                physicalType, template.sourceKind(), template.targetKind(), template.keyKind());
    }

    public PropertyBinding property(OclType type) {
        if (type.isCollection()) {
            throw new IllegalArgumentException("collection attributes are outside T_MM");
        }
        return required(properties, type.kind(), "property");
    }

    public Map<NodeKind, NodeBinding> nodes() {
        return nodes;
    }

    public Map<RelationshipKind, RelationshipBinding> relationships() {
        return relationships;
    }

    private static NodeBinding node(NodeKind kind, GraphModel.Projection projection,
                                    String role, String label, KeyKind key) {
        return new NodeBinding(kind, projection, role, List.of(label), key);
    }

    private static RelationshipBinding relationship(
            RelationshipKind kind, GraphModel.Projection projection, String physicalType,
            NodeKind source, NodeKind target, KeyKind key) {
        return new RelationshipBinding(kind, projection, physicalType, source, target, key);
    }

    private static <E extends Enum<E>, V> Map<E, V> immutableEnumMap(
            Class<E> enumType, Map<E, V> values) {
        EnumMap<E, V> copy = new EnumMap<>(enumType);
        copy.putAll(values);
        return Collections.unmodifiableMap(copy);
    }

    private static <E, V> V required(Map<E, V> map, E key, String family) {
        V value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("missing " + family + " binding for " + key);
        }
        return value;
    }

    private static <E> void requireComplete(E[] values, Map<E, ?> map, String family) {
        for (E value : values) {
            if (!map.containsKey(value)) {
                throw new IllegalArgumentException("missing " + family + " binding for " + value);
            }
        }
    }
}
