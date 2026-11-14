package org.uet.dse.ocl2cypher.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.Stage;

/**
 * Closed physical contract for the canonical binary-UML graph profile.
 *
 * <p>This is the executable counterpart of the v7.4 {@code RepSpec} described
 * by the research specification.  Producers, observers and the Neo4j adapter
 * refer to this vocabulary instead of maintaining independent string sets.
 */
public final class Uml2GraphEncodingContract {

    public static final String PROFILE = "ocl2cypher-canonical";
    public static final String VERSION = "7.4";
    public static final String KEY_VERSION = "GraphKey-v1";

    public static final class Label {
        public static final String MODEL = "Model";
        public static final String UML_CLASS = "UmlClass";
        public static final String ATTRIBUTE = "Attribute";
        public static final String OBJECT = "Object";
        public static final String ASSOCIATION_CLASS_OBJECT = "AssociationClassObject";
        public static final String ATTRIBUTE_VALUE = "AttributeValue";

        private Label() {
        }
    }

    public static final class Relationship {
        public static final String OBJECT_INSTANCE_OF = "ObjectInstanceOf";
        public static final String INSTANCE_OF = "InstanceOf";
        public static final String HAS_ATTRIBUTE = "HasAttribute";
        public static final String OBJECT_HAS_ATTRIBUTE = "ObjectHasAttribute";
        public static final String EXTENDS = "Extends";
        public static final String ASSOCIATE_WITH = "AssociateWith";
        public static final String AGGREGATES = "Aggregates";
        public static final String COMPOSE_OF = "ComposeOf";
        public static final String ASSOCIATION_CLASS_SCHEMA_SOURCE = "sourceAssoClass";
        public static final String ASSOCIATION_CLASS_SCHEMA_TARGET = "targetAssoClass";
        public static final String LINK_ASSOCIATE_WITH = "LinkAssociateWith";
        public static final String LINK_AGGREGATES = "LinkAggregates";
        public static final String LINK_COMPOSE_OF = "LinkComposeOf";
        public static final String ASSOCIATION_CLASS_SOURCE_PARTICIPANT =
                "AssociationClassSourceParticipant";
        public static final String ASSOCIATION_CLASS_TARGET_PARTICIPANT =
                "AssociationClassTargetParticipant";

        private Relationship() {
        }
    }

    public static final class Property {
        public static final String MODEL_KEY = "modelKey";
        public static final String ENCODING_PROFILE = "encodingProfile";
        public static final String ENCODING_VERSION = "encodingVersion";
        public static final String CLASS_KEY = "classKey";
        public static final String ATTRIBUTE_KEY = "attributeKey";
        public static final String OBJECT_KEY = "objectKey";
        public static final String SLOT_KEY = "slotKey";
        public static final String USE_ID = "use_id";
        public static final String LINK_KEY = "linkKey";
        public static final String ASSOCIATION_KEY = "associationKey";
        public static final String ASSOCIATION_NAME = "associationName";
        public static final String ASSOCIATION_KIND = "associationKind";
        public static final String SOURCE_CLASS_KEY = "sourceClassKey";
        public static final String SOURCE_ROLE = "sourceRole";
        public static final String SOURCE_LOWER = "sourceLower";
        public static final String SOURCE_UPPER = "sourceUpper";
        public static final String TARGET_CLASS_KEY = "targetClassKey";
        public static final String TARGET_ROLE = "targetRole";
        public static final String TARGET_LOWER = "targetLower";
        public static final String TARGET_UPPER = "targetUpper";
        public static final String ORDERED = "ordered";
        public static final String UNIQUE = "unique";
        public static final String QUALIFIER_END = "qualifierEnd";
        public static final String SOURCE_QUALIFIER_COUNT = "sourceQualifierCount";
        public static final String TARGET_QUALIFIER_COUNT = "targetQualifierCount";
        public static final String IS_ASSOCIATION_CLASS = "isAssociationClass";
        public static final String ASSOCIATION_CLASS_OBJECT_KEY = "associationClassObjectKey";

        private Property() {
        }
    }

