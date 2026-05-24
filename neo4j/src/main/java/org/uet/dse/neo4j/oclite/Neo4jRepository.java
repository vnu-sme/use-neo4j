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

    public Neo4jRepository(String modelName) {
        this.modelName = modelName;
    }


    public Node findNodeById(String name) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(cls) " +
                    "MATCH (obj {use_id: $name})-[:ObjectInstanceOf]->(cls) " +
                    "RETURN obj";
                Result res = tx.run(cypher, Values.parameters("modelName", this.modelName, "name", name));
                return res.hasNext() ? res.next().get("obj").asNode() : null;
            });
        }
    }
    public Object getAttributeValue(Node sourceNode, String attrName) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (o) WHERE id(o) = $id " +
                        "MATCH (o)-[:ObjectHasAttribute]->(val:AttributeValue) " +
                        "WHERE val.name ENDS WITH $suffix " +
                        "RETURN val.value AS value, val.type AS type, val.isCollection AS isCollection";

                Result res = tx.run(cypher, Values.parameters(
                    "id", sourceNode.id(),
                    "suffix", "_" + attrName
                ));

                if (res.hasNext()) {
//                    org.neo4j.driver.Record record = res.next();
//                    Value val = record.get("value");
//                    if (val.isNull()) return null;
//                    String type = record.get("type").isNull() ? "" : record.get("type").asString();
//                    boolean isCollection = !record.get("isCollection").isNull() && record.get("isCollection").asBoolean(false);
//                    return normalizeAttributeValue(val.asObject(), type, isCollection);

                    Value val = res.next().get("value");

                    if (val.isNull()) return null;
                    if (val.type().name().equals("INTEGER")) return val.asDouble();
                    if (val.type().name().equals("FLOAT")) return val.asDouble();
                    return val.asObject();
                }
                return null;
            });
        }
    }

    public boolean isVariableExist(String varName) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
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

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {

                String cypher =
                    "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(cls {name: $clsName}) " +
                        "MATCH (cls)-[r]-(tgt) " +
                        "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
                        "AND ( " +
                        "  (startNode(r) = cls AND (r.targerClassrole = $targetName OR r.associationName = $targetName)) " +
                        "  OR " +
                        "  (endNode(r) = cls AND (r.sourceClassrole = $targetName OR r.associationName = $targetName)) " +
                        ") " +
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
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (o {use_id: $id})-[:ObjectInstanceOf]->(cls) RETURN cls.name";
                Result res = tx.run(cypher, Map.of("id", objId));
                return res.hasNext() ? res.next().get(0).asString() : null;
            });
        }
    }

    public String findClassName(Node objectNode) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher = "MATCH (o) WHERE id(o) = $id MATCH (o)-[:ObjectInstanceOf]->(cls) RETURN cls.name AS className";
                Result res = tx.run(cypher, Values.parameters("id", objectNode.id()));
                return res.hasNext() ? res.next().get("className").asString() : null;
            });
        }
    }

    public boolean isKindOf(Node objectNode, String typeName) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (o) WHERE id(o) = $id " +
                    "MATCH (o)-[:ObjectInstanceOf]->(cls) " +
                    "RETURN cls.name = $typeName OR EXISTS { MATCH (cls)-[:Extends*1..]->(parent {name: $typeName}) } AS matches";
                Result res = tx.run(cypher, Values.parameters("id", objectNode.id(), "typeName", typeName));
                return res.hasNext() && res.next().get("matches").asBoolean(false);
            });
        }
    }
    public Object findAttributeValue(Node ownerNode, String attrName) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
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

    public List<Node> findRelatedNodes(Node startNode, String relationshipType) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {

                String cypher =
                    "MATCH (s) WHERE id(s) = $id " +
                        "MATCH (s)-[r]-(t) " +
                        "WHERE type(r) STARTS WITH 'Link' " +
                        "AND ( " +
                        "  (startNode(r) = s AND (r.targetRole = $relType OR r.name = $relType)) " +
                        "  OR " +
                        "  (endNode(r) = s AND (r.sourceRole = $relType OR r.name = $relType)) " +
                        ") " +
                        "RETURN t";

                Result res = tx.run(cypher, Values.parameters(
                    "id", startNode.id(),
                    "relType", relationshipType
                ));

                List<Node> nodes = new ArrayList<>();
                while (res.hasNext()) {
                    nodes.add(res.next().get("t").asNode());
                }
                return nodes;
            });
        }
    }

    public List<Node> findAllInstancesOfClass(String className) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(target {name: $className}) " +
                    "MATCH (cls)-[:Extends*0..]->(target) " +
                    "MATCH (obj)-[:ObjectInstanceOf]->(cls) " +
                    "RETURN DISTINCT obj";

                Result res = tx.run(cypher, Values.parameters(
                    "modelName", this.modelName,
                    "className", className
                ));

                List<Node> instances = new ArrayList<>();
                while (res.hasNext()) {
                    instances.add(res.next().get("obj").asNode());
                }
                return instances;
            });
        }
    }

    private Object normalizeAttributeValue(Object rawValue, String dbType, boolean isCollection) {
        if (rawValue == null) return null;

        String raw = rawValue.toString().trim();
        if (raw.equals("Undefined") || raw.equals("COLLECTION_EMPTY")) return null;
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            raw = raw.substring(1, raw.length() - 1);
        }

        if (isCollection && raw.contains("|")) {
            List<Object> values = new ArrayList<>();
            for (String part : raw.split("\\s*\\|\\s*")) {
                if (!part.isBlank()) {
                    values.add(normalizeAttributeValue(part, dbType, false));
                }
            }
            return values;
        }

        if (dbType.equals("Int") || dbType.equals("Integer")) {
            try {
                return Long.parseLong(raw.replaceAll("[^0-9\\-]", ""));
            } catch (NumberFormatException ignored) {
                return rawValue;
            }
        }

        if (dbType.equals("Double") || dbType.equals("Real")) {
            try {
                return Double.parseDouble(raw.replaceAll("[^0-9.\\-]", ""));
            } catch (NumberFormatException ignored) {
                return rawValue;
            }
        }

        if (dbType.equals("Boolean")) {
            return Boolean.parseBoolean(raw);
        }

        if (raw.startsWith("#")) {
            return raw.substring(1);
        }

        return raw;
    }

    public boolean checkClassExistsInDb(String className) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            return session.executeRead(tx -> {
                String cypher =
                    "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta:MetaNode) " +
                        "WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                        "MATCH (cls {name: $className})-[:InstanceOf]->(meta) " +
                        "RETURN count(cls) > 0 AS exists";

                Result res = tx.run(cypher, Values.parameters(
                    "modelName", this.modelName,
                    "className", className
                ));

                return res.single().get("exists").asBoolean();
            });
        }
    }
}
