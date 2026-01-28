package org.uet.dse.neo4j.repo;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.*;

public class Neo4jObjectRepository {
    private final Driver driver;

    public Neo4jObjectRepository() {
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    /**
     * Bước 1: Tạo Node Object và nối tới Class định nghĩa (M1)
     */
    public void upsertObjectNode(org.neo4j.driver.TransactionContext tx, String objName, String className) {
        // SỬA: Tìm Class dựa trên quan hệ InstanceOf tới MetaNode (Đúng chuẩn Metamodeling)
        // Dùng nháy ngược `` cho nhãn Object (tên Class)
        String cypher = String.format(
                "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                        "MATCH (cls {name: $clsName})-[:InstanceOf]->(meta) " + // Tìm node định nghĩa M1
                        "MERGE (obj:`%s` {use_id: $objName}) " +               // Tạo node thực thể M0
                        "MERGE (obj)-[:ObjectInstanceOf]->(cls)", className);  // Nối M0 -> M1

        tx.run(cypher, org.neo4j.driver.Values.parameters("clsName", className, "objName", objName));
    }

    /**
     * Bước 2: Tạo Node AttributeValue và các liên kết (ObjectHasAttribute, InstanceOf, objectReference)
     */
    /**
     * Xóa node AttributeValue nếu thuộc tính đó bị xóa/rỗng trong USE
     */
    public void removeAttributeValueNode(TransactionContext tx, String objName, String attrName) {
        String valNodeId = objName + "_" + attrName;
        // DETACH DELETE để xóa sạch Node và các quan hệ ObjectHasAttribute, InstanceOf, objectReference
        tx.run("MATCH (v:AttributeValue {name: $vId}) DETACH DELETE v", Map.of("vId", valNodeId));
    }

    public void setAttributeValueNode(TransactionContext tx, String objName, String className,
                                      String attrName, Object value, Map<String, Object> metadata) {
        String valNodeId = objName + "_" + attrName;
        String attrDefId = className + "_" + attrName;

        // Lệnh Cypher tạo Node AttributeValue (Chỉ gán khi có giá trị)
        String cypher = String.format(
                "MATCH (obj {use_id: $objName}), (attrDef:Attribute {name: $attrDefId}) " +
                        "MERGE (val:AttributeValue {name: $valId}) " +
                        "SET val.type = $type, " +
                        "    val.value = $val, " + // Gán giá trị thực (không dùng fallback "Undefined" ở đây)
                        "    val.isCollection = $isColl, " +
                        "    val.collectionType = $collType " +
                        "MERGE (obj)-[:ObjectHasAttribute]->(val) " +
                        "MERGE (val)-[:InstanceOf]->(attrDef)");

        tx.run(cypher, Values.parameters(
                "objName", objName,
                "attrDefId", attrDefId,
                "valId", valNodeId,
                "type", metadata.get("type"),
                "val", value,
                "isColl", metadata.get("isCollection"),
                "collType", metadata.get("collectionType")
        ));

        // (Logic handleObjectReferences giữ nguyên cho cạnh objectReference)
    }

    // Trong Neo4jObjectRepository.java

    /**
     * Lấy Timestamp của lần cuối cùng tầng Object (M0) được cập nhật trên Neo4j
     * Dùng cho tính năng Sync Status Check.
     */
    public long getRemoteObjectTimestamp() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {
            // Lấy objectTimestamp từ Node ModelVersion
            String cypher = "MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.objectTimestamp AS ts";
            Result res = session.run(cypher);

            if (res.hasNext()) {
                org.neo4j.driver.Value val = res.single().get("ts");
                // Nếu giá trị không null, trả về giá trị kiểu Long
                return val.isNull() ? 0L : val.asLong();
            }
        } catch (Exception e) {
            // Im lặng bỏ qua nếu bảng chưa tồn tại, trả về 0L để bắt đầu
            return 0L;
        }
        return 0L;
    }

