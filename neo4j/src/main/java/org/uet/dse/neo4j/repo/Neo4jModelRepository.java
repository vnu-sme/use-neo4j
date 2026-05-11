package org.uet.dse.neo4j.repo;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.mm.core.edge.Extends;
import org.uet.dse.neo4j.mm.core.edge.NodeBinaryAssociation;
import org.uet.dse.neo4j.mm.core.node.*;
import org.uet.dse.neo4j.query.builder.CypherQueryBuilder;
import org.uet.dse.neo4j.query.builder.MetaNodeCypherBuilder;
import org.uet.dse.neo4j.query.model.MetaNodeDescriptor;
import org.uet.dse.neo4j.query.model.MetaNodeRegistry;
import org.uet.dse.neo4j.repo.query.Neo4jModelQuery;
import org.uet.dse.neo4j.sync.log.LoggingSession;
import java.util.List;
import java.util.Map;

public class Neo4jModelRepository {

    private final Driver driver;

    public Neo4jModelRepository() {
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    public void createAssociationEdge(NodeBinaryAssociation edge) {
        withSession(session -> {
            session.run(
                Neo4jModelQuery.DELETE_ASSOCIATION_EDGES,
                Values.parameters("name", edge.getAssocName())
            );
            session.run(
                Neo4jModelQuery.upsertBinaryAssociationEdge(edge.getEdgeLabel()),
                Values.parameters(
                    "assocName", edge.getAssocName(),
                    "sName",     edge.getSourceName(),
                    "sRole",     edge.getSourceRole(),
                    "sMult",     edge.getSourceMult(),
                    "sKind",     edge.getSourceKind(),
                    "tName",     edge.getTargetName(),
                    "tRole",     edge.getTargetRole(),
                    "tMult",     edge.getTargetMult(),
                    "tKind",     edge.getTargetKind()
                )
            );
        });
    }

    public void createAssociationClassStructure(NodeAssociationClass node) {
        withSession(session -> session.run(
            Neo4jModelQuery.UPSERT_ASSOCIATION_CLASS_STRUCTURE,
            Values.parameters(
                "modelName", node.getModelName(),
                "srcName",   node.getSourceName(),
                "tgtName",   node.getTargetName(),
                "acName",    node.getName(),
                "sRole",     node.getSourceRole(),
                "sMult",     node.getSourceMult(),
                "tRole",     node.getTargetRole(),
                "tMult",     node.getTargetMult()
            )
        ));
    }

    public void upsertTernaryAssociation(NodeTernaryAssociation node) {
        withSession(session -> {
            session.run(
                Neo4jModelQuery.createTernaryHub(node.getName()),
                Values.parameters("name", node.getName())
            );
            for (NodeTernarySpoke spoke : node.getSpokes()) {
                session.run(
                    Neo4jModelQuery.upsertTernarySpoke(node.getName(), spoke.getEdgeLabel()),
                    Values.parameters(
                        "modelName", node.getModelName(),
                        "clsName",   spoke.getClsName(),
                        "name",      node.getName(),
                        "idx",       spoke.getIndex(),
                        "role",      spoke.getRole(),
                        "mult",      spoke.getMult()
                    )
                );
            }
        });
    }

    public void createInstanceNode(AbstractMetaNode node, String dynamicLabel, String modelName) {
        Map<String, Object> props = node.toPropertyMap();
        requireNameProperty(props, dynamicLabel);

        withSession(session -> session.run(
                Neo4jModelQuery.upsertInstanceNode(dynamicLabel),
                Values.parameters(
                    "modelName", modelName,
                    "metaName", node.getMetaName(),
                        "id", node.getName(),
                        "props", props
                )
        ));
    }

    public void upsertClassNode(AbstractClassNode node, String dynamicLabel, String modelName) {
        Map<String, Object> props = node.toPropertyMap();
        requireNameProperty(props, dynamicLabel);

        withSession(session -> session.run(
            Neo4jModelQuery.upsertInstanceNode(dynamicLabel),
            Values.parameters(
                "modelName", modelName,
                "metaName", node.getMetaName(),
                "id", node.getName(),
                "props", props,
                "isAbstract", node.isAbstract()
            )
        ));
    }

    public void createStructuralEdge(String fromNodeName, String toNodeName, String edgeLabel) {
        withSession(session -> session.run(
                CypherQueryBuilder.buildStructuralEdgeQuery(edgeLabel),
                Values.parameters("from", fromNodeName, "to", toNodeName)
        ));
    }

    public void initializeMetamodel(String modelName) {
        List<MetaNodeDescriptor> descriptors = MetaNodeRegistry.ALL.stream()
                .map(MetaNodeDescriptor::from)
                .toList();

        withSession(session -> session.run(
            MetaNodeCypherBuilder.buildInitializeScript(modelName, descriptors)
        ));
    }

    public void upsertEnumeration(NodeEnumeration nodeEnumeration, String modelName) {
        NodeEnumeration nodeEnum = new NodeEnumeration();
        nodeEnum.setName(nodeEnumeration.getName());
        nodeEnum.setValues(nodeEnumeration.getValues());

        withSession(session -> session.run(
            CypherQueryBuilder.buildUpsertEnumerationQuery(nodeEnum.getName()),
            Values.parameters("modelName", modelName, "name", nodeEnum.getName(), "values", nodeEnum.getValues())
        ));
    }

    public void createExtendsEdge(Extends edge) {
        String cypher = CypherQueryBuilder.buildExtendsEdgeQuery();

        withSession(session -> session.run(
            cypher,
            Values.parameters(
                "srcName", edge.getChildName(),
                "tgtName", edge.getParentName()
            )
        ));
    }

    /**
     * Opens a session against the active database, runs the given block, and
     * closes the session. Centralises the repeated try-with-resources pattern
     * that previously appeared in every public method.
     */
    private void withSession(java.util.function.Consumer<Session> block) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            Session loggingSession = new LoggingSession(session);
            block.accept(loggingSession);
        }

    }

    /**
     * Resolves the binary association edge label from both ends' aggregation kinds.
     * Composition takes priority over aggregation.
     */
    private static String resolveBinaryEdgeLabel(int kind0, int kind1) {
        if (kind0 == 2 || kind1 == 2) return "ComposeOf";
        if (kind0 == 1 || kind1 == 1) return "Aggregates";
        return "AssociateWith";
    }

    private static String resolveEndEdgeLabel(int aggregationKind) {
        if (aggregationKind == 2) return "ComposeOf";
        if (aggregationKind == 1) return "Aggregates";
        return "AssociateWith";
    }

    private static void requireNameProperty(Map<String, Object> props, String label) {
        if (props.get("name") == null) {
            throw new RuntimeException("Property 'name' is required for node: " + label);
        }
    }
}