    public static final class Codec {
        public static final String VALUE_STATE = "valueState";
        public static final String VALUE_TYPE = "valueType";
        public static final String CODEC_ID = "codecId";
        public static final String PAYLOAD = "value";
        public static final String DEFINED = "DEFINED";
        public static final String BOTTOM = "BOTTOM";
        public static final String BOOLEAN = "ocl-boolean-v1";
        public static final String INTEGER = "ocl-integer-decimal-v1";
        public static final String REAL = "ocl-real-decimal-v1";
        public static final String STRING = "ocl-string-v1";
        public static final String OBJECT_REFERENCE = "ocl-object-reference-v1";

        private Codec() {
        }
    }

    private static final Set<String> PHYSICAL_RELATIONSHIP_TYPES = Set.of(
            Relationship.OBJECT_INSTANCE_OF,
            Relationship.INSTANCE_OF,
            Relationship.HAS_ATTRIBUTE,
            Relationship.OBJECT_HAS_ATTRIBUTE,
            Relationship.EXTENDS,
            Relationship.ASSOCIATE_WITH,
            Relationship.AGGREGATES,
            Relationship.COMPOSE_OF,
            Relationship.ASSOCIATION_CLASS_SCHEMA_SOURCE,
            Relationship.ASSOCIATION_CLASS_SCHEMA_TARGET,
            Relationship.LINK_ASSOCIATE_WITH,
            Relationship.LINK_AGGREGATES,
            Relationship.LINK_COMPOSE_OF,
            Relationship.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
            Relationship.ASSOCIATION_CLASS_TARGET_PARTICIPANT);

    private static final Set<String> SCHEMA_ASSOCIATION_PROPERTIES = Set.of(
            Property.MODEL_KEY, Property.ASSOCIATION_KEY, Property.ASSOCIATION_NAME,
            Property.ASSOCIATION_KIND, Property.SOURCE_CLASS_KEY, Property.SOURCE_ROLE,
            Property.SOURCE_LOWER, Property.SOURCE_UPPER, Property.TARGET_CLASS_KEY,
            Property.TARGET_ROLE, Property.TARGET_LOWER, Property.TARGET_UPPER,
            Property.ORDERED, Property.UNIQUE, Property.QUALIFIER_END,
            Property.SOURCE_QUALIFIER_COUNT, Property.TARGET_QUALIFIER_COUNT,
            Property.IS_ASSOCIATION_CLASS);

    private static final Set<String> LINK_PROPERTIES = Set.of(
            Property.MODEL_KEY, Property.ASSOCIATION_KEY, Property.ASSOCIATION_NAME,
            Property.SOURCE_ROLE, Property.TARGET_ROLE, Property.LINK_KEY);

    private Uml2GraphEncodingContract() {
    }

    /** A compact certificate returned after all profile-shape checks succeed. */
    public record Witness(String modelKey, int objectCount, int slotCount,
                          int relationshipCount) {
    }

