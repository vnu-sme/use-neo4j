package org.uet.dse.ocl2cypher.graph;

import java.util.*;

/**
 * A concrete {@code GraphInstanceModel G : PGMM}.
 *
 * <p>The graph holds ONE instance model containing four logical partitions —
 * {@code G_repository, G_schema, G_type, G_snapshot} — distinguished by
 * {@link Projection}. They are partitions of this single graph, never four
 * graphs or extra metamodel roots.
 *
 * <p>Physical relationships always have direction source → target;
 * {@code INCOMING}/{@code BOTH} are logical navigation directions owned by
 * observers only. Identity is the stable model-scoped key; internal database
 * ids are never semantic identity.
 */
public final class GraphModel {

    public static final String ENCODING_PROFILE = Uml2GraphEncodingContract.PROFILE;
    public static final String ENCODING_VERSION = Uml2GraphEncodingContract.VERSION;

    public enum Projection {
        SCHEMA,
        INSTANCE,
        TYPING,
        REPOSITORY_CONTROL
    }

    /** Canonical relationship roles; each maps to one PGMM physical type. */
    public static final String OBJECT_INSTANCE_OF =
            Uml2GraphEncodingContract.Relationship.OBJECT_INSTANCE_OF;
    public static final String INSTANCE_OF = Uml2GraphEncodingContract.Relationship.INSTANCE_OF;
    public static final String HAS_ATTRIBUTE =
            Uml2GraphEncodingContract.Relationship.HAS_ATTRIBUTE;
    public static final String OBJECT_HAS_ATTRIBUTE =
            Uml2GraphEncodingContract.Relationship.OBJECT_HAS_ATTRIBUTE;
    public static final String EXTENDS = Uml2GraphEncodingContract.Relationship.EXTENDS;
    public static final String ASSOCIATE_WITH =
            Uml2GraphEncodingContract.Relationship.ASSOCIATE_WITH;
    public static final String AGGREGATES = Uml2GraphEncodingContract.Relationship.AGGREGATES;
    public static final String COMPOSE_OF = Uml2GraphEncodingContract.Relationship.COMPOSE_OF;
    public static final String ASSOCIATION_CLASS_SCHEMA_SOURCE =
            Uml2GraphEncodingContract.Relationship.ASSOCIATION_CLASS_SCHEMA_SOURCE;
    public static final String ASSOCIATION_CLASS_SCHEMA_TARGET =
            Uml2GraphEncodingContract.Relationship.ASSOCIATION_CLASS_SCHEMA_TARGET;
    public static final String LINK_ASSOCIATE_WITH =
            Uml2GraphEncodingContract.Relationship.LINK_ASSOCIATE_WITH;
    public static final String LINK_AGGREGATES =
            Uml2GraphEncodingContract.Relationship.LINK_AGGREGATES;
    public static final String LINK_COMPOSE_OF =
            Uml2GraphEncodingContract.Relationship.LINK_COMPOSE_OF;
    public static final String ASSOCIATION_CLASS_SOURCE_PARTICIPANT =
            Uml2GraphEncodingContract.Relationship.ASSOCIATION_CLASS_SOURCE_PARTICIPANT;
    public static final String ASSOCIATION_CLASS_TARGET_PARTICIPANT =
            Uml2GraphEncodingContract.Relationship.ASSOCIATION_CLASS_TARGET_PARTICIPANT;

    public record Node(String stableKey,
                       String modelKey,
                       Projection projection,
                       String observationRole,
                       List<String> labels,
                       Map<String, String> properties) {
        public Node {
            Objects.requireNonNull(stableKey, "stable key");
            Objects.requireNonNull(modelKey, "model key");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(observationRole, "observation role");
            labels = List.copyOf(labels);
            properties = Map.copyOf(properties);
        }
    }

    public record Relationship(String stableKey,
                               String modelKey,
                               Projection projection,
                               String physicalType,
                               String sourceKey,
                               String targetKey,
                               Map<String, String> properties) {
        public Relationship {
            Objects.requireNonNull(stableKey, "stable key");
            Objects.requireNonNull(modelKey, "model key");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(physicalType, "physical type");
            Objects.requireNonNull(sourceKey, "source key");
            Objects.requireNonNull(targetKey, "target key");
            properties = Map.copyOf(properties);
        }
    }

    private final String modelKey;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Relationship> relationships = new LinkedHashMap<>();

    public GraphModel(String modelKey) {
        this.modelKey = Objects.requireNonNull(modelKey, "model key");
    }

    public String modelKey() {
        return modelKey;
    }

    public void addNode(Node n) {
        requireModelScope(n.modelKey(), n.properties(), n.stableKey());
        if (nodes.containsKey(n.stableKey) || relationships.containsKey(n.stableKey)) {
            throw new IllegalStateException("G_DUPLICATE_STABLE_KEY: " + n.stableKey);
        }
        nodes.put(n.stableKey, n);
    }

    public void addRelationship(Relationship r) {
        requireModelScope(r.modelKey(), r.properties(), r.stableKey());
        if (nodes.containsKey(r.stableKey) || relationships.containsKey(r.stableKey)) {
            throw new IllegalStateException("G_DUPLICATE_STABLE_KEY: " + r.stableKey);
        }
        if (!nodes.containsKey(r.sourceKey) || !nodes.containsKey(r.targetKey)) {
            throw new IllegalStateException("G_DANGLING_ENDPOINT: " + r.stableKey);
        }
        relationships.put(r.stableKey, r);
    }

    private void requireModelScope(String declaredModelKey,
                                    Map<String, String> properties,
                                    String stableKey) {
        if (!modelKey.equals(declaredModelKey)
                || !modelKey.equals(properties.get("modelKey"))) {
            throw new IllegalArgumentException("G_MODEL_SCOPE: " + stableKey);
        }
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public Collection<Relationship> relationships() {
        return relationships.values();
    }

    public Node node(String stableKey) {
        Node n = nodes.get(stableKey);
        if (n == null) {
            throw new NoSuchElementException("no node " + stableKey);
        }
        return n;
    }

    /** Outgoing relationships of a given physical type from a node. */
    public List<Relationship> outgoing(String fromKey, String physicalType) {
        List<Relationship> r = new ArrayList<>();
        for (Relationship rel : relationships.values()) {
            if (rel.sourceKey.equals(fromKey) && rel.physicalType.equals(physicalType)) {
                r.add(rel);
            }
        }
        return r;
    }

    /** Incoming relationships of a given physical type into a node. */
    public List<Relationship> incoming(String toKey, String physicalType) {
        List<Relationship> r = new ArrayList<>();
        for (Relationship rel : relationships.values()) {
            if (rel.targetKey.equals(toKey) && rel.physicalType.equals(physicalType)) {
                r.add(rel);
            }
        }
        return r;
    }

    /** {@code Extends*} closure over direct subclass → superclass edges. */
    public Set<String> subclassClosure(String classNodeKey) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(classNodeKey);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (out.add(cur)) {
                for (Relationship r : incoming(cur, EXTENDS)) {
                    stack.push(r.sourceKey);
                }
            }
        }
        return out;
    }
}
