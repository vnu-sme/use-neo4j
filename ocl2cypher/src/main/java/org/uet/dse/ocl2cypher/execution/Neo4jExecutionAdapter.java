package org.uet.dse.ocl2cypher.execution;

import java.util.*;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.Serializer;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.Stage;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphKey;
import org.uet.dse.ocl2cypher.graph.GraphFingerprint;
import org.uet.dse.ocl2cypher.graph.Uml2GraphEncodingContract;
import org.uet.dse.ocl2cypher.runtime.OclValue;

/**
 * Execution adapter: bind {@code π = π_public ⊎ π_gen}, run the read-only
 * Cypher text, and decode the tagged result. The driver is a backend — it
 * never decides OCL semantics. On failure the adapter reports an
 * {@code EXECUTION} diagnostic, never a bottom value; callers distinguish
 * "no rows but violation-set empty" from "whole-collection bottom" solely by
 * the tagged __oclBottom flag, not by row count.
 *
 * <p>The reference test graph is materialized deterministically by writing
 * {@code GraphModel G} into Neo4j via the same bolt session that the query
 * later uses. Every written node carries its source {@code objectKey} as the
 * stable id so {@code use_id} projection is exactly the surface identity.
 * Ownership is represented by a technical node label rather than a Boolean
 * property.  Materialization also writes a presentation-only {@code name}
 * alias: object nodes use {@code use_id}, while schema and slot nodes use a
 * concise declaration/value caption.  The alias is not observed by the OCL
 * semantics, but prevents Neo4j Browser/Desktop from choosing the common
 * {@code modelKey} namespace as every node's visible caption.
 */
public final class Neo4jExecutionAdapter {

    public static final String MANAGED_PROPERTY = "_ocl2cypherManaged";
    public static final String MANAGED_LABEL = "OCL2CypherManaged";
    private static final String MATERIALIZATION_VERSION_PROPERTY =
            "materializationVersion";
    private static final String MATERIALIZATION_VERSION = "2";
    private static final String TRANSIENT_STABLE_KEY = "_stableKey";
    private static final String GRAPH_FINGERPRINT = "graphFingerprint";
    private static final List<String> RECOMMENDED_INDEXES = List.of(
            "CREATE INDEX ocl2cypher_managed_model IF NOT EXISTS "
                    + "FOR (n:`OCL2CypherManaged`) ON (n.`modelKey`)",
            "CREATE INDEX ocl2cypher_object_model IF NOT EXISTS "
                    + "FOR (n:`Object`) ON (n.`modelKey`)",
            "CREATE INDEX ocl2cypher_object_model_use_id IF NOT EXISTS "
                    + "FOR (n:`Object`) ON (n.`modelKey`, n.`use_id`)",
            "CREATE INDEX ocl2cypher_class_model_key IF NOT EXISTS "
                    + "FOR (n:`UmlClass`) ON (n.`modelKey`, n.`classKey`)",
            "CREATE INDEX ocl2cypher_attribute_model_key IF NOT EXISTS "
                    + "FOR (n:`Attribute`) ON (n.`modelKey`, n.`attributeKey`)",
            "CREATE INDEX ocl2cypher_slot_model_key IF NOT EXISTS "
                    + "FOR (n:`AttributeValue`) ON (n.`modelKey`, n.`attributeKey`)");

    enum MaterializationPhase {
        SCHEMA,
        OBJECT_SNAPSHOT
    }

    /** Prevent two in-process integration runs from replacing the same model scope. */
    private static final java.util.concurrent.ConcurrentMap<String,
            java.util.concurrent.locks.ReentrantLock> MATERIALIZATION_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Neo4jExecutionAdapter() {
    }

    public static final String DEFAULT_URI = "bolt://localhost:7687";
    public static final String DEFAULT_DATABASE = "neo4j";
    public static final String DEFAULT_USER = "neo4j";
    public static final String DEFAULT_PASSWORD = "neo4j";

    public record ExecutionConfig(String uri, String database, String user, String password) {
        public ExecutionConfig {
            Objects.requireNonNull(uri, "Neo4j URI");
            Objects.requireNonNull(user, "Neo4j user");
            Objects.requireNonNull(password, "Neo4j password");
            database = normalizedDatabase(database);
        }
    }