    /**
     * Validate the representation-only obligations that do not require the
     * source UML schema or snapshot.  {@link ValidRepChecker} adds semantic
     * source/target correspondence checks on top of this certificate.
     */
    public static Result<Witness> validate(GraphModel graph) {
        if (graph.modelKey().isBlank()) {
            return failure("G_MODEL_SCOPE", "modelKey must not be blank");
        }
        Map<String, GraphModel.Node> nodes = new LinkedHashMap<>();
        for (GraphModel.Node node : graph.nodes()) {
            Result<Witness> common = validateElement(
                    graph.modelKey(), node.stableKey(), node.modelKey(), node.properties());
            if (common != null) return common;
            GraphKey.Decoded decoded;
            try {
                decoded = GraphKey.requireCanonical(node.stableKey(), graph.modelKey());
            } catch (IllegalArgumentException error) {
                return failure("G_KEY_CONTRACT", error.getMessage());
            }
            GraphKey.Kind expected = nodeKeyKind(node.observationRole());
            if (expected == null || decoded.kind() != expected) {
                return failure("G_NODE_CONTRACT", "node " + node.stableKey()
                        + " has role " + node.observationRole() + " but key kind "
                        + decoded.kind());
            }
            String requiredLabel = requiredLabel(node.observationRole());
            if (requiredLabel == null || !node.labels().contains(requiredLabel)) {
                return failure("G_NODE_CONTRACT", "node " + node.stableKey()
                        + " lacks canonical label for " + node.observationRole());
            }
            if (node.projection() != nodeProjection(node.observationRole())) {
                return failure("G_NODE_CONTRACT", "node " + node.stableKey()
                        + " is in the wrong graph projection");
            }
            Result<Witness> identity = validateNodeIdentity(node, decoded);
            if (identity != null) return identity;
            nodes.put(node.stableKey(), node);
        }

        long roots = graph.nodes().stream()
                .filter(node -> "MODEL_ROOT".equals(node.observationRole())).count();
        if (roots != 1) {
            return failure("G_MODEL_ROOT", "canonical graph requires exactly one model root");
        }
        GraphModel.Node root;
        try {
            root = graph.node(GraphKey.model(graph.modelKey()));
        } catch (RuntimeException error) {
            return failure("G_MODEL_ROOT", "canonical model root key is missing");
        }
        if (!PROFILE.equals(root.properties().get(Property.ENCODING_PROFILE))
                || !VERSION.equals(root.properties().get(Property.ENCODING_VERSION))) {
            return failure("G_PROFILE_VERSION", "model root must declare "
                    + PROFILE + " version " + VERSION);
        }

        Map<String, Integer> directTypingCount = new LinkedHashMap<>();
        Map<String, Integer> slotOwnershipCount = new LinkedHashMap<>();
        Map<String, Integer> slotTypingCount = new LinkedHashMap<>();
        for (GraphModel.Relationship relationship : graph.relationships()) {
            Result<Witness> common = validateElement(graph.modelKey(),
                    relationship.stableKey(), relationship.modelKey(),
                    relationship.properties());
            if (common != null) return common;
            if (!nodes.containsKey(relationship.sourceKey())
                    || !nodes.containsKey(relationship.targetKey())) {
                return failure("G_DANGLING_ENDPOINT", relationship.stableKey());
            }
            if (!PHYSICAL_RELATIONSHIP_TYPES.contains(relationship.physicalType())) {
                return failure("G_RELATIONSHIP_CONTRACT", "unregistered physical type "
                        + relationship.physicalType());
            }
            GraphKey.Decoded decoded;
            try {
                decoded = GraphKey.requireCanonical(
                        relationship.stableKey(), graph.modelKey());
            } catch (IllegalArgumentException error) {
                return failure("G_KEY_CONTRACT", error.getMessage());
            }
            GraphKey.Kind expected = relationshipKeyKind(relationship.physicalType());
            if (decoded.kind() != expected) {
                return failure("G_RELATIONSHIP_CONTRACT", "relationship "
                        + relationship.stableKey() + " has type "
                        + relationship.physicalType() + " but key kind " + decoded.kind());
            }
            if (relationship.projection()
                    != relationshipProjection(relationship.physicalType())) {
                return failure("G_RELATIONSHIP_CONTRACT", "relationship "
                        + relationship.stableKey() + " is in the wrong graph projection");
            }
            Result<Witness> shape = validateRelationshipShape(
                    relationship, nodes, directTypingCount,
                    slotOwnershipCount, slotTypingCount);
            if (shape != null) return shape;
        }

        int objects = 0;
        int slots = 0;
        for (GraphModel.Node node : graph.nodes()) {
            if (isObject(node)) {
                objects++;
                if (directTypingCount.getOrDefault(node.stableKey(), 0) != 1) {
                    return failure("G_OBJECT_TYPING", "object " + node.stableKey()
                            + " must have exactly one direct ObjectInstanceOf edge");
                }
            } else if ("ATTRIBUTE_VALUE".equals(node.observationRole())) {
                slots++;
                Result<Witness> codec = validateSlot(node);
                if (codec != null) return codec;
                if (slotOwnershipCount.getOrDefault(node.stableKey(), 0) != 1
                        || slotTypingCount.getOrDefault(node.stableKey(), 0) != 1) {
                    return failure("G_SLOT_CONTRACT", "slot " + node.stableKey()
                            + " requires exactly one ownership and one typing edge");
                }
            }
        }
        return Result.success(new Witness(graph.modelKey(), objects, slots,
                graph.relationships().size()));
    }

