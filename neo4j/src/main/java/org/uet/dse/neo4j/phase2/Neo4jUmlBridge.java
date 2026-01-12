package org.uet.dse.neo4j.phase2;

import org.tzi.use.api.UseModelApi;
import org.tzi.use.graph.DirectedGraph;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.EnumType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystemState;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.repo.Neo4jRepository;
import org.uet.dse.neo4j.sync.ChangeTracker;
import org.uet.dse.neo4j.sync.SyncObjectCoordinator;
import org.uet.dse.neo4j.sync.UmlTypeTranslator;

import java.util.*;

public class Neo4jUmlBridge {

    private final UseModelApi modelApi;
    private final WorkLogManager logger = WorkLogManager.getInstance();

    private final Neo4jRepository repo;

    public Neo4jUmlBridge(UseModelApi modelApi) {
        this.modelApi = modelApi;
        this.repo = new Neo4jRepository();
        // Khởi tạo metamodel trước khi push
        this.repo.initializeMetamodel();
    }

    /**
     * CHIỀU 1: PUSH (JAVA MODEL -> NEO4J GRAPH)
     * Đồng bộ cấu trúc model từ USE lên Graph Database
     */
    public void pushModelToNeo4j() {
        MModel model = modelApi.getModel();

        // 1. Lấy thông tin người dùng đầy đủ cho Versioning và Log
        String currentUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        logger.log("PUSH_START", "User " + currentUser + " initiated sync for model: " + model.name());

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {

            // 2. KHỞI TẠO METAMODEL (M2 Layer)
            // Đảm bảo các node NodeConcreteClass, NodeAttribute... luôn tồn tại
            repo.initializeMetamodel();

            // 3. Nodes: Enumerations
            for (EnumType e : model.enumTypes()) {
                createEnumNode(session, e);
            }

            // 4. Nodes: Classes (Concrete, Abstract, AssociationClass)
            // Mỗi Class tạo ra sẽ nối InstanceOf tới MetaNode tương ứng
            for (MClass cls : model.classes()) {
                if (cls instanceof MAssociationClass) {
                    createAssociationClassNode(session, (MAssociationClass) cls);
                } else {
                    createClassNode(session, cls);
                }
            }

            // 5. Edges: Extends (Kế thừa)
            DirectedGraph<MClassifier, MGeneralization> genGraph = model.generalizationGraph();
            Iterator<MGeneralization> edgeIterator = genGraph.edgeIterator();
            while (edgeIterator.hasNext()) {
                MGeneralization gen = edgeIterator.next();
                createExtendsEdge(session, gen.child(), gen.parent());
            }

            // 6. Nodes & Edges: Attributes, Operations, Params
            // Mỗi Attr/Op tạo ra sẽ nối InstanceOf tới NodeAttribute/NodeOperation
            for (MClass cls : model.classes()) {
                mapAttributes(session, cls);
                mapOperations(session, cls);

                // --- THÊM 2 DÒNG NÀY ĐỂ PUSH RÀNG BUỘC ---
                mapInvariants(session, cls); // Đẩy Invariant
                mapOperationConstraints(session, cls); // Đẩy Pre/Post
            }

            // 7. Edges: Associations (Binary & AssociationClass Link)
            for (MAssociation assoc : model.associations()) {
                if (!(assoc instanceof MAssociationClass)) {

                    // GỌI HÀM CỦA REPOSITORY TẠI ĐÂY:
                    // Hàm này đã được sửa để lưu cả sourceMultiplicity và targetMultiplicity
                    repo.createAssociationEdge(assoc);

                } else {
                    // Đặc tả 18: Nối AssociationClass tới các Class tham gia
                    createAssociationClassLink(session, (MAssociationClass) assoc);
                }
            }

            // 8. CẬP NHẬT VERSION (Trình theo dõi thay đổi)
            // Đánh dấu Database vừa được thay đổi bởi currentUser vào lúc này
            updateModelVersion(session, currentUser);

            logger.log("PUSH_SUCCESS", "Model " + model.name() + " is now synchronized.");

        } catch (Exception e) {
            logger.log("PUSH_ERROR", "Failed to push model: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Cập nhật Node phiên bản để ChangeTracker của những người dùng khác có thể nhận biết
     */


    public void updateModelVersion(org.neo4j.driver.Session session, String user) {
        // 1. Lấy Model hiện tại từ modelApi
        org.tzi.use.uml.mm.MModel model = modelApi.getModel();
        String modelName = model.name(); // Lấy tên thực tế (ví dụ: "EmployeeSystem")

        // 2. Tính toán dấu vân tay (Hash) của Model
        // Hàm này bạn đã định nghĩa ở phần trước để quét qua toàn bộ Class/Attr/Op
        long modelHash = SyncObjectCoordinator.calculateModelHash(model);

        // 3. Lấy thời gian hiện tại
        long ts = System.currentTimeMillis();

        // 4. Cypher: Lưu đồng thời User, Timestamp và ModelHash
        // ModelHash này sẽ được SyncObjectCoordinator dùng để so sánh trước khi Push Object
        String cypher = "MERGE (v:ModelVersion {id: 'CURRENT'}) " +
                "SET v.timestamp = timestamp(), v.user = $user, " +
                "    v.modelHash = $hash, v.modelName = $name";

        session.run(cypher, Values.parameters("user", user, "hash", modelHash, "name", modelName));

        // 5. Cập nhật tracker local để máy mình không tự báo "Database Updated"
        org.uet.dse.neo4j.sync.ChangeTracker.updateLocalTimestamp(ts);

        logger.log("VERSION_UPDATE", "Model version updated. Hash: " + modelHash);
    }

    private void createEnumNode(Session session, EnumType e) {
        String cypher = "MERGE (n:Enumeration {name: $name}) SET n.value = $values";
        session.run(cypher, Values.parameters("name", e.name(), "values", e.getLiterals()));
    }


    private void createAssociationClassNode(Session session, MAssociationClass ac) {
        String cypher = "MERGE (n:AssociationClass {name: $name})";
        session.run(cypher, Values.parameters("name", ac.name()));

        // AssociationClass cũng là Class nên cần map Attr/Op (được gọi ở vòng lặp classes())
    }


    private void mapOperations(Session session, MClass cls) {
        for (MOperation op : cls.operations()) {
            // Chỉ xử lý các operation được khai báo trực tiếp tại Class này (tránh kế thừa)
            if (op.cls().equals(cls)) {
                Map<String, Object> rInfo = parseType(op.resultType());
                String opId = cls.name() + "_" + op.name();

                Map<String, Object> props = new HashMap<>();
                props.put("name", opId);
                props.put("opName", op.name());
                props.put("returnType", rInfo.get("type"));
                props.put("isReturnCollection", rInfo.get("isCollection"));
                props.put("isAbstract", cls.isAbstract());

                // 1. Tạo Node Operation và nối với Class
                repo.createInstanceNode("NodeOperation", "Operation", props);
                repo.createStructuralEdge(cls.name(), opId, "HasOperation");

                // 2. Xử lý cạnh ReferenceReturnType (Đặc tả 17)
                TypeMapper.handleTypeReference(repo, opId, op.resultType(), true);

                // 3. XỬ LÝ PARAMETERS
                int order = 1;
                for (org.tzi.use.uml.ocl.expr.VarDecl param : op.paramList()) {
                    Map<String, Object> pInfo = parseType(param.type());
                    // ID duy nhất cho tham số
                    String paramId = opId + "_p_" + param.name();

                    Map<String, Object> pProps = new HashMap<>();
                    pProps.put("name", paramId);
                    pProps.put("pName", param.name()); // DÙNG 'pName' ĐỂ KHỚP VỚI LOGIC PULL
                    pProps.put("type", pInfo.get("type"));
                    pProps.put("pOrder", order++);     // DÙNG 'pOrder' ĐỂ TRÁNH LỖI ÉP KIỂU
                    pProps.put("isCollection", pInfo.get("isCollection"));
                    pProps.put("collectionType", pInfo.get("collType"));

                    // Tạo Node Param và nối với Operation (Đặc tả 15)
                    repo.createInstanceNode("NodeParam", "Param", pProps);
                    repo.createStructuralEdge(opId, paramId, "HasParam");

                    // Xử lý cạnh ReferenceType cho Parameter (Đặc tả 16)
                    TypeMapper.handleTypeReference(repo, paramId, param.type(), false);
                }

                // 4. QUAN TRỌNG: Gọi nạp ràng buộc OCL ngay sau khi Op và Params đã lên DB
                mapOperationConstraints(session, op, opId);
            }
        }
    }


    /**
     * Helper: Chuyển đổi Type của USE sang Schema Neo4j
     * Sử dụng các phương thức isKindOf... từ interface Type
     */
    /**
     * Helper: Chuyển đổi Type của USE sang Schema Neo4j
     */
    public Map<String, Object> parseType(Type type) {
        Map<String, Object> map = new HashMap<>();
        if (type == null) {
            map.put("type", "Void");
            map.put("isCollection", false);
            map.put("collType", "None");
            return map;
        }

        boolean isColl = type.isKindOfCollection(VoidHandling.EXCLUDE_VOID);
        map.put("isCollection", isColl);

        Type baseType = type;
        if (isColl && type instanceof CollectionType) {
            baseType = ((CollectionType) type).elemType();
            if (type.isKindOfSet(VoidHandling.EXCLUDE_VOID)) map.put("collType", "Set");
            else map.put("collType", "List");
        } else {
            map.put("collType", "None");
        }

        if (baseType.isTypeOfInteger()) map.put("type", "Int");
        else if (baseType.isTypeOfString()) map.put("type", "String");
        else if (baseType.isTypeOfReal()) map.put("type", "Double");
        else if (baseType.isTypeOfBoolean()) map.put("type", "Boolean");
        else if (baseType.isKindOfEnum(VoidHandling.EXCLUDE_VOID) || baseType.isKindOfClass(VoidHandling.EXCLUDE_VOID)) {
            map.put("type", "Object");
            map.put("ref", baseType.shortName());
        } else {
            map.put("type", "Object");
            map.put("ref", baseType.shortName());
        }
        return map;
    }

    private void createAssociationClassLink(Session session, MAssociationClass ac) {
        // Đặc tả 18: Edge HasAssociationClass
        // Nối Association Class với các Class tham gia
        for (MAssociationEnd end : ac.associationEnds()) {
            String cypher = "MATCH (ac:AssociationClass {name: $acName}), (c {name: $clsName}) " +
                    "MERGE (ac)-[:HasAssociationClass]->(c)";
            session.run(cypher, Values.parameters("acName", ac.name(), "clsName", end.cls().name()));
        }
    }

    private void createExtendsEdge(Session session, MClassifier child, MClassifier parent) {
        // Tuân thủ Schema số 8 và 12
        String cypher = "MATCH (src {name: $srcName}), (tgt {name: $tgtName}) " +
                "MERGE (src)-[r:Extends]->(tgt) " +
                "SET r.sourceName = $srcName, r.targetName = $tgtName";
        session.run(cypher, Values.parameters("srcName", child.name(), "tgtName", parent.name()));
    }


    private void createClassNode(Session session, MClass cls) {
        Map<String, Object> props = new HashMap<>();
        props.put("name", cls.name());
        props.put("hasParent", !cls.parents().isEmpty());
        props.put("hasChildren", !cls.children().isEmpty());

        if (cls instanceof MAssociationClass) {
            // Ánh xạ tới MetaNode 'NodeAssociationClass'
            repo.createInstanceNode("NodeAssociationClass", cls.name(), props);
        } else if (cls.isAbstract()) {
            // Ánh xạ tới MetaNode 'NodeAbstractClass'
            repo.createInstanceNode("NodeAbstractClass", cls.name(), props);
        } else {
            // Ánh xạ tới MetaNode 'NodeConcreteClass'
            repo.createInstanceNode("NodeConcreteClass", cls.name(), props);
        }
    }

    private void mapAttributes(Session session, MClass cls) {
        // SỬA: Thay allAttributes() bằng attributes() để chỉ lấy thuộc tính khai báo tại Class này
        for (MAttribute attr : cls.attributes()) {
            // Hoặc dùng: if (attr.owner().equals(cls)) { ... }
            Map<String, Object> tInfo = parseType(attr.type());
            String instanceId = cls.name() + "_" + attr.name();

            Map<String, Object> props = new HashMap<>();
            props.put("name", instanceId);
            props.put("attrName", attr.name());

            // DÙNG TRANSLATOR: Real -> Double
            props.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
            props.put("isCollection", tInfo.get("isCollection"));
            props.put("collectionType", tInfo.get("collType"));
            props.put("isDerived", attr.isDerived());

            // createInstanceNode bên trong đã dùng MERGE theo 'name', nên sẽ không bị trùng
            repo.createInstanceNode("NodeAttribute", "Attribute", props);
            repo.createStructuralEdge(cls.name(), instanceId, "HasAttribute");
            TypeMapper.handleTypeReference(repo, instanceId, attr.type(), false);
        }
    }

    public void pushObjectState(MObject obj, MSystemState state) {
        String objName = obj.name();
        String clsName = obj.cls().name();

        // 1. Tạo node Object trên Neo4j
        repo.upsertObject(objName, clsName);

        // 2. Lấy trạng thái của Object trong System State hiện tại
        // Trong USE, MObjectState chứa bản đồ giá trị thuộc tính
        Map<MAttribute, Value> values = obj.state(state).attributeValueMap();

        for (Map.Entry<MAttribute, Value> entry : values.entrySet()) {
            MAttribute attr = entry.getKey();
            Value useValue = entry.getValue();
            Type attrType = attr.type();

            // Chuyển đổi giá trị sang Java Object
            Object mappedValue = ValueMapper.mapUseValue(useValue);
            if (mappedValue == null) continue;

            // 3. Kiểm tra kiểu dữ liệu dựa trên interface Type của USE
            // Kiểm tra xem kiểu cơ bản hoặc kiểu phần tử tập hợp có phải là Object (Class) không
            boolean isObjectRef = false;

            if (attrType.isKindOfClass(VoidHandling.EXCLUDE_VOID)) {
                // Trường hợp Single Object Reference
                isObjectRef = true;
            } else if (attrType.isKindOfCollection(VoidHandling.EXCLUDE_VOID)) {
                // Trường hợp Collection, cần kiểm tra kiểu phần tử bên trong
                if (attrType instanceof CollectionType) {
                    Type elemType = ((CollectionType) attrType).elemType();
                    if (elemType.isKindOfClass(VoidHandling.EXCLUDE_VOID)) {
                        isObjectRef = true;
                    }
                }
            }

            // 4. Đẩy dữ liệu xuống Repository tương ứng
            if (isObjectRef) {
                // Đặc tả: Tạo cạnh REFERENCES nối giữa các node Object
                repo.setObjectReferenceAttribute(objName, attr.name(), mappedValue);
            } else {
                // Đặc tả: Tạo cạnh HAS_VALUE chứa giá trị primitive
                repo.setPrimitiveAttribute(objName, clsName, attr.name(), mappedValue);
            }
        }
    }

    public static void updateObjectVersionOnServer() {
        long ts = System.currentTimeMillis();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            // Cập nhật timestamp riêng cho tầng Object
            String cypher = "MERGE (v:ModelVersion {id: 'CURRENT'}) SET v.objectTimestamp = $ts";
            session.run(cypher, org.neo4j.driver.Values.parameters("ts", ts));

            // Đồng bộ luôn local timestamp để Dashboard chuyển sang màu xanh
            SyncObjectCoordinator.updateLocalSyncTimestamp(ts);
        }
    }

    /**
     * CHỈ ĐẨY 1 CLASS LÊN DB (JAVA -> DB)
     */
    public void pushSingleClass(MClass mClass) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            // 1. Khởi tạo metamodel (luôn cần thiết)
            repo.initializeMetamodel();

            // 2. Tạo Node Class (InstanceOf MetaNode)
            if (mClass instanceof org.tzi.use.uml.mm.MAssociationClass) {
                createAssociationClassNode(session, (org.tzi.use.uml.mm.MAssociationClass) mClass);
            } else {
                createClassNode(session, mClass);
            }

            // 3. Map Attributes và Operations riêng cho Class này
            mapAttributes(session, mClass);
            mapOperations(session, mClass);

            logger.log("SELECTIVE_PUSH", "Pushed class: " + mClass.name());
        }
    }

