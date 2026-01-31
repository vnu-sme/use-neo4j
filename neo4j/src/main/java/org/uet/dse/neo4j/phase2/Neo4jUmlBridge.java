package org.uet.dse.neo4j.phase2;

import org.tzi.use.api.UseModelApi;
import org.tzi.use.graph.DirectedGraph;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystemState;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;
import org.uet.dse.neo4j.repo.Neo4jSideRepository;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;
import org.uet.dse.neo4j.sync.UmlTypeTranslator;

import java.util.*;

public class Neo4jUmlBridge {

    private final UseModelApi modelApi;
    private final WorkLogManager logger = WorkLogManager.getInstance();

    private final Neo4jModelRepository repo;

    public Neo4jUmlBridge(UseModelApi modelApi) {
        this.modelApi = modelApi;
        this.repo = new Neo4jModelRepository();
        // Khởi tạo metamodel trước khi push
        this.repo.initializeMetamodel();
    }

    /**
     * CHIỀU 1: PUSH (JAVA MODEL -> NEO4J GRAPH)
     * Đồng bộ cấu trúc model từ USE lên Graph Database
     */
    public void pushModelToNeo4j() {
        MModel model = modelApi.getModel();

        String currentUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        logger.log("PUSH_START", "User " + currentUser + " initiated sync for model: " + model.name());

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {

            //assure existence of meta-nodes
            repo.initializeMetamodel();

            //enums
            for (org.tzi.use.uml.ocl.type.EnumType e : model.enumTypes()) {
                repo.upsertEnumeration(e.name(), e.getLiterals());
                logger.log("PUSH_ENUM", "Pushed enumeration: " + e.name());
            }

            // Concrete, Abstract, AssociationClass
            // include InstanceOf egde connection
            for (MClass cls : model.classes()) {
                createClassNode(session, cls);
            }

            //extends
            DirectedGraph<MClassifier, MGeneralization> genGraph = model.generalizationGraph();
            Iterator<MGeneralization> edgeIterator = genGraph.edgeIterator();
            while (edgeIterator.hasNext()) {
                MGeneralization gen = edgeIterator.next();
                createExtendsEdge(session, gen.child(), gen.parent());
            }

            //Attributes, Operations, Params
            //include InstanceOf to NodeAttribute/NodeOperation
            for (MClass cls : model.classes()) {
                mapAttributes(session, cls);
                mapOperations(session, cls);
                mapInvariants(session, cls);
                mapOperationConstraintsForAllOperationsInClass(session, cls);
            }

            //Associations (Binary & AssociationClass Link)
            for (MAssociation assoc : model.associations()) {
                if (assoc.associationEnds().size() > 2) {
                    // Xử lý quan hệ N-bên (vd: Filmstrip)
                    repo.upsertTernaryAssociation(assoc);
                }
                else if (assoc instanceof MAssociationClass) {
                    //Star Schema (A -> AC -> B)
                    //sourceAssoClass, targetAssoClass, HasAssociationClass
                    repo.createAssociationClassStructure((MAssociationClass) assoc);
                } else {
                    // normal association association, composition, aggregation
                    repo.createAssociationEdge(assoc);
                }
            }

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
        org.tzi.use.uml.mm.MModel model = modelApi.getModel();

        String modelName = model.name();

        long modelHash = ObjectSyncCoordinator.calculateModelHash(model);
        long ts = System.currentTimeMillis();

        // ModelHash này sẽ được SyncObjectCoordinator dùng để so sánh trước khi Push Object
        String cypher = "MERGE (v:ModelVersion {id: 'CURRENT'}) " +
                "SET v.timestamp = timestamp(), v.user = $user, " +
                "    v.modelHash = $hash, v.modelName = $name";
        session.run(cypher, Values.parameters("user", user, "hash", modelHash, "name", modelName));

        // 5. Cập nhật tracker local để máy mình không tự báo "Database Updated"
        org.uet.dse.neo4j.sync.ChangeTracker.updateLocalTimestamp(ts);

        logger.log("VERSION_UPDATE", "Model version updated. Hash: " + modelHash);
    }

    private void createAssociationClassNode(Session session, MAssociationClass ac) {
        String cypher = "MERGE (n:AssociationClass {name: $name})";
        session.run(cypher, Values.parameters("name", ac.name()));
    }

    private void mapOperations(Session session, MClass cls) {
        for (MOperation op : cls.operations()) {
            // only resolve operation in this. class to avoid inheritance
            if (op.cls().equals(cls)) {
                Map<String, Object> rInfo = parseType(op.resultType());
                String opId = cls.name() + "_" + op.name();

                Map<String, Object> props = new HashMap<>();
                props.put("name", opId);
                props.put("opName", op.name());
                props.put("returnType", rInfo.get("type"));
                props.put("isReturnCollection", rInfo.get("isCollection"));
                props.put("isAbstract", cls.isAbstract());

                // --- BỔ SUNG: TRÍCH XUẤT BODY ---
                String body = null;
                boolean isQuery = true;

                if (op.hasExpression()) {
                    // Nếu là OCL Query Operation
                    body = op.expression().toString();
                    isQuery = true;
                } else if (op.hasStatement()) {
                    // Nếu là SOIL Imperative Operation
                    body = op.getStatement().toString();
                    isQuery = false;
                }

                props.put("body", body);
                props.put("isQuery", isQuery);


                //create node operation
                repo.createInstanceNode("NodeOperation", "Operation", props);
                repo.createStructuralEdge(cls.name(), opId, "HasOperation");

                //referenceReturnType
                TypeMapper.handleTypeReference(repo, opId, op.resultType(), true);

                //parameters
                int order = 1;
                for (org.tzi.use.uml.ocl.expr.VarDecl param : op.paramList()) {
                    Map<String, Object> pInfo = parseType(param.type());
                    //unique id for each param
                    String paramId = opId + "_p_" + param.name();

                    Map<String, Object> pProps = new HashMap<>();
                    pProps.put("name", paramId);
                    pProps.put("pName", param.name());
                    pProps.put("type", pInfo.get("type"));
                    pProps.put("pOrder", order++);
                    pProps.put("isCollection", pInfo.get("isCollection"));
                    pProps.put("collectionType", pInfo.get("collType"));

                    //node param creation
                    repo.createInstanceNode("NodeParam", "Param", pProps);
                    repo.createStructuralEdge(opId, paramId, "HasParam");

                    //referenceType for param
                    TypeMapper.handleTypeReference(repo, paramId, param.type(), false);
                }

                //* Gọi nạp ràng buộc OCL ngay sau khi Op và Params đã lên DB
                mapOperationConstraintsForOneOperation(session, op, opId);
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
            else if (type.isKindOfBag(VoidHandling.EXCLUDE_VOID)) map.put("collType", "Bag");
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
        int index = 0;
        for (MAttribute attr : cls.attributes()) {
            Type fullType = attr.type();
            String instanceId = cls.name() + "_" + attr.name();

            // 1. Phân tích kiểu dữ liệu
            Type ultimateBaseType = UmlTypeTranslator.getUltimateBaseType(fullType);
            int depth = UmlTypeTranslator.getCollectionDepth(fullType);
            boolean isCollection = depth > 0;
            boolean isNested = depth > 1;

            // 2. Chuẩn bị thuộc tính cho Node Attribute gốc
            Map<String, Object> props = new HashMap<>();
            props.put("name", instanceId);
            props.put("attrName", attr.name());
            props.put("index", index++);

            // ĐẶC TẢ MỚI: isNestedCollection
            props.put("isNestedCollection", isNested);
            props.put("isCollection", isCollection);

            // Luôn lưu kiểu cơ sở cuối cùng (Vd: Int, Person) để cạnh ReferenceType hoạt động đúng
            props.put("type", UmlTypeTranslator.toDatabaseType(ultimateBaseType));

            // Lưu loại tập hợp lớp ngoài cùng (Vd: Set)
            props.put("collectionType", isCollection ? UmlTypeTranslator.getCollectionType(fullType) : "None");
            props.put("isDerived", attr.isDerived());

            // 3. Tạo Node thực thể Attribute (M1)
            repo.createInstanceNode("NodeAttribute", "Attribute", props);
            repo.createStructuralEdge(cls.name(), instanceId, "HasAttribute");

            // 4. Nối cạnh ReferenceType từ Node gốc tới Class/Enum đích (Nếu có)
            // Việc dùng ultimateBaseType đảm bảo lấy đúng kiểu dữ liệu trong Nested
            TypeMapper.handleTypeReference(repo, instanceId, ultimateBaseType, false);

            // 5. Nếu là Nested Collection -> Tạo các Node con trung gian
            if (isNested) {
                // Bắt đầu đệ quy từ lớp vỏ thứ 2
                pushNestedCollectionNodes(session, instanceId, ((CollectionType) fullType).elemType());
            }
        }
    }

    /**
     * Hàm đệ quy tạo chuỗi Node NestedCollection
     */
    private void pushNestedCollectionNodes(Session session, String parentId, Type currentType) {
        // Chỉ xử lý nếu lớp hiện tại vẫn là một Collection
        if (!currentType.isKindOfCollection(VoidHandling.EXCLUDE_VOID)) return;

        // Tạo ID duy nhất cho Node Nested
        String nestedId = "nested_" + UUID.randomUUID().toString();

        // Kiểm tra xem lớp tiếp theo bên trong còn là Collection nữa không
        Type innerType = ((CollectionType) currentType).elemType();
        boolean stillNested = innerType.isKindOfCollection(VoidHandling.EXCLUDE_VOID);

        Map<String, Object> props = new HashMap<>();
        props.put("name", nestedId);
        props.put("collectionName", UmlTypeTranslator.getCollectionType(currentType));
        props.put("isNestedCollection", stillNested);

        // Tạo Node thực thể Nested và nối cạnh HasNestedCollection
        repo.createInstanceNode("NodeNestedCollection", "NestedCollection", props);
        repo.createStructuralEdge(parentId, nestedId, "HasNestedCollection");

        // Nếu vẫn còn lớp bên trong -> Tiếp tục đệ quy
        if (stillNested) {
            pushNestedCollectionNodes(session, nestedId, innerType);
        }
    }

    private void pushNestedLevels(Session session, String parentId, org.tzi.use.uml.ocl.type.Type currentType) {
        if (!currentType.isKindOfCollection(VoidHandling.EXCLUDE_VOID)) return;

        String nestedId = "nested_" + UUID.randomUUID().toString();
        boolean hasMore = ((org.tzi.use.uml.ocl.type.CollectionType)currentType).elemType().isKindOfCollection(VoidHandling.EXCLUDE_VOID);

        Map<String, Object> props = new HashMap<>();
        props.put("name", nestedId);
        props.put("collectionName", UmlTypeTranslator.getCollectionType(currentType));
        props.put("isNestedCollection", hasMore);

        repo.createInstanceNode("NodeNestedCollection", "NestedCollection", props);
        repo.createStructuralEdge(parentId, nestedId, "HasNestedCollection");

        if (hasMore) {
            pushNestedLevels(session, nestedId, ((org.tzi.use.uml.ocl.type.CollectionType)currentType).elemType());
        }
    }


    /**
     * Ánh xạ Class Invariants (Đặc tả 1 & 6)
     */
    private void mapInvariants(Session session, MClass cls) {
        int index = 0;
        for (MClassInvariant inv : modelApi.getModel().classInvariants(cls)) {
            String displayName = (inv.name() == null || inv.name().isEmpty()) ? "unnamed" : inv.name();

            //non null
            String invInstanceId = cls.name() + "_inv_" + index;

            Map<String, Object> props = new HashMap<>();
            props.put("name", invInstanceId);         // unique key when merge
            props.put("invName", displayName);        // Tên thực tế
            props.put("expression", inv.bodyExpression().toString());
            props.put("isExistential", inv.isExistential());
            props.put("index", index);

            //node creation
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
    private void mapOperationConstraintsForOneOperation(Session session, MOperation op, String opId) {
        //pre
        int preIdx = 0;
        for (MPrePostCondition pre : op.preConditions()) {
            String preId = opId + "_pre_" + preIdx;
            Map<String, Object> props = new HashMap<>();
            props.put("name", preId);      //unique key for merge (M0)
            props.put("condName", pre.name());     //display name
            props.put("expression", pre.expression().toString());  //plain ocl
            props.put("index", preIdx++);
            repo.createInstanceNode("NodePreCondition", "PreCondition", props);
            repo.createStructuralEdge(opId, preId, "HasPreCondition");
        }

        //post
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
    private void mapOperationConstraintsForAllOperationsInClass(Session session, MClass cls) {
        for (MOperation op : cls.operations()) {
            if (op.cls().equals(cls)) {
                //must: id of operation node == id created in mapOperations()
                String opId = cls.name() + "_" + op.name();
                mapOperationConstraintsForOneOperation(session, op, opId);
            }
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
            ObjectSyncCoordinator.updateLocalSyncTimestamp(ts);
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

}