    private static Result<Witness> validateElement(
            String graphModelKey, String stableKey, String elementModelKey,
            Map<String, String> properties) {
        if (!graphModelKey.equals(elementModelKey)
                || !graphModelKey.equals(properties.get(Property.MODEL_KEY))) {
            return failure("G_MODEL_SCOPE", "element outside model scope: " + stableKey);
        }
        return null;
    }

    private static Result<Witness> validateRelationshipShape(
            GraphModel.Relationship relationship,
            Map<String, GraphModel.Node> nodes,
            Map<String, Integer> directTypingCount,
            Map<String, Integer> slotOwnershipCount,
            Map<String, Integer> slotTypingCount) {
        GraphModel.Node source = nodes.get(relationship.sourceKey());
        GraphModel.Node target = nodes.get(relationship.targetKey());
        String type = relationship.physicalType();
        if (Relationship.OBJECT_INSTANCE_OF.equals(type)) {
            if (!isObject(source) || !"UML_CLASS".equals(target.observationRole())) {
                return failure("G_OBJECT_TYPING", "ObjectInstanceOf must be Object -> UmlClass");
            }
            directTypingCount.merge(source.stableKey(), 1, Integer::sum);
        } else if (Relationship.EXTENDS.equals(type)) {
            if (!"UML_CLASS".equals(source.observationRole())
                    || !"UML_CLASS".equals(target.observationRole())) {
                return failure("G_RELATIONSHIP_CONTRACT", "Extends must be UmlClass -> UmlClass");
            }
        } else if (Relationship.HAS_ATTRIBUTE.equals(type)) {
            if (!"UML_CLASS".equals(source.observationRole())
                    || !"ATTRIBUTE_DECLARATION".equals(target.observationRole())) {
                return failure("G_RELATIONSHIP_CONTRACT",
                        "HasAttribute must be UmlClass -> Attribute");
            }
        } else if (Relationship.OBJECT_HAS_ATTRIBUTE.equals(type)) {
            if (!isObject(source) || !"ATTRIBUTE_VALUE".equals(target.observationRole())) {
                return failure("G_SLOT_CONTRACT", "ObjectHasAttribute must be Object -> AttributeValue");
            }
            slotOwnershipCount.merge(target.stableKey(), 1, Integer::sum);
        } else if (Relationship.INSTANCE_OF.equals(type)) {
            if (!"ATTRIBUTE_VALUE".equals(source.observationRole())
                    || !"ATTRIBUTE_DECLARATION".equals(target.observationRole())) {
                return failure("G_SLOT_CONTRACT", "InstanceOf must be AttributeValue -> Attribute");
            }
            slotTypingCount.merge(source.stableKey(), 1, Integer::sum);
        } else if (isSchemaAssociation(type)) {
            if (!"UML_CLASS".equals(source.observationRole())
                    || !"UML_CLASS".equals(target.observationRole())) {
                return failure("G_RELATIONSHIP_CONTRACT",
                        "schema association endpoints must be UmlClass nodes");
            }
        } else if (Set.of(Relationship.LINK_ASSOCIATE_WITH,
                Relationship.LINK_AGGREGATES, Relationship.LINK_COMPOSE_OF).contains(type)) {
            if (!isObject(source) || !isObject(target)) {
                return failure("G_RELATIONSHIP_CONTRACT",
                        "binary link endpoints must be object observations");
            }
        } else if (Set.of(Relationship.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
                Relationship.ASSOCIATION_CLASS_TARGET_PARTICIPANT).contains(type)) {
            if (!"ASSOCIATION_CLASS_OBJECT".equals(source.observationRole())
                    || !isObject(target)) {
                return failure("G_RELATIONSHIP_CONTRACT",
                        "association-class participant must start at its link object");
            }
        }
        if (isSchemaAssociation(type)
                && !relationship.properties().keySet().containsAll(
                        SCHEMA_ASSOCIATION_PROPERTIES)) {
            return failure("G_ASSOCIATION_METADATA", "schema association "
                    + relationship.stableKey() + " lacks canonical metadata");
        }
        if (isLink(type)
                && !relationship.properties().keySet().containsAll(LINK_PROPERTIES)) {
            return failure("G_ASSOCIATION_METADATA", "link " + relationship.stableKey()
                    + " lacks canonical metadata");
        }
        return null;
    }