    public record ExecutionRequest(CypherAst.GeneratedArtifact artifact,
                                  GraphModel graph,
                                  String uri, String database, String user, String password,
                                  Map<String, Object> extraParameters) {

        public ExecutionRequest {
            Objects.requireNonNull(artifact, "generated artifact");
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(uri, "Neo4j URI");
            Objects.requireNonNull(user, "Neo4j user");
            Objects.requireNonNull(password, "Neo4j password");
            Objects.requireNonNull(extraParameters, "extra parameters");
            database = normalizedDatabase(database);
        }

        public static ExecutionRequest of(CypherAst.GeneratedArtifact artifact, GraphModel graph) {
            return new ExecutionRequest(artifact, graph,
                    DEFAULT_URI, DEFAULT_DATABASE, DEFAULT_USER, DEFAULT_PASSWORD, Map.of());
        }
    }

    public record ExecutionResult(List<String> violationIds,
                                  OclValue value,
                                  CypherAst.GeneratedArtifact artifact) {
    }

    /** Probe both server connectivity and the selected database. */
    public static Result<Boolean> checkAvailability(ExecutionConfig config) {
        try (Driver driver = GraphDatabase.driver(config.uri(),
                AuthTokens.basic(config.user(), config.password()));
             Session session = driver.session(SessionConfig.forDatabase(config.database()))) {
            driver.verifyConnectivity();
            session.run("RETURN 1 AS available").consume();
            return Result.success(Boolean.TRUE);
        } catch (RuntimeException e) {
            return Result.failure(Stage.EXECUTION, "NEO4J_UNAVAILABLE",
                    "Neo4j connectivity check failed: " + e.getMessage());
        }
    }

    public static Result<ExecutionResult> execute(Session session,
                                                  CypherAst.GeneratedArtifact artifact,
                                                  Map<String, Object> extraParams,
                                                  GraphModel graph) {
        try {
            if (graph != null) {
                requireCanonicalGraph(graph);
                requireCompatibleEncoding(session, graph);
            }
            Serializer.Serialized s = Serializer.serialize(artifact);
            Map<String, Object> params = Neo4jValueCodec.buildParamMap(s.parameters(), extraParams, graph);
            var rec = session.executeRead(tx -> tx.run(s.cypherText(), params).list());
            return Result.success(Neo4jValueCodec.decodeRecords(rec, s.contract(), artifact));
        } catch (RuntimeException e) {
            return Result.failure(Stage.EXECUTION, "EXECUTION_FAILED",
                    "Cypher execution failed: " + e.getMessage());
        }
    }

