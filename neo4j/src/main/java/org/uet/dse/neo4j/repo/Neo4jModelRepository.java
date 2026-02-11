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
import org.uet.dse.neo4j.mm.core.node.AbstractMetaNode;
import org.uet.dse.neo4j.mm.core.node.NodeEnumeration;
import org.uet.dse.neo4j.query.builder.CypherQueryBuilder;
import org.uet.dse.neo4j.query.builder.MetaNodeCypherBuilder;
import org.uet.dse.neo4j.query.model.MetaNodeDescriptor;
import org.uet.dse.neo4j.query.model.MetaNodeRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Neo4jModelRepository {
    private final Driver driver;

    public Neo4jModelRepository() {
        // Lấy driver từ Manager đã connect thành công
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    public void createAssociationEdge(MAssociation assoc) {
        if (assoc.associationEnds().size() < 2) return;

        // Lấy đúng thứ tự từ Model của USE
        MAssociationEnd end0 = assoc.associationEnds().get(0); // Vị trí 1 trong file .use
        MAssociationEnd end1 = assoc.associationEnds().get(1); // Vị trí 2 trong file .use

        // XÁC ĐỊNH NHÃN CẠNH (Label)
        // Ưu tiên nhãn theo đầu 1 (Target), nếu đầu 1 là None thì xét đầu 0
        String edgeLabel = "AssociateWith";
        int kind0 = end0.aggregationKind();
        int kind1 = end1.aggregationKind();

        if (kind1 == 2 || kind0 == 2) edgeLabel = "ComposeOf";
        else if (kind1 == 1 || kind0 == 1) edgeLabel = "Aggregates";

        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {

            // 1. Dọn dẹp an toàn: Xóa mọi cạnh cũ của Association này
            session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                    Values.parameters("name", assoc.name()));

            // 2. Tạo cạnh LUÔN ĐI TỪ END 0 SANG END 1 (Bảo toàn thứ tự tuyệt đối)
            String cypher = String.format(
                    "MATCH (s {name: $sName}), (t {name: $tName}) " +
                            "MERGE (s)-[r:%s {associationName: $assocName}]->(t) " +
                            "SET r.sourceClassName = $sName, " +
                            "    r.sourceClassrole = $sRole, " +
                            "    r.sourceMultiplicity = $sMult, " +
                            "    r.sourceKind = $sKind, " +       // LƯU KIND ĐẦU 0
                            "    r.targetClassName = $tName, " +
                            "    r.targerClassrole = $tRole, " +
                            "    r.targetMultiplicity = $tMult, " +
                            "    r.targetKind = $tKind, " +       // LƯU KIND ĐẦU 1
                            "    r.isTernary = false",            // Đánh dấu để hàm Pull Ternary bỏ qua
                    edgeLabel);

            session.run(cypher, Values.parameters(
                    "assocName", assoc.name(),
                    "sName", end0.cls().name(),
                    "sRole", end0.name(),
                    "sMult", end0.multiplicity().toString(),
                    "sKind", kind0,
                    "tName", end1.cls().name(),
                    "tRole", end1.name(),
                    "tMult", end1.multiplicity().toString(),
                    "tKind", kind1
            ));
        }
    }

    public void createStructuralEdge(String fromNodeName, String toNodeName, String edgeLabel) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String cypher = CypherQueryBuilder.buildStructuralEdgeQuery(edgeLabel);
            session.run(cypher, Values.parameters(
                    "from", fromNodeName,
                    "to", toNodeName
            ));
        }
    }

    /**
     * run script for meta node creation
     */
    public void initializeMetamodel() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        List<MetaNodeDescriptor> descriptors =
                MetaNodeRegistry.ALL.stream()
                        .map(MetaNodeDescriptor::from)
                        .toList();

        String script = MetaNodeCypherBuilder.buildMerge(descriptors);

        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            session.run(script);
        }
    }


//    /**
//     * Tạo một Node thực thể (M1) và nối InstanceOf tới MetaNode (M2)
//     * @param metaNodeName Tên của node metamodel (ví dụ: NodeConcreteClass)
//     * @param instanceLabel Nhãn của thực thể (ví dụ: Person)
//     * @param params Các thuộc tính của thực thể
//     */
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

    /**
     * refactored
     * @param node
     * @param dynamicLabel
     */
    public void createInstanceNode(AbstractMetaNode node, String dynamicLabel) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String cypher = CypherQueryBuilder.buildInstanceCreationQuery(dynamicLabel);

            Object idValue = node.toPropertyMap().get("name");
            if (idValue == null) {
                throw new RuntimeException("Property 'name' is missing for " + dynamicLabel);
            }

            session.run(cypher, Values.parameters(
                    "metaName", node.getMetaName(),
                    "id", node.getName(),
                    "props", node.toPropertyMap()
            ));
        }
    }

    public long getRemoteObjectTimestamp() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
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

    public void upsertEnumeration(String enumName, List<String> literals) {
        NodeEnumeration nodeEnum = new NodeEnumeration();
        nodeEnum.setName(enumName);
        nodeEnum.setValues(literals);

        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            String cypher = CypherQueryBuilder.buildUpsertEnumerationQuery(nodeEnum.getName());

            session.run(cypher, org.neo4j.driver.Values.parameters(
                    "name", nodeEnum.getName(),
                    "values", nodeEnum.getValues()
            ));
        }
    }

    /**
     * Tạo cấu trúc quan hệ N-bên (Ternary) sử dụng Node Hub và Spoke Edges
     */
    public void upsertTernaryAssociation(MAssociation assoc) {
        String assocName = assoc.name();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            // 1. Tạo Node Hub (Vẫn như cũ)
            String createHub = String.format(
                    "MATCH (meta:MetaNode {name: 'NodeTernaryAssociation'}) " +
                            "MERGE (h:`%s` {name: $name}) " +
                            "MERGE (h)-[:InstanceOf]->(meta)", assocName);
            session.run(createHub, Values.parameters("name", assocName));

            // 2. Nối các đầu mút (Spokes)
            List<MAssociationEnd> ends = assoc.associationEnds();
            for (int i = 0; i < ends.size(); i++) {
                MAssociationEnd end = ends.get(i);
                String edgeLabel = (end.aggregationKind() == 2) ? "ComposeOf" :
                        (end.aggregationKind() == 1 ? "Aggregates" : "AssociateWith");

                String cypher = String.format(
                        "MATCH (c {name: $clsName}), (hub:`%s` {name: $name}) " +
                                "MERGE (c)-[r:%s {index: $idx}]->(hub) " +
                                "SET r.associationName = $name, " +
                                "    r.isTernary = true, " +
                                "    r.sourceClassrole = $role, " +
                                "    r.sourceMultiplicity = $mult", assocName, edgeLabel);

                session.run(cypher, Values.parameters(
                        "clsName", end.cls().name(),
                        "name", assocName,
                        "idx", i,
                        "role", end.name(),
                        "mult", end.multiplicity().toString()
                ));
            }
        }
    }
}