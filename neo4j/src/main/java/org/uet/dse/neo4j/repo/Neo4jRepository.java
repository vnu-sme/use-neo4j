package org.uet.dse.neo4j.repo;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationClass;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MClassifier;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;

import java.util.List;
import java.util.Map;

public class Neo4jRepository {
    private final Driver driver;

    public Neo4jRepository() {
        // Lấy driver từ Manager đã connect thành công
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    /**
     * Tạo Node tổng quát (Dùng cho đặc tả 1-7)
     */
    public void createNode(String label, Map<String, Object> params) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            // MERGE theo name để tránh trùng lặp, SET các thuộc tính còn lại
            String query = String.format("MERGE (n:%s {name: $name}) SET n += $params", label);
            session.run(query, Values.parameters("name", params.get("name"), "params", params));
        }
    }

    /**
     * Tạo quan hệ kế thừa (Đặc tả 8 & 12)
     */
    public void createExtendsEdge(MClassifier child, MClassifier parent) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String cypher = "MATCH (src {name: $srcName}), (tgt {name: $tgtName}) " +
                    "MERGE (src)-[r:Extends]->(tgt) " +
                    "SET r.name = $edgeName, r.sourceName = $srcName, r.targetName = $tgtName, " +
                    "    r.sourceMultiplicity = '1', r.targetMultiplicity = '1'";

            session.run(cypher, Values.parameters(
                    "srcName", child.name(),
                    "tgtName", parent.name(),
                    "edgeName", child.name() + "Inherits" + parent.name()
            ));
        }
    }

    /**
     * Tạo quan hệ Association (Đặc tả 9, 10, 11)
     */
    public void createAssociationEdge(MAssociation assoc) {
        if (assoc.associationEnds().size() < 2) return;

        MAssociationEnd source = assoc.associationEnds().get(0);
        MAssociationEnd target = assoc.associationEnds().get(1);

        String edgeLabel = "AssociateWith";
        int kind = target.aggregationKind();
        if (kind == 2) edgeLabel = "ComposeOf";
        else if (kind == 1) edgeLabel = "Aggregates";

        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String cypher = String.format(
                    "MATCH (s {name: $sName}), (t {name: $tName}) " +
                            "MERGE (s)-[r:%s {associationName: $assocName}]->(t) " +
                            "SET r.sourceClassName = $sName, " +
                            "    r.sourceClassrole = $sRole, " +
                            "    r.sourceMultiplicity = $sMult, " + // LƯU ĐẦU SOURCE
                            "    r.targetClassName = $tName, " +
                            "    r.targerClassrole = $tRole, " +
                            "    r.targetMultiplicity = $tMult",    // LƯU ĐẦU TARGET
                    edgeLabel
            );

            session.run(cypher, Values.parameters(
                    "assocName", assoc.name(),
                    "sName", source.cls().name(),
                    "sRole", source.name(),
                    "sMult", source.multiplicity().toString(), // Dùng hàm toString() của MMultiplicity
                    "tName", target.cls().name(),
                    "tRole", target.name(),
                    "tMult", target.multiplicity().toString()  // Dùng hàm toString() của MMultiplicity
            ));
        }
    }

    /**
     * Tạo các quan hệ cấu trúc (Đặc tả 13, 14, 15)
     */
    public void createStructuralEdge(String fromNodeName, String toNodeName, String edgeLabel) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            // MATCH linh hoạt: b có thể là bất cứ nhãn nào (ConcreteClass, AbstractClass, Enumeration)
            String cypher = String.format(
                    "MATCH (a {name: $from}), (b {name: $to}) " +
                            "MERGE (a)-[r:%s]->(b)", edgeLabel);

            session.run(cypher, Values.parameters("from", fromNodeName, "to", toNodeName));
        }
    }



    /**
     * Khởi tạo các Meta-nodes (M2 Layer) cho Database
     * Chạy như một Script khởi tạo khi tạo mới DB hoặc Session
     */
    public void initializeMetamodel() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String script =
                    "MERGE (:MetaNode {name: 'NodeAttribute', label: 'Attribute'}) " +
                            "MERGE (:MetaNode {name: 'NodeOperation', label: 'Operation'}) " +
                            "MERGE (:MetaNode {name: 'NodeParam', label: 'Param'}) " +
                            "MERGE (:MetaNode {name: 'NodeAssociationClass', label: 'AssociationClass'}) " +
                            "MERGE (:MetaNode {name: 'NodeAbstractClass', label: 'AbstractClass'}) " +
                            "MERGE (:MetaNode {name: 'NodeConcreteClass', label: 'ConcreteClass'}) " +
                            "MERGE (:MetaNode {name: 'NodeEnumeration', label: 'Enumeration'})" +
                            "MERGE (:MetaNode {name: 'NodeClassInvariant', label: 'ClassInvariant'}) " +
                            "MERGE (:MetaNode {name: 'NodePreCondition', label: 'PreCondition'}) " +
                            "MERGE (:MetaNode {name: 'NodePostCondition', label: 'PostCondition'})";

            session.run(script);
        }
    }

    /**
     * Tạo một Node thực thể (M1) và nối InstanceOf tới MetaNode (M2)
     * @param metaNodeName Tên của node metamodel (ví dụ: NodeConcreteClass)
     * @param instanceLabel Nhãn của thực thể (ví dụ: Person)
     * @param params Các thuộc tính của thực thể
     */
    public void createInstanceNode(String metaNodeName, String instanceLabel, Map<String, Object> params) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {

            // Lấy giá trị 'name' từ map để làm khóa cho MERGE
            Object idValue = params.get("name");
            if (idValue == null) {
                throw new RuntimeException("Property 'name' is missing for " + instanceLabel);
            }

            String cypher = String.format(
                    "MATCH (meta:MetaNode {name: $metaName}) " +
                            "MERGE (inst:%s {name: $id}) " +  // Dùng $id thay vì $name nếu cần rõ ràng
                            "SET inst += $props " +
                            "MERGE (inst)-[:InstanceOf]->(meta)", instanceLabel);

            session.run(cypher, Values.parameters(
                    "metaName", metaNodeName,
                    "id", idValue,
                    "props", params
            ));
        }
    }
    public void setPrimitiveAttribute(String objName, String clsName, String attrName, Object value) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            String attrId = clsName + "_" + attrName;
            String cypher =
                    "MATCH (obj {name: $objName}), (attr:Attribute {name: $attrId}) " +
                            "MERGE (obj)-[r:HAS_VALUE]->(attr) " +
                            "SET r.value = $val"; // $val có thể là String hoặc List<String>

            session.run(cypher, Values.parameters("objName", objName, "attrId", attrId, "val", value));
        }
    }

    public void setObjectReferenceAttribute(String srcObjName, String attrName, Object targetValue) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            // Xóa các tham chiếu cũ của attribute này để gán mới (Overwrite logic)
            session.run("MATCH (s {name: $sName})-[r:REFERENCES {role: $role}]->() DELETE r",
                    Values.parameters("sName", srcObjName, "role", attrName));

            if (targetValue instanceof List) {
                // Trường hợp Collection Object Reference
                List<String> targetNames = (List<String>) targetValue;
                String cypher =
                        "MATCH (s {name: $sName}), (t) WHERE t.name IN $tNames " +
                                "MERGE (s)-[:REFERENCES {role: $role}]->(t)";
                session.run(cypher, Values.parameters("sName", srcObjName, "role", attrName, "tNames", targetNames));
            } else if (targetValue != null) {
                // Trường hợp Single Object Reference
                String cypher =
                        "MATCH (s {name: $sName}), (t {name: $tName}) " +
                                "MERGE (s)-[:REFERENCES {role: $role}]->(t)";
                session.run(cypher, Values.parameters("sName", srcObjName, "role", attrName, "tName", targetValue.toString()));
            }
        }
    }

    public void upsertObject(String objectName, String className) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            // MATCH lớp (M1) và tạo thực thể Object (M0) nối InstanceOf
            // Sử dụng dấu nháy ngược cho Label động (tên Class)
            String cypher = String.format(
                    "MATCH (meta:MetaNode {name: 'NodeConcreteClass'}) " +
                            "MATCH (cls:ConcreteClass {name: $clsName})-[:InstanceOf]->(meta) " +
                            "MERGE (obj:`%s` {name: $objName}) " +
                            "MERGE (obj)-[:InstanceOf]->(cls)", className);

            session.run(cypher, org.neo4j.driver.Values.parameters("clsName", className, "objName", objectName));
        }
    }

    public long getRemoteObjectTimestamp() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            // Lấy timestamp từ node ModelVersion (dùng chung node này để quản lý version)
            org.neo4j.driver.Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.objectTimestamp AS ts");
            if (res.hasNext()) {
                org.neo4j.driver.Value val = res.single().get("ts");
                return val.isNull() ? 0L : val.asLong();
            }
        } catch (Exception e) {
            return -1L;
        }
        return 0L;
    }

    // Đặc tả 18: Edge HasAssociationClass
    public void createAssociationClassStructure(MAssociationClass ac) {
        MAssociationEnd sourceEnd = ac.associationEnds().get(0);
        MAssociationEnd targetEnd = ac.associationEnds().get(1);

        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            // TRUY VẤN THÔNG QUA TẦNG METAMODEL (InstanceOf)
            String cypher =
                    // 1. Tìm Node thực thể và MetaNode tương ứng của nó
                    "MATCH (a {name: $srcName})-[:InstanceOf]->(metaA:MetaNode) " +
                            "MATCH (b {name: $tgtName})-[:InstanceOf]->(metaB:MetaNode) " +
                            "MATCH (ac {name: $acName})-[:InstanceOf]->(metaAC:MetaNode) " +

                            // 2. Kiểm tra logic: a và b phải là Class, ac phải là AssociationClass
                            "WHERE metaA.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                            "AND metaB.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                            "AND metaAC.name = 'NodeAssociationClass' " +

                            // 3. MERGE các quan hệ M1
                            "MERGE (a)-[s:sourceAssoClass]->(ac) " +
                            "SET s.role = $sRole, s.multiplicity = $sMult, s.index = 0 " +

                            "MERGE (ac)-[t:targetAssoClass]->(b) " +
                            "SET t.role = $tRole, t.multiplicity = $tMult, t.index = 1 " +

                            // 4. Cạnh HasAssociationClass (Class nguồn -> AC)
                            "MERGE (a)-[:HasAssociationClass]->(ac) " +
                            "MERGE (b)-[:HasAssociationClass]->(ac) ";

            session.run(cypher, Values.parameters(
                    "srcName", sourceEnd.cls().name(),
                    "tgtName", targetEnd.cls().name(),
                    "acName", ac.name(),
                    "sRole", sourceEnd.name(),
                    "sMult", sourceEnd.multiplicity().toString(),
                    "tRole", targetEnd.name(),
                    "tMult", targetEnd.multiplicity().toString()
            ));
        }
    }

    /**
     * Tạo Node thực thể Enumeration và nối InstanceOf tới MetaNode
     */
    public void upsertEnumeration(String enumName, List<String> literals) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            // Sử dụng nhãn động là tên của Enum (ví dụ :Gender)
            // Dùng nháy ngược `` để an toàn với tên có ký tự đặc biệt
            String cypher = String.format(
                    "MATCH (meta:MetaNode {name: 'NodeEnumeration'}) " +
                            "MERGE (e:`%s` {name: $name}) " +
                            "SET e.value = $values " +
                            "MERGE (e)-[:InstanceOf]->(meta)", enumName);

            session.run(cypher, org.neo4j.driver.Values.parameters(
                    "name", enumName,
                    "values", literals
            ));
        }
    }
}