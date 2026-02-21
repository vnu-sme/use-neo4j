package org.uet.dse.neo4j.repo;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.repo.query.Neo4jObjectQuery;
import org.uet.dse.neo4j.sync.helper.OclSerializer;

import java.util.*;


public class Neo4jObjectRepository {

    public void upsertObjectNode(TransactionContext tx, String objName, String className) {
        tx.run(
                Neo4jObjectQuery.upsertObjectNode(className),
                Values.parameters("clsName", className, "objName", objName)
        );
    }

    public void deleteObjectDeeply(TransactionContext tx, String objName) {
        tx.run(Neo4jObjectQuery.DELETE_OBJECT_DEEPLY, Map.of("name", objName));
    }

    public void removeAttributeValueNode(TransactionContext tx, String objName, String attrName) {
        tx.run(
                Neo4jObjectQuery.DELETE_ATTRIBUTE_VALUE,
                Map.of("vId", attributeValueId(objName, attrName))
        );
    }

    public void setAttributeValueNode(
            TransactionContext tx,
            String objName,
            String ownerClassName,
            String attrName,
            Object value,
            Map<String, Object> metadata) {

        String valNodeId = attributeValueId(objName, attrName);
        String attrDefId = attributeDefId(ownerClassName, attrName);

        boolean isObjRef = (boolean) metadata.get("isObjectReference");
        boolean isNested = (boolean) metadata.get("isNestedCollection");
        boolean isColl = (boolean) metadata.get("isCollection");

        Object valueForNeo4j;
        if (value == null || "Undefined".equals(value)) {
            valueForNeo4j = "Undefined";
        } else if (isObjRef) {
            valueForNeo4j = "Object";
        } else {
            valueForNeo4j = OclSerializer.serialize(value);
        }



        tx.run(Neo4jObjectQuery.DELETE_ATTRIBUTE_VALUE, Map.of("vId", valNodeId));
        tx.run(Neo4jObjectQuery.CREATE_ATTRIBUTE_VALUE, Values.parameters(
                "objName", objName,
                "attrDefId", attrDefId,
                "valId", valNodeId,
                "type", metadata.get("type"),
                "val", value,
                "isColl", metadata.get("isCollection"),
                "collType", metadata.get("collectionType"),
                "isNested", isNested,
                "isObjRef", isObjRef
        ));

        // 2. Nếu là mảng lồng nhau -> Khởi tạo đệ quy
        if (isNested && value instanceof Map) {
            pushNestedStructure(tx, valNodeId, (Map<String, Object>) value, isObjRef);
        }
        else if (isObjRef && value != null && !"Undefined".equals(value)) {
            Object refData = (value instanceof Map) ? ((Map<String, Object>) value).get("items") : value;
            createObjectReferences(tx, valNodeId, refData);
        }

//        if (isObjectReference(metadata, value)) {
//            createObjectReferences(tx, valNodeId, value);
//        }
    }


    private void pushNestedStructure(TransactionContext tx, String parentId, Map<String, Object> collMap, boolean isObjRef) {
        List<Object> items = (List<Object>) collMap.get("items");

        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);

            if (item instanceof Map) {
                Map<String, Object> subColl = (Map<String, Object>) item;
                String nestedId = "nested_" + UUID.randomUUID();

                tx.run(Neo4jObjectQuery.CREATE_NESTED_NODE, Values.parameters(
                        "parentId", parentId, "nId", nestedId,
                        "cName", subColl.get("collectionType"),
                        "isNested", true, "idx", i));

                pushNestedStructure(tx, nestedId, subColl, isObjRef);
            } else {
                if (isObjRef && item != null && !"Undefined".equals(item)) {
                    tx.run(Neo4jObjectQuery.CREATE_OBJECT_REFERENCE_FROM_ANY,
                            Map.of("srcId", parentId, "tId", item.toString(), "idx", i));
                } else {
                    tx.run(Neo4jObjectQuery.SET_PRIMITIVE_VALUE_ON_NODE,
                            Map.of("nId", parentId, "val", item != null ? item : "null"));

                }
            }
        }
    }

    public boolean checkClassExistsInDb(TransactionContext tx, String className) {
        return tx.run(Neo4jObjectQuery.CHECK_CLASS_EXISTS, Map.of("name", className))
                .hasNext();
    }

    public void upsertBinaryLink(
            TransactionContext tx,
            String srcObj, String tgtObj,
            String assocName, String label,
            String sRole, String tRole) {

        tx.run(
                Neo4jObjectQuery.upsertBinaryLink(label),
                Map.of("id1", srcObj, "id2", tgtObj, "name", assocName, "sRole", sRole, "tRole", tRole)
        );
    }

    public void upsertTernaryLink(
            TransactionContext tx,
            String assocName,
            List<Map<String, Object>> participants) {

        String hubId = assocName + "_" + System.currentTimeMillis();
        tx.run(Neo4jObjectQuery.createTernaryHub(assocName), Map.of("id", hubId, "name", assocName));

        for (Map<String, Object> p : participants) {
            tx.run(
                    Neo4jObjectQuery.upsertTernarySpoke((String) p.get("label")),
                    Map.of("objId", p.get("objName"), "hubId", hubId, "role", p.get("role"), "idx", p.get("index"))
            );
        }
    }

    public void upsertLinkObject(
            TransactionContext tx,
            String loName, String loClassName,
            List<Map<String, Object>> participants) {

        upsertObjectNode(tx, loName, loClassName);

        for (Map<String, Object> p : participants) {
            tx.run(
                    Neo4jObjectQuery.upsertLinkObjectSpoke((String) p.get("label")),
                    Map.of("loId", loName, "pId", p.get("objName"), "role", p.get("role"))
            );
        }
    }

    private void createObjectReferences(TransactionContext tx, String valNodeId, Object value) {
        if (value instanceof List) {
            List<String> targets = (List<String>) value;
            for (int i = 0; i < targets.size(); i++) {
                tx.run(Neo4jObjectQuery.CREATE_OBJECT_REFERENCE,
                        Map.of("vId", valNodeId, "tId", targets.get(i), "idx", i));
            }
        } else {
            tx.run(Neo4jObjectQuery.CREATE_OBJECT_REFERENCE,
                    Map.of("vId", valNodeId, "tId", value.toString(), "idx", 0));
        }
    }

    private boolean isObjectReference(Map<String, Object> metadata, Object value) {
        return "Object".equals(metadata.get("type")) && !"Undefined".equals(value);
    }

    private static String attributeValueId(String objName, String attrName) {
        return objName + "_" + attrName;
    }

    private static String attributeDefId(String ownerClassName, String attrName) {
        return ownerClassName + "_" + attrName;
    }

}