    /**
     * CHỈ KÉO 1 CLASS TỪ DB VỀ JAVA (DB -> JAVA)
     */
    public void pullSingleClass(String className) throws Exception {
        // Lấy snapshot của duy nhất Class này từ Neo4j
        // Chúng ta sẽ tái sử dụng logic getModelSnapshotFromNeo4j nhưng thêm điều kiện WHERE name = className
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            String cypher = "MATCH (c {name: $name}) WHERE c:ConcreteClass OR c:AbstractClass " +
                    "RETURN c.name as name, labels(c)[0] as type";

            org.neo4j.driver.Result res = session.run(cypher, org.neo4j.driver.Values.parameters("name", className));
            if (res.hasNext()) {
                org.neo4j.driver.Record rec = res.next();
                boolean isAbs = rec.get("type").asString().equals("AbstractClass");

                // Tạo trong USE Model
                if (modelApi.getClass(className) == null) {
                    modelApi.createClass(className, isAbs);
                    logger.log("SELECTIVE_PULL", "Pulled class from DB: " + className);
                }
            }
        }
    }

    /**
     * Ánh xạ Class Invariants (Đặc tả 1 & 6)
     */
    private void mapInvariants(Session session, MClass cls) {
        int index = 0;
        for (MClassInvariant inv : modelApi.getModel().classInvariants(cls)) {
            // 1. Xử lý tên hiển thị: Nếu trống thì đặt là 'unnamed'
            String displayName = (inv.name() == null || inv.name().isEmpty()) ? "unnamed" : inv.name();

            // 2. Tạo ID duy nhất (Khóa chính name trên Neo4j): Class_inv_Index
            // ID này tuyệt đối không được null
            String invInstanceId = cls.name() + "_inv_" + index;

            Map<String, Object> props = new HashMap<>();
            props.put("name", invInstanceId);         // Khóa định danh cho MERGE
            props.put("invName", displayName);        // Tên thực tế
            props.put("expression", inv.bodyExpression().toString());
            props.put("isExistential", inv.isExistential());
            props.put("index", index);

            // Gọi repo để tạo node
            repo.createInstanceNode("NodeClassInvariant", "ClassInvariant", props);
            repo.createStructuralEdge(cls.name(), invInstanceId, "HasInvariant");

            index++;
        }
    }

    /**
     * Ánh xạ Pre/Post Conditions (Đặc tả 2, 3, 4, 5)
     */
    /**
     * Ánh xạ các ràng buộc Pre/Post Conditions của một Operation xuống Neo4j
     */
    private void mapOperationConstraints(Session session, MOperation op, String opId) {
        // A. Pre-conditions
        int preIdx = 0;
        for (MPrePostCondition pre : op.preConditions()) {
            String preId = opId + "_pre_" + preIdx;
            Map<String, Object> props = new HashMap<>();
            props.put("name", preId);
            props.put("condName", pre.name());
            props.put("expression", pre.expression().toString());
            props.put("index", preIdx++);
            repo.createInstanceNode("NodePreCondition", "PreCondition", props);
            repo.createStructuralEdge(opId, preId, "HasPreCondition");
        }
        ///
        int postIdx = 0;
        for (MPrePostCondition post : op.postConditions()) {
            String postId = opId + "_post_" + postIdx;
            Map<String, Object> props = new HashMap<>();
            props.put("name", postId);
            props.put("condName", post.name());
            props.put("expression", post.expression().toString());
            props.put("index", postIdx++);
            repo.createInstanceNode("NodePostCondition", "PostCondition", props);
            repo.createStructuralEdge(opId, postId, "HasPostCondition");
        }
    }

    /**
     * Ánh xạ toàn bộ Tiền/Hậu điều kiện của tất cả Operation trong một Class
     */
    private void mapOperationConstraints(Session session, MClass cls) {
        // Chỉ xử lý các operation được khai báo trực tiếp tại Class này (tránh dư thừa dữ liệu)
        for (MOperation op : cls.operations()) {
            if (op.cls().equals(cls)) {
                // ID của operation node (Phải khớp với ID đã tạo trong hàm mapOperations)
                String opId = cls.name() + "_" + op.name();

                // --- 1. Xử lý danh sách TIỀN ĐIỀU KIỆN (PreConditions) ---
                int preIdx = 0;
                for (MPrePostCondition pre : op.preConditions()) {
                    // Tạo ID duy nhất: ClassName_OpName_pre_Index
                    String preInstanceId = opId + "_pre_" + preIdx;

                    Map<String, Object> props = new HashMap<>();
                    props.put("name", preInstanceId);      // Khóa chính (M1 Instance ID)
                    props.put("condName", pre.name());    // Tên hiển thị (ví dụ: 'firePre')
                    props.put("expression", pre.expression().toString()); // Body OCL nguyên bản
                    props.put("index", preIdx);           // Thứ tự xuất hiện

                    // Tạo Node thực thể PreCondition nối InstanceOf -> NodePreCondition (M2)
                    repo.createInstanceNode("NodePreCondition", "PreCondition", props);

                    // Nối Operation tới PreCondition (Đặc tả cạnh 4)
                    repo.createStructuralEdge(opId, preInstanceId, "HasPreCondition");
                    preIdx++;
                }

                // --- 2. Xử lý danh sách HẬU ĐIỀU KIỆN (PostConditions) ---
                int postIdx = 0;
                for (MPrePostCondition post : op.postConditions()) {
                    // Tạo ID duy nhất: ClassName_OpName_post_Index
                    String postInstanceId = opId + "_post_" + postIdx;

                    Map<String, Object> props = new HashMap<>();
                    props.put("name", postInstanceId);
                    props.put("condName", post.name());
                    props.put("expression", post.expression().toString());
                    props.put("index", postIdx);

                    // Tạo Node thực thể PostCondition nối InstanceOf -> NodePostCondition (M2)
                    repo.createInstanceNode("NodePostCondition", "PostCondition", props);

                    // Nối Operation tới PostCondition (Đặc tả cạnh 5)
                    repo.createStructuralEdge(opId, postInstanceId, "HasPostCondition");
                    postIdx++;
                }
            }
        }
    }
}
