package org.uet.dse.neo4j.oclite;

import org.neo4j.driver.*;
import org.neo4j.driver.types.Node;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.oclite.expr.ExpressionNode;
import org.uet.dse.neo4j.oclite.expr.VariableExpression;
import org.uet.dse.neo4j.oclite.expr.PropertyExpression;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public class Neo4jRepository {
    private String modelName;
    private Driver driver;

    public Neo4jRepository(String modelName) {
        this.driver = Neo4jDriverManager.getInstance().getDriver();
        this.modelName = modelName;
    }


    public Node findNodeById(String name) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(cls) " +
                    "MATCH (obj {use_id: $name})-[:ObjectInstanceOf]->(cls) " +
                    "RETURN obj";
                Result res = tx.run(cypher, Values.parameters("modelName", this.modelName, "name", name));
                return res.hasNext() ? res.next().get("obj").asNode() : null;
            });
        }
    }

    public boolean isVariableExist(String varName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta:MetaNode) " +
                        "WHERE meta.name IN ['NodeConcreteClass', 'NodeAssociationClass'] " +
                        "MATCH (cls)-[:InstanceOf]->(meta) " +
                        "MATCH (obj {use_id: $varName})-[:ObjectInstanceOf]->(cls) " +
                        "RETURN count(obj) > 0 AS exists";

                Result res = tx.run(cypher, Values.parameters("modelName", this.modelName, "varName", varName));
                return res.single().get("exists").asBoolean();
            });
        }
    }


    public boolean isRelationship(ExpressionNode sourceNode, String name) {

        String sourceClassName = getClassNameOfExpression(sourceNode);
        if (sourceClassName == null) return false;

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(cls {name: $clsName}) " +
                        "MATCH (cls)-[r]->(tgt) " +
                        "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
                        "  AND (r.associationName = $targetName OR r.targerClassrole = $targetName) " +
                        "RETURN count(r) > 0 AS isRel";

                Result res = tx.run(cypher, Values.parameters(
                    "modelName", this.modelName,
                    "clsName", sourceClassName,
                    "targetName", name
                ));
                return res.single().get("isRel").asBoolean();
            });
        }
    }

    private String getClassNameOfExpression(ExpressionNode node) {

        if (node instanceof VariableExpression) {
            return findClassNameByObjectId(((VariableExpression) node).getVarName());
        }

        return null;
    }

    private String findClassNameByObjectId(String objId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (o {use_id: $id})-[:ObjectInstanceOf]->(cls) RETURN cls.name";
                Result res = tx.run(cypher, Map.of("id", objId));
                return res.hasNext() ? res.next().get(0).asString() : null;
            });
        }
    }
    public Object findAttributeValue(Node ownerNode, String attrName) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (o) WHERE id(o) = $id " +
                        "MATCH (o)-[:ObjectHasAttribute]->(val:AttributeValue) " +
                        "WHERE val.name ENDS WITH $attrName " +
                        "RETURN val.value AS value";

                Result res = tx.run(cypher, Values.parameters("id", ownerNode.id(), "attrName", "_" + attrName));
                if (res.hasNext()) {
                    var v = res.next().get("value");
                    return v.asObject();
                }
                return null;
            });
        }
    }

    public Collection<Object> findRelatedNodes(Node source, String relationshipType) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (s) WHERE id(s) = $id " +
                        "MATCH (s)-[r]->(t) " +
                        "WHERE (type(r) = $relType " +
                        "   OR r.targetRole = $relType " +
                        "   OR r.associationName = $relType " +
                        "   OR type(r) = 'Link_' + $relType) " +
                        "AND EXISTS { " +
                        "  MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta:MetaNode) " +
                        "  MATCH (cls)-[:InstanceOf]->(meta) " +
                        "  MATCH (t)-[:ObjectInstanceOf]->(cls) " +
                        "} " +
                        "RETURN t";

                Result res = tx.run(cypher, Values.parameters(
                    "id", source.id(),
                    "relType", relationshipType,
                    "modelName", this.modelName
                ));

                List<Object> relatedNodes = new ArrayList<>();
                while (res.hasNext()) {
                    relatedNodes.add(res.next().get("t").asNode());
                }
                return relatedNodes;
            });
        }
    }
}