    private static Result<Witness> validateNodeIdentity(
            GraphModel.Node node, GraphKey.Decoded decoded) {
        String logicalIdentity = switch (decoded.kind()) {
            case MODEL -> node.modelKey();
            case CLASS -> node.properties().get(Property.CLASS_KEY);
            case ATTRIBUTE -> node.properties().get(Property.ATTRIBUTE_KEY);
            case OBJECT -> node.properties().get(Property.OBJECT_KEY);
            case SLOT -> node.properties().get(Property.SLOT_KEY);
            default -> null;
        };
        if (decoded.kind() == GraphKey.Kind.SLOT) {
            if (!node.stableKey().equals(logicalIdentity)) {
                return failure("G_KEY_CONTRACT", "slotKey property differs from stable key");
            }
        } else if (logicalIdentity == null
                || !decoded.components().get(0).equals(logicalIdentity)) {
            return failure("G_KEY_CONTRACT", "node key and logical identity differ: "
                    + node.stableKey());
        }
        return null;
    }

    private static Result<Witness> validateSlot(GraphModel.Node slot) {
        Map<String, String> properties = slot.properties();
        for (String required : List.of(Property.SLOT_KEY, Property.ATTRIBUTE_KEY,
                Codec.VALUE_STATE, Codec.VALUE_TYPE, Codec.CODEC_ID)) {
            if (!properties.containsKey(required)) {
                return failure("G_CODEC_CONTRACT", "slot " + slot.stableKey()
                        + " lacks " + required);
            }
        }
        String state = properties.get(Codec.VALUE_STATE);
        if (Codec.BOTTOM.equals(state)) {
            if (properties.containsKey(Codec.PAYLOAD)) {
                return failure("G_CODEC_CONTRACT", "bottom slot carries a payload");
            }
        } else if (Codec.DEFINED.equals(state)) {
            if (!properties.containsKey(Codec.PAYLOAD)) {
                return failure("G_CODEC_CONTRACT", "defined slot lacks a payload");
            }
        } else {
            return failure("G_CODEC_CONTRACT", "unknown slot state " + state);
        }
        if (!Set.of(Codec.BOOLEAN, Codec.INTEGER, Codec.REAL, Codec.STRING,
                Codec.OBJECT_REFERENCE).contains(properties.get(Codec.CODEC_ID))) {
            return failure("G_CODEC_CONTRACT", "unknown codec "
                    + properties.get(Codec.CODEC_ID));
        }
        return null;
    }

    private static GraphKey.Kind nodeKeyKind(String role) {
        return switch (role) {
            case "MODEL_ROOT" -> GraphKey.Kind.MODEL;
            case "UML_CLASS" -> GraphKey.Kind.CLASS;
            case "ATTRIBUTE_DECLARATION" -> GraphKey.Kind.ATTRIBUTE;
            case "OBJECT", "ASSOCIATION_CLASS_OBJECT" -> GraphKey.Kind.OBJECT;
            case "ATTRIBUTE_VALUE" -> GraphKey.Kind.SLOT;
            default -> null;
        };
    }

    private static String requiredLabel(String role) {
        return switch (role) {
            case "MODEL_ROOT" -> Label.MODEL;
            case "UML_CLASS" -> Label.UML_CLASS;
            case "ATTRIBUTE_DECLARATION" -> Label.ATTRIBUTE;
            case "OBJECT", "ASSOCIATION_CLASS_OBJECT" -> Label.OBJECT;
            case "ATTRIBUTE_VALUE" -> Label.ATTRIBUTE_VALUE;
            default -> null;
        };
    }