    // Tiện thể, đảm bảo bạn cũng có hàm lấy Model Hash cho bước isModelInSync()
    public long getRemoteModelHash() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {
            Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.modelHash AS hash");
            if (res.hasNext()) {
                org.neo4j.driver.Value val = res.single().get("hash");
                return val.isNull() ? -1L : val.asLong();
            }
        } catch (Exception e) {
            return -1L;
        }
        return -1L;
    }

    /**
     * Kiểm tra sự tồn tại của Class dưới DB (M1 layer) ngay trong transaction.
     * Giải pháp: Tìm Node có thuộc tính name khớp và nối InstanceOf tới MetaNode loại Class.
     */
    public boolean checkClassExistsInDb(org.neo4j.driver.TransactionContext tx, String className) {
        // Chúng ta tìm bất kỳ Node nào có name = className
        // mà Node đó phải là một thực thể của tầng Metamodel Class
        String cypher =
                "MATCH (meta:MetaNode) " +
                        "WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                        "MATCH (cls {name: $name})-[:InstanceOf]->(meta) " +
                        "RETURN cls LIMIT 1";

        // Sử dụng tx.run để thực hiện truy vấn bên trong transaction hiện hành
        org.neo4j.driver.Result result = tx.run(cypher, java.util.Map.of("name", className));

        return result.hasNext();
    }

    /**
     * PUSH: Tạo Link Binary (2 đầu)
     */
    public void upsertBinaryLink(TransactionContext tx, String srcObj, String tgtObj,
                                 String assocName, String label, String sRole, String tRole) {
        String cypher = String.format(
                "MATCH (a {use_id: $id1}), (b {use_id: $id2}) " +
                        "MERGE (a)-[r:%s {name: $name}]->(b) " +
                        "SET r.sourceRole = $sRole, r.targetRole = $tRole, r.isTernary = false", label);

        tx.run(cypher, Map.of("id1", srcObj, "id2", tgtObj, "name", assocName, "sRole", sRole, "tRole", tRole));
    }

    /**
     * PUSH: Tạo Link N-ary (Ternary) - Hub and Spoke
     */
    public void upsertTernaryLink(TransactionContext tx, String assocName, List<Map<String, Object>> participants) {
        // 1. Tạo Node Hub cho instance của link này (Unique ID dựa trên các đối tượng tham gia)
        String hubId = assocName + "_" + System.currentTimeMillis();
        String createHub = String.format("MERGE (h:LinkHub:`%s` {use_id: $id, name: $name})", assocName);
        tx.run(createHub, Map.of("id", hubId, "name", assocName));

        // 2. Nối các thành phần tới Hub
        for (Map<String, Object> p : participants) {
            String label = (String) p.get("label"); // LinkAssociateWith...
            String cypher = String.format(
                    "MATCH (obj {use_id: $objId}), (h:LinkHub {use_id: $hubId}) " +
                            "MERGE (obj)-[r:%s]->(h) " +
                            "SET r.role = $role, r.isTernary = true, r.index = $idx", label);

            tx.run(cypher, Map.of("objId", p.get("objName"), "hubId", hubId, "role", p.get("role"), "idx", p.get("index")));
        }
    }

    /**
     * PUSH: Tạo LinkObject (Association Class Instance)
     */
    public void upsertLinkObject(TransactionContext tx, String loName, String loClassName, List<Map<String, Object>> participants) {
        // 1. Tạo Node LinkObject
        upsertObjectNode(tx, loName, loClassName);

        // 2. Nối từ LinkObject tới các Object tham gia
        for (Map<String, Object> p : participants) {
            String label = (String) p.get("label");
            String cypher = String.format(
                    "MATCH (lo {use_id: $loId}), (p {use_id: $pId}) " +
                            "MERGE (lo)-[r:%s]->(p) " +
                            "SET r.role = $role, r.isLinkObjectPart = true", label);

            tx.run(cypher, Map.of("loId", loName, "pId", p.get("objName"), "role", p.get("role")));
        }
    }

    /**
     * Xóa triệt để một Object và các Node giá trị (AttributeValue) của nó
     */
    public void deleteObjectDeeply(TransactionContext tx, String objName) {
        // 1. Tìm Object và các Node giá trị con của nó qua cạnh ObjectHasAttribute
        // 2. Thực hiện DETACH DELETE để xóa cả Node và mọi quan hệ liên quan
        String cypher =
                "MATCH (o {use_id: $name}) " +
                        "OPTIONAL MATCH (o)-[:ObjectHasAttribute]->(val:AttributeValue) " +
                        "DETACH DELETE o, val";

        tx.run(cypher, Map.of("name", objName));
    }
}