    /** Materialize {@code G} on a wiped database, then run the query. */
    public static Result<ExecutionResult> executeWithMaterializedGraph(ExecutionRequest req) {
        String lockKey = req.uri() + "\u0000" + String.valueOf(req.database())
                + "\u0000" + req.graph().modelKey();
        java.util.concurrent.locks.ReentrantLock lock = MATERIALIZATION_LOCKS.computeIfAbsent(
                lockKey, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            try (Driver driver = GraphDatabase.driver(req.uri(),
                    AuthTokens.basic(req.user(), req.password()))) {
                try (Session session = driver.session(
                        SessionConfig.forDatabase(req.database()))) {
                    return executeGraphMaterialized(session, req);
                }
            }
        } catch (RuntimeException e) {
            return Result.failure(Stage.EXECUTION, "EXECUTION_FAILED",
                    "driver/connect failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Blank/null configuration means the explicit project default, never driver-dependent DB. */
    public static String normalizedDatabase(String database) {
        return database == null || database.isBlank() ? DEFAULT_DATABASE : database.trim();
    }

    /**
     * Install the physical indexes used by generated MATCH patterns. Indexes
     * are deliberately outside the logical UML2Graph contract: they change no
     * node, relationship, property, identity or query result. Callers opt in
     * because schema-management privileges are not required for ordinary query
     * execution.
     */
    public static void ensureRecommendedIndexes(Session session) {
        Objects.requireNonNull(session, "Neo4j session");
        for (String statement : RECOMMENDED_INDEXES) {
            session.run(statement).consume();
        }
        session.run("CALL db.awaitIndexes(300)").consume();
    }

    static List<String> recommendedIndexStatements() {
        return RECOMMENDED_INDEXES;
    }

    private static Result<ExecutionResult> executeGraphMaterialized(Session session,
                                                                     ExecutionRequest req) {
        materializeGraph(session, req.graph());
        return execute(session, req.artifact(), req.extraParameters(), req.graph());
    }

    /**
     * Replace only this graph's model namespace in one atomic write transaction.
     * Repository/schema nodes and relationships are created first; object,
     * attribute-slot, typing and link occurrences are created second. A final
     * cardinality check runs before commit, so a failed snapshot phase rolls
     * the schema phase back as well.
     */
    public static boolean materializeGraph(Session session, GraphModel g) {
        requireCanonicalGraph(g);
        return session.executeWrite(tx -> {
            // Refuse a namespace containing foreign nodes, then replace only our nodes.
            String fingerprint = GraphFingerprint.sha256(g);
            Map<String, Object> model = Map.of(
                "modelKey", g.modelKey(),
                "encodingProfile", GraphModel.ENCODING_PROFILE,
                "encodingVersion", GraphModel.ENCODING_VERSION,
                MATERIALIZATION_VERSION_PROPERTY, MATERIALIZATION_VERSION,
                "graphFingerprint", fingerprint);
            long current = tx.run("MATCH (m:Model:" + quote(MANAGED_LABEL)
                        + " {modelKey:$modelKey, encodingProfile:$encodingProfile, "
                        + "encodingVersion:$encodingVersion, "
                        + MATERIALIZATION_VERSION_PROPERTY + ":$"
                        + MATERIALIZATION_VERSION_PROPERTY + ", "
                        + "graphFingerprint:$graphFingerprint}) "
                        + "RETURN count(m) AS count", model)
                .single().get("count").asLong();
            if (current == 1 && materializedCardinality(tx, g).matches(g)) {
                return Boolean.FALSE;
            }
            long foreignNodes = tx.run(
                "MATCH (n {modelKey: $modelKey}) "
                        + "WHERE NOT n:" + quote(MANAGED_LABEL) + " "
                        + "AND (n." + MANAGED_PROPERTY + " IS NULL "
                        + "OR n." + MANAGED_PROPERTY + " <> true) "
                        + "RETURN count(n) AS foreignCount", model)
                .single().get("foreignCount").asLong();
            if (foreignNodes != 0) {
                throw new IllegalStateException("MODEL_NAMESPACE_NOT_OWNED: namespace '"
                    + g.modelKey() + "' contains " + foreignNodes
                    + " node(s) not created by OCL2Cypher");
            }
            // Accept and replace both the current label-based ownership marker and
            // the property marker written by releases before canonical-v1.1.
            tx.run("MATCH (n {modelKey: $modelKey}) WHERE n:" + quote(MANAGED_LABEL)
                        + " OR n." + MANAGED_PROPERTY + " = true DETACH DELETE n",
                model).consume();
            materializeNodes(tx, g, MaterializationPhase.SCHEMA);
            materializeRelationships(tx, g, MaterializationPhase.SCHEMA);
            materializeNodes(tx, g, MaterializationPhase.OBJECT_SNAPSHOT);
            materializeRelationships(tx, g, MaterializationPhase.OBJECT_SNAPSHOT);

            validateMaterializedCardinality(tx, g);
            // Endpoint matching needs this key only while the two phases are being
            // built. Removing it keeps technical data out of Browser captions.
            tx.run("MATCH (n:" + quote(MANAGED_LABEL) + " {modelKey:$modelKey}) "
                        + "REMOVE n." + TRANSIENT_STABLE_KEY,
                model).consume();
            return Boolean.TRUE;
        });
    }

    private static void requireCanonicalGraph(GraphModel graph) {
        Result<Uml2GraphEncodingContract.Witness> contract =
                Uml2GraphEncodingContract.validate(graph);
        if (contract.isFailure()) {
            throw new IllegalArgumentException(contract.primaryDiagnostic().code()
                    + ": " + contract.primaryDiagnostic().message());
        }
    }

    private static void materializeNodes(TransactionContext tx, GraphModel graph,
                                         MaterializationPhase phase) {
        Map<List<String>, List<Map<String, Object>>> nodeBatches = new LinkedHashMap<>();
        for (var node : graph.nodes()) {
            if (phaseOf(node) != phase) continue;
            Map<String, Object> props = materializedNodeProperties(graph, node);
            nodeBatches.computeIfAbsent(materializedNodeLabels(node), ignored -> new ArrayList<>())
                    .add(props);
        }
        for (Map.Entry<List<String>, List<Map<String, Object>>> batch : nodeBatches.entrySet()) {
            String labels = batch.getKey().isEmpty() ? ""
                    : ":" + String.join(":", batch.getKey().stream()
                            .map(Neo4jExecutionAdapter::quote).toList());
            tx.run("UNWIND $rows AS row CREATE (n" + labels + ") SET n += row",
                    Map.of("rows", batch.getValue())).consume();
        }
    }

    private static void requireCompatibleEncoding(Session session, GraphModel graph) {
        Map<String, Object> parameters = Map.of(
                "modelKey", graph.modelKey(),
                "encodingProfile", GraphModel.ENCODING_PROFILE,
                "encodingVersion", GraphModel.ENCODING_VERSION,
                MATERIALIZATION_VERSION_PROPERTY, MATERIALIZATION_VERSION,
                "graphFingerprint", GraphFingerprint.sha256(graph));
        long matches = session.executeRead(tx -> tx.run(
                        "MATCH (m:Model:" + quote(MANAGED_LABEL)
                                + " {modelKey:$modelKey, encodingProfile:$encodingProfile, "
                                + "encodingVersion:$encodingVersion, "
                                + MATERIALIZATION_VERSION_PROPERTY + ":$"
                                + MATERIALIZATION_VERSION_PROPERTY + ", "
                                + "graphFingerprint:$graphFingerprint}) "
                                + "RETURN count(m) AS count", parameters)
                .single().get("count").asLong());
        if (matches != 1) {
            throw new IllegalStateException("GRAPH_ENCODING_MISMATCH: expected "
                    + GraphModel.ENCODING_PROFILE + " " + GraphModel.ENCODING_VERSION
                    + " for namespace '" + graph.modelKey() + "'");
        }
    }

    private static void materializeRelationships(TransactionContext tx, GraphModel graph,
                                                  MaterializationPhase phase) {
        Map<String, List<Map<String, Object>>> relationshipBatches = new LinkedHashMap<>();
        for (var relationship : graph.relationships()) {
            if (phaseOf(relationship) != phase) continue;
            Map<String, Object> props = new LinkedHashMap<>(relationship.properties());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("src", relationship.sourceKey());
            row.put("tgt", relationship.targetKey());
            row.put("props", props);
            relationshipBatches.computeIfAbsent(
                            relationship.physicalType(), ignored -> new ArrayList<>())
                    .add(row);
        }
        for (Map.Entry<String, List<Map<String, Object>>> batch
                 : relationshipBatches.entrySet()) {
            String q = "UNWIND $rows AS row "
                    + "MATCH (a:" + quote(MANAGED_LABEL)
                    + " { _stableKey: row.src, modelKey: $modelKey }) "
                    + "MATCH (b:" + quote(MANAGED_LABEL)
                    + " { _stableKey: row.tgt, modelKey: $modelKey }) "
                    + "CREATE (a)-[x:" + quote(batch.getKey()) + "]->(b) "
                    + "SET x += row.props";
            tx.run(q, Map.of("rows", batch.getValue(),
                    "modelKey", graph.modelKey())).consume();
        }
    }

    private static void validateMaterializedCardinality(TransactionContext tx, GraphModel graph) {
        MaterializedCardinality actual = materializedCardinality(tx, graph);
        if (!actual.matches(graph)) {
            throw new IllegalStateException("MATERIALIZATION_CARDINALITY: expected "
                    + graph.nodes().size() + " node(s)/" + graph.relationships().size()
                    + " relationship(s), found " + actual.nodes() + "/"
                    + actual.relationships());
        }
    }

    private static MaterializedCardinality materializedCardinality(
            TransactionContext tx, GraphModel graph) {
        Map<String, Object> model = Map.of("modelKey", graph.modelKey());
        long nodes = tx.run("MATCH (n:" + quote(MANAGED_LABEL)
                        + " {modelKey:$modelKey}) RETURN count(n) AS count", model)
                .single().get("count").asLong();
        long relationships = tx.run("MATCH (a:" + quote(MANAGED_LABEL)
                        + " {modelKey:$modelKey})-[r {modelKey:$modelKey}]->"
                        + "(b:" + quote(MANAGED_LABEL) + " {modelKey:$modelKey}) "
                        + "RETURN count(r) AS count", model)
                .single().get("count").asLong();
        return new MaterializedCardinality(nodes, relationships);
    }

    private record MaterializedCardinality(long nodes, long relationships) {
        boolean matches(GraphModel graph) {
            return nodes == graph.nodes().size()
                    && relationships == graph.relationships().size();
        }
    }

    static MaterializationPhase phaseOf(GraphModel.Node node) {
        return switch (node.projection()) {
            case SCHEMA, REPOSITORY_CONTROL -> MaterializationPhase.SCHEMA;
            case INSTANCE, TYPING -> MaterializationPhase.OBJECT_SNAPSHOT;
        };
    }

    static MaterializationPhase phaseOf(GraphModel.Relationship relationship) {
        return switch (relationship.projection()) {
            case SCHEMA, REPOSITORY_CONTROL -> MaterializationPhase.SCHEMA;
            case INSTANCE, TYPING -> MaterializationPhase.OBJECT_SNAPSHOT;
        };
    }

    static List<String> materializedNodeLabels(GraphModel.Node node) {
        List<String> labels = new ArrayList<>(node.labels());
        if (!labels.contains(MANAGED_LABEL)) labels.add(MANAGED_LABEL);
        return List.copyOf(labels);
    }

    static Map<String, Object> materializedNodeProperties(GraphModel graph,
                                                           GraphModel.Node node) {
        // Keep this conventional property first and stable. Neo4j's visual
        // clients commonly prefer `name` as a node caption. It is deliberately
        // added only at the physical materialization boundary, so it does not
        // enlarge the canonical GraphModel or its formal observers.
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", displayLabel(node));
        node.properties().forEach((key, value) -> {
            if (!"name".equals(key)) props.put(key, value);
        });
        scopePhysicalNodeKeys(graph, props);
        if (node.labels().contains("Model")) {
            props.put("encodingProfile", GraphModel.ENCODING_PROFILE);
            props.put("encodingVersion", GraphModel.ENCODING_VERSION);
            props.put(MATERIALIZATION_VERSION_PROPERTY, MATERIALIZATION_VERSION);
            props.put(GRAPH_FINGERPRINT, GraphFingerprint.sha256(graph));
        }
        props.put(TRANSIENT_STABLE_KEY, node.stableKey());
        props.remove(MANAGED_PROPERTY);
        return props;
    }

    /** Human-readable, non-semantic caption shared by Neo4j and the plugin graph view. */
    public static String displayLabel(GraphModel.Node node) {
        Objects.requireNonNull(node, "node");
        Map<String, String> properties = node.properties();
        String objectId = properties.get("use_id");
        if (objectId != null && !objectId.isBlank()) return objectId;

        if (properties.containsKey("slotKey")) {
            String attribute = simpleQualifiedName(properties.get("attributeKey"));
            String state = properties.get("valueState");
            String value = properties.get("value");
            if ("BOTTOM".equals(state)) return attribute + " = ⊥";
            if (value != null) return attribute + " = " + (value.isEmpty() ? "''" : value);
            return attribute + " (slot)";
        }

        for (String key : List.of("name", "qualifiedName", "classKey",
                "associationKey", "attributeKey")) {
            String value = properties.get(key);
            if (value != null && !value.isBlank()) return simpleQualifiedName(value);
        }
        if (node.labels().contains("Model")) return node.modelKey();
        String stable = node.stableKey();
        int separator = Math.max(stable.lastIndexOf('/'), stable.lastIndexOf(':'));
        return separator < 0 ? stable : stable.substring(separator + 1);
    }

    private static String simpleQualifiedName(String value) {
        if (value == null) return "?";
        int separator = Math.max(value.lastIndexOf("::"),
                Math.max(value.lastIndexOf('/'), value.lastIndexOf('#')));
        return separator < 0 ? value
                : value.substring(separator + (value.startsWith("::", separator) ? 2 : 1));
    }

    private static void scopePhysicalNodeKeys(GraphModel graph, Map<String, Object> props) {
        Object classKey = props.get("classKey");
        if (classKey instanceof String value) {
            props.put("classKey", GraphKey.clazz(graph.modelKey(), value));
        }
        Object attributeKey = props.get("attributeKey");
        if (attributeKey instanceof String value) {
            props.put("attributeKey", GraphKey.attribute(graph.modelKey(), value));
        }
        Object objectKey = props.get("objectKey");
        if (objectKey instanceof String value) {
            props.put("objectKey", GraphKey.object(graph.modelKey(), value));
        }
    }

    private static String quote(String name) {
        if (name == null) {
            return "";
        }
        return "`" + name.replace("`", "``") + "`";
    }

    public static Map<String, Object> buildParamMap(CypherAst.GeneratedArtifact artifact,
            Map<String, Object> parameters, GraphModel graph) {
        return Neo4jValueCodec.buildParamMap(artifact, parameters, graph);
    }
}