    private static GraphModel.Projection nodeProjection(String role) {
        return switch (role) {
            case "MODEL_ROOT" -> GraphModel.Projection.REPOSITORY_CONTROL;
            case "UML_CLASS", "ATTRIBUTE_DECLARATION" -> GraphModel.Projection.SCHEMA;
            case "OBJECT", "ASSOCIATION_CLASS_OBJECT", "ATTRIBUTE_VALUE" ->
                    GraphModel.Projection.INSTANCE;
            default -> throw new IllegalArgumentException("unknown observation role " + role);
        };
    }

    private static GraphModel.Projection relationshipProjection(String type) {
        if (Set.of(Relationship.EXTENDS, Relationship.HAS_ATTRIBUTE,
                Relationship.ASSOCIATE_WITH, Relationship.AGGREGATES,
                Relationship.COMPOSE_OF, Relationship.ASSOCIATION_CLASS_SCHEMA_SOURCE,
                Relationship.ASSOCIATION_CLASS_SCHEMA_TARGET).contains(type)) {
            return GraphModel.Projection.SCHEMA;
        }
        if (Set.of(Relationship.OBJECT_INSTANCE_OF, Relationship.INSTANCE_OF).contains(type)) {
            return GraphModel.Projection.TYPING;
        }
        return GraphModel.Projection.INSTANCE;
    }

    private static GraphKey.Kind relationshipKeyKind(String type) {
        if (Relationship.EXTENDS.equals(type)) return GraphKey.Kind.GENERALIZATION;
        if (Relationship.HAS_ATTRIBUTE.equals(type)) return GraphKey.Kind.CLASS_ATTRIBUTE;
        if (Set.of(Relationship.ASSOCIATE_WITH, Relationship.AGGREGATES,
                Relationship.COMPOSE_OF).contains(type)) return GraphKey.Kind.ASSOCIATION;
        if (Set.of(Relationship.ASSOCIATION_CLASS_SCHEMA_SOURCE,
                Relationship.ASSOCIATION_CLASS_SCHEMA_TARGET).contains(type)) {
            return GraphKey.Kind.ASSOCIATION_END;
        }
        if (Relationship.OBJECT_INSTANCE_OF.equals(type)) return GraphKey.Kind.OBJECT_TYPING;
        if (Relationship.OBJECT_HAS_ATTRIBUTE.equals(type)) return GraphKey.Kind.SLOT_OWNERSHIP;
        if (Relationship.INSTANCE_OF.equals(type)) return GraphKey.Kind.SLOT_TYPING;
        if (Set.of(Relationship.LINK_ASSOCIATE_WITH, Relationship.LINK_AGGREGATES,
                Relationship.LINK_COMPOSE_OF).contains(type)) return GraphKey.Kind.LINK;
        return GraphKey.Kind.ASSOCIATION_CLASS_PARTICIPANT;
    }

    private static boolean isObject(GraphModel.Node node) {
        return "OBJECT".equals(node.observationRole())
                || "ASSOCIATION_CLASS_OBJECT".equals(node.observationRole());
    }

    private static boolean isSchemaAssociation(String type) {
        return Set.of(Relationship.ASSOCIATE_WITH, Relationship.AGGREGATES,
                Relationship.COMPOSE_OF, Relationship.ASSOCIATION_CLASS_SCHEMA_SOURCE,
                Relationship.ASSOCIATION_CLASS_SCHEMA_TARGET).contains(type);
    }

    private static boolean isLink(String type) {
        return Set.of(Relationship.LINK_ASSOCIATE_WITH, Relationship.LINK_AGGREGATES,
                Relationship.LINK_COMPOSE_OF,
                Relationship.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
                Relationship.ASSOCIATION_CLASS_TARGET_PARTICIPANT).contains(type);
    }

    static String simpleClassLabel(String qualifiedName) {
        String label = qualifiedName;
        for (String separator : List.of("::", "/", "#", ".")) {
            int index = label.lastIndexOf(separator);
            if (index >= 0 && index + separator.length() < label.length()) {
                label = label.substring(index + separator.length());
            }
        }
        return label.isBlank() ? "Object" : label;
    }

    private static Result<Witness> failure(String code, String message) {
        return Result.failure(Stage.F_G, code, message);
    }
}
