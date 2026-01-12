package org.uet.dse.neo4j.sync;

import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.tzi.use.api.UseModelApi;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.parser.Symtable;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.util.NullPrintWriter;
import org.uet.dse.neo4j.model.ClassState;
import org.uet.dse.neo4j.phase2.Neo4jUmlBridge;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.phase2.TypeMapper;
import org.uet.dse.neo4j.phase2.WorkLogManager;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

public class SyncCoordinator {
    private final Session useSession; // Thay vì chỉ giữ MModel, hãy giữ cả Session
    private final MModel useModel;

    private final MainWindow mainWindow;
    private final UseModelApi modelApi;
    private final Neo4jUmlBridge bridge;

    public SyncCoordinator(Session session, MainWindow mainWindow, MModel useModel, UseModelApi modelApi, Neo4jUmlBridge bridge) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = useModel;
        this.modelApi = modelApi;
        this.bridge = bridge;
    }

    public SyncCoordinator(Session session, MainWindow mainWindow) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = session.system().model();
        this.modelApi = new UseModelApi(session.system().model());
        this.bridge = new Neo4jUmlBridge(this.modelApi);
    }

    public SyncCoordinator(Session session) {
        this.useSession = session;
        this.mainWindow = null;
        this.useModel = session.system().model();
        this.modelApi = new UseModelApi(session.system().model());
        this.bridge = new Neo4jUmlBridge(this.modelApi);
    }


    public MModel getUseModel() {
        return useModel;
    }

    public UseModelApi getModelApi() {
        return modelApi;
    }

    public Neo4jUmlBridge getBridge() {
        return bridge;
    }




    /**
     * Phân tích sâu sự khác biệt giữa 2 Snapshot
     */
    public ModelDiff compareWithNeo4j() {
        ModelDiff diff = new ModelDiff();
        Map<String, ClassState> neo4jState = getModelSnapshotFromNeo4j();
        Map<String, ClassState> useUIState = getModelSnapshotFromUSE();

        // 1. Tìm những thứ Java có (có thể là javaOnly hoặc mismatched)
        for (String className : useUIState.keySet()) {
            ClassState useCls = useUIState.get(className);
            if (!neo4jState.containsKey(className)) {
                // Java có mà DB không có
                diff.javaOnlyClasses.add(className);
            } else {
                // Cả hai cùng có, kiểm tra deep equal
                ClassState n4jCls = neo4jState.get(className);
                if (!useCls.isSameAs(n4jCls)) {
                    diff.mismatchedClasses.add(className);
                    // Kiểm tra chi tiết Attribute
                    compareAttributesDeep(className, useCls, n4jCls, diff);
                }
            }
        }

        // 2. Tìm những thứ chỉ Neo4j mới có
        for (String className : neo4jState.keySet()) {
            if (!useUIState.containsKey(className)) {
                diff.neo4jOnlyClasses.add(className);
            }
        }

        return diff;
    }

    private void compareAttributesDeep(String clsName, ClassState useCls, ClassState n4jCls, ModelDiff diff) {
        List<String> javaOnly = new ArrayList<>();
        List<String> neo4jOnly = new ArrayList<>();

        for (String a : useCls.getAttributes().keySet()) {
            if (!n4jCls.getAttributes().containsKey(a)) javaOnly.add(a);
        }
        for (String a : n4jCls.getAttributes().keySet()) {
            if (!useCls.getAttributes().containsKey(a)) neo4jOnly.add(a);
        }

        if (!javaOnly.isEmpty()) diff.javaOnlyAttributes.put(clsName, javaOnly);
        if (!neo4jOnly.isEmpty()) diff.neo4jOnlyAttributes.put(clsName, neo4jOnly);
    }
    private Map<String, ClassState> getModelSnapshotFromNeo4j() {
        Map<String, ClassState> stateMap = new HashMap<>();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            String cypher =
                    "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
                            "MATCH (inst)-[:InstanceOf]->(meta) " +
                            "WITH inst, labels(inst)[0] as classType " +

                            // 1. Lấy Attributes
                            "OPTIONAL MATCH (inst)-[:HasAttribute]->(a) " +
                            "OPTIONAL MATCH (a)-[r1]->(aRef) WHERE type(r1) = 'ReferenceType' " +
                            "WITH inst, classType, collect(DISTINCT {name: a.attrName, type: a.type, idx: a.index, ref: aRef.name}) as attrs " +

                            // 2. Lấy Invariants
                            "OPTIONAL MATCH (inst)-[:HasInvariant]->(inv) " +
                            "WITH inst, classType, attrs, collect(DISTINCT {name: inv.invName, expr: inv.expression, exist: inv.isExistential}) as invariants " +

                            // 3. Xử lý Operations và con của nó
                            "OPTIONAL MATCH (inst)-[:HasOperation]->(o) " +
                            "OPTIONAL MATCH (o)-[r2]->(retRef) WHERE type(r2) = 'ReferenceReturnType' " +

                            "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                            "OPTIONAL MATCH (p)-[r3]->(pRef) WHERE type(r3) = 'ReferenceType' " +
                            "WITH inst, classType, attrs, invariants, o, retRef, " +
                            "collect(DISTINCT {pName: p.pName, pType: p.type, pOrder: p.pOrder, pRef: pRef.name}) as opParams " +

                            "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                            "WITH inst, classType, attrs, invariants, o, retRef, opParams, " +
                            "collect(DISTINCT {name: pre.condName, expr: pre.expression}) as opPres " +

                            "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +
                            "WITH inst, classType, attrs, invariants, o, retRef, opParams, opPres, " +
                            "collect(DISTINCT {name: post.condName, expr: post.expression}) as opPosts " +

                            "WITH inst, classType, attrs, invariants, " +
                            "collect(DISTINCT {name: o.opName, ret: o.returnType, retRef: retRef.name, params: opParams, pre: opPres, post: opPosts}) as ops " +

                            "RETURN inst.name as className, classType, attrs, invariants, ops";

            Result result = session.run(cypher);

            while (result.hasNext()) {
                org.neo4j.driver.Record row = result.next();
                ClassState cls = new ClassState();
                cls.setName(row.get("className").asString());
                cls.setType(row.get("classType").asString());

                // --- A. XỬ LÝ ATTRIBUTES (Null-Safe) ---
                Value attrsVal = row.get("attrs");
                if (!attrsVal.isNull()) {
                    for (Value aVal : attrsVal.asList(v -> v)) {
                        if (aVal.get("name").isNull()) continue;
                        Map<String, Object> props = new HashMap<>(aVal.asMap());
                        if ("Object".equals(props.get("type"))) props.put("type", props.get("ref"));
                        cls.getAttributes().put((String)props.get("name"), props);
                    }
                }

                // --- B. XỬ LÝ INVARIANTS (Null-Safe) ---
                Value invsVal = row.get("invariants");
                if (!invsVal.isNull()) {
                    for (Value invVal : invsVal.asList(v -> v)) {
                        if (invVal.get("expr").isNull()) continue;
                        Map<String, Object> invMap = new HashMap<>(invVal.asMap());
                        invMap.put("exist", invVal.get("exist").asBoolean(false));
                        cls.addInvariant(invMap);
                    }
                }

                // --- C. XỬ LÝ OPERATIONS (Null-Safe & Nested Null-Safe) ---
                Value opsVal = row.get("ops");
                if (!opsVal.isNull()) {
                    for (Value oVal : opsVal.asList(v -> v)) {
                        if (oVal.get("name").isNull()) continue;

                        String opName = oVal.get("name").asString();
                        Map<String, Object> opProps = new HashMap<>();
                        String finalRet = "Object".equals(oVal.get("ret").asString()) ?
                                oVal.get("retRef").asString() : oVal.get("ret").asString();
                        opProps.put("returnType", finalRet);
                        cls.getOperations().put(opName, opProps);

                        // C.1 Params
                        Value paramsVal = oVal.get("params");
                        if (!paramsVal.isNull()) {
                            for (Value pVal : paramsVal.asList(v -> v)) {
                                if (pVal.get("pName").isNull()) continue;
                                int order = pVal.get("pOrder").asInt(0);
                                String pType = "Object".equals(pVal.get("pType").asString()) ? pVal.get("pRef").asString() : pVal.get("pType").asString();
                                cls.addOperationParam(opName, pVal.get("pName").asString(), pType, order);
                            }
                        }

                        // C.2 Pre-Conditions
                        Value preVal = oVal.get("pre");
                        if (!preVal.isNull()) {
                            for (Value p : preVal.asList(v -> v)) {
                                if (!p.get("expr").isNull()) cls.addPreCondition(opName, new HashMap<>(p.asMap()));
                            }
                        }

                        // C.3 Post-Conditions
                        Value postVal = oVal.get("post");
                        if (!postVal.isNull()) {
                            for (Value p : postVal.asList(v -> v)) {
                                if (!p.get("expr").isNull()) cls.addPostCondition(opName, new HashMap<>(p.asMap()));
                            }
                        }
                    }
                }
                stateMap.put(cls.getName(), cls);
            }

            // Bước Kế thừa (Duyệt an toàn)
            Result genRes = session.run("MATCH (c)-[:Extends]->(p) RETURN c.name as child, p.name as parent");
            while (genRes.hasNext()) {
                Record r = genRes.next();
                if (stateMap.containsKey(r.get("child").asString())) {
                    stateMap.get(r.get("child").asString()).getParents().add(r.get("parent").asString());
                }
            }
        }
        return stateMap;
    }
    /**
     * Chuyển đổi USE MModel sang ClassState để so sánh
     */
    private Map<String, ClassState> getModelSnapshotFromUSE() {
        Map<String, ClassState> stateMap = new HashMap<>();
        for (MClass mCls : useModel.classes()) {
            ClassState cls = new ClassState();
            cls.setName(mCls.name());
            cls.setType(mCls instanceof MAssociationClass ? "AssociationClass" :
                    (mCls.isAbstract() ? "AbstractClass" : "ConcreteClass"));

            // 1. Map Attributes
            for (MAttribute attr : mCls.attributes()) {
                Map<String, Object> props = new HashMap<>();
                props.put("attrName", attr.name());
                props.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
                props.put("index", attr.getPositionInModel());
                cls.getAttributes().put(attr.name(), props);
            }

            // 2. Map Operations & Constraints
            for (MOperation op : mCls.operations()) {
                Map<String, Object> opProps = new HashMap<>();
                opProps.put("returnType", UmlTypeTranslator.toDatabaseType(op.resultType()));
                cls.getOperations().put(op.name(), opProps);

                // Map Params
                int pIdx = 0;
                for (org.tzi.use.uml.ocl.expr.VarDecl p : op.paramList()) {
                    cls.addOperationParam(op.name(), p.name(), UmlTypeTranslator.toDatabaseType(p.type()), pIdx++);
                }

                // Map Pre/Post (Để so sánh)
                for (MPrePostCondition pre : op.preConditions()) {
                    Map<String, Object> pc = new HashMap<>();
                    pc.put("name", pre.name());
                    pc.put("expr", pre.expression().toString());
                    cls.addPreCondition(op.name(), pc);
                }
                for (MPrePostCondition post : op.postConditions()) {
                    Map<String, Object> pc = new HashMap<>();
                    pc.put("name", post.name());
                    pc.put("expr", post.expression().toString());
                    cls.addPostCondition(op.name(), pc);
                }
            }

            // 3. Map Class Invariants
            for (MClassInvariant inv : useModel.classInvariants(mCls)) {
                Map<String, Object> invMap = new HashMap<>();
                invMap.put("name", inv.name());
                invMap.put("expr", inv.bodyExpression().toString());
                invMap.put("exist", inv.isExistential());
                cls.addInvariant(invMap);
            }

            mCls.parents().forEach(p -> cls.getParents().add(p.name()));
            stateMap.put(cls.getName(), cls);
        }
        return stateMap;
    }

    private void handleCriticalDeletion(List<String> deletedClasses) {
        // Đặc tả đề xuất C1 & C2
        String[] options = {"Delete all objects (C1)", "Scan & Keep Valid (C2)", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null,
            "Warning: Deleting classes will destroy associated objects/links in Neo4j!",
            "Critical Conflict", 0, JOptionPane.ERROR_MESSAGE, null, options, options[2]);

        if (choice == 0) {
            // Xóa triệt để các node có label thuộc deletedClasses
            deletedClasses.forEach(this::deleteObjectsByLabel);
        } else if (choice == 1) {
            // Logic Scan phức tạp hơn (Kiểm tra xem node có đang tham gia quan hệ nào ko)
        }
    }

    private void deleteObjectsByLabel(String label) {
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run("MATCH (n:`" + label + "`) DETACH DELETE n");
        }
    }

    /**
     * Quy trình Sync Ngược: Neo4j -> Java
     */
    public void syncBackward() {
        // Lấy định danh người dùng để Lock
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        LockManager.LockResult lock = LockManager.acquireLock(user);
        if (!lock.success) {
            JOptionPane.showMessageDialog(null, "Database is locked by: " + lock.owner);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                // 1. Lấy snapshot toàn diện từ DB (Đã bao gồm Attr, Op, Param, RefType)
                Map<String, ClassState> dbSnapshot = getModelSnapshotFromNeo4j();
                if (dbSnapshot.isEmpty()) {
                    JOptionPane.showMessageDialog(null, "No model found in Database.");
                    return;
                }

                // 2. ĐẶC TẢ 1: Lấy tên Model thực tế từ DB
                String actualModelName = getModelNameFromDb(); // Hàm này đọc từ node ModelVersion

                // 3. QUAN TRỌNG: Đóng các view đang mở để tránh NullPointer khi xóa data cũ
                // Đã thảo luận: GUI của USE liên kết cứng với đối tượng trong bộ nhớ, phải đóng trước khi Replace
                //mainWindow.closeAllViews();

                // 4. TẠO MODEL MỚI với tên chính xác
                ModelFactory factory = new ModelFactory();
                MModel newModel = factory.createModel(actualModelName);

                // 5. NẠP DỮ LIỆU: Gọi hàm nạp (đổi tên từ rebuild thành applyChangesFromSnapshot cho chuẩn)
                // Hàm này chứa 5 bước: Class -> Attr (Index) -> Op (Ref) -> Gen -> Assoc
                applyChangesFromSnapshot(newModel, dbSnapshot);

                // 6. THAY THẾ SESSION
                MSystem newSystem = new MSystem(newModel);
                useSession.setSystem(newSystem);

                // 7. CẬP NHẬT GIAO DIỆN
                // Dùng .update() thay vì .updateUI() để tool USE nạp lại cấu trúc cây thư mục
                mainWindow.getModelBrowser().updateUI();
               // mainWindow.showModelStatus();
                mainWindow.logWriter().println("Neo4j Sync: Model '" + actualModelName + "' successfully pulled.");

                JOptionPane.showMessageDialog(mainWindow, "Sync Backward Success!");

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Sync Error: " + e.getMessage());
            } finally {
                LockManager.releaseLock();
            }
        });
    }
    private String getModelNameFromDb() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(
                org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            org.neo4j.driver.Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.modelName as name");
            if (res.hasNext()) {
                return res.single().get("name").asString();
            }
        }
        return "UntitledModel"; // Fallback nếu không tìm thấy
    }

    /**
     * Kéo các quan hệ Association từ Neo4j và nạp vào một đối tượng Model mục tiêu
     * @param targetModel Đối tượng MModel mới đang được xây dựng
     * @param tempApi API thao tác trên targetModel
     */
    private void syncAssociationsToTargetModel(MModel targetModel, org.tzi.use.api.UseModelApi tempApi) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        // Sử dụng driver để mở session tới database hiện hành
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(
                org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            // Truy vấn lấy tất cả các cạnh quan hệ Model (M1)
            String cypher = "MATCH (s)-[r]->(t) " +
                    "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
                    "RETURN r.associationName as name, s.name as src, t.name as tgt, " +
                    "r.sourceClassrole as sRole, r.sourceMultiplicity as sMult, " +
                    "r.targerClassrole as tRole, r.targetMultiplicity as tMult, type(r) as edgeType";

            org.neo4j.driver.Result res = session.run(cypher);

            while (res.hasNext()) {
                org.neo4j.driver.Record rec = res.next();
                String assocName = rec.get("name").asString();

                // 1. Kiểm tra tránh trùng lặp trong targetModel (Idempotency)
                if (targetModel.getAssociation(assocName) != null) continue;

                // 2. Trích xuất metadata từ DB (Xử lý Null để tránh Exception)
                String srcName = rec.get("src").asString();
                String tgtName = rec.get("tgt").asString();
                String sRole = rec.get("sRole").isNull() ? "src_" + assocName : rec.get("sRole").asString();
                String tRole = rec.get("tRole").isNull() ? "tgt_" + assocName : rec.get("tRole").asString();
                String sMult = rec.get("sMult").isNull() ? "1" : rec.get("sMult").asString();
                String tMult = rec.get("tMult").isNull() ? "*" : rec.get("tMult").asString();

                // 3. Xác định Aggregation Kind cho đầu Target (Đặc tả 9, 10, 11)
                // 0: None (AssociateWith), 1: Aggregate (Aggregates), 2: Composite (ComposeOf)
                int kind = 0;
                String edgeType = rec.get("edgeType").asString();
                if (edgeType.equals("ComposeOf")) kind = 2;
                else if (edgeType.equals("Aggregates")) kind = 1;
                // Nếu là AssociateWith, kind mặc định là 0 (Chính xác)

                try {
                    // 4. Kiểm tra sự tồn tại của các Class tham gia trong targetModel
                    MClass srcCls = targetModel.getClass(srcName);
                    MClass tgtCls = targetModel.getClass(tgtName);

                    if (srcCls == null || tgtCls == null) {
                        System.err.println("Skip Assoc [" + assocName + "]: One of the classes (" +
                                srcName + ", " + tgtName + ") is missing in Snapshot.");
                        continue;
                    }

                    // 5. Thực thi tạo Association thông qua API của USE trên Model mới
                    tempApi.createAssociation(
                            assocName,
                            srcName, sRole, sMult, 0, // Đầu source thường là 0 (None)
                            tgtName, tRole, tMult, kind
                    );

                    WorkLogManager.getInstance().log("PULL_ASSOC", "Successfully recreated association: " + assocName);

                } catch (Exception e) {
                    // Log lỗi chi tiết nếu có ràng buộc OCL bị vi phạm
                    System.err.println("Critical error creating association " + assocName + ": " + e.getMessage());
                    WorkLogManager.getInstance().log("PULL_ASSOC_ERROR", assocName + " -> " + e.getMessage());
                }
            }
        } catch (Exception e) {
            WorkLogManager.getInstance().log("PULL_DB_ERROR", "Failed to query associations: " + e.getMessage());
        }
    }

    /**
     * Vấn đề 1: Xóa sạch các thành phần cũ trong Java
     */
    private void wipeLocalModel() {

            WorkLogManager.getInstance().log("WIPE", "Deep cleaning USE Model...");

            // 1. Xóa Association và các ràng buộc phụ thuộc
            useModel.associations().clear();
            useModel.classInvariants().clear();

            // 2. QUAN TRỌNG: Phải xóa sạch quan hệ kế thừa (Generalization)
            // để phá vỡ liên kết giữa Person và Professor trước khi nạp lại
            useModel.generalizationGraph().clear();

            // 3. Xóa các thực thể Class
            useModel.classes().clear();

            // 4. Xóa Enum
            useModel.enumTypes().clear();

            WorkLogManager.getInstance().log("WIPE", "Model is now completely sterile.");
    }
    /**
     * Xây dựng lại Model dựa trên Snapshot (Đảm bảo thứ tự Index và Link)
     */
    /**
     * Xây dựng toàn bộ cấu trúc Model vào một đối tượng MModel mục tiêu
     * @param targetModel Đối tượng model mới tinh (thường dùng cho chiến lược REPLACE)
     * @param dbSnapshot Snapshot dữ liệu metadata lấy từ Neo4j
     */
    private void applyChangesFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) throws Exception {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);

        // BƯỚC 1 & 2: Classes và Attributes (Sửa lỗi ép kiểu Index)
        for (ClassState dbCls : dbSnapshot.values()) {
            tempApi.createClass(dbCls.getName(), dbCls.getType().equals("NodeAbstractClass"));

            List<Map<String, Object>> attrList = new ArrayList<>(dbCls.getAttributes().values());
            // Fix Long to Integer cast cho index
            attrList.sort(Comparator.comparingInt(a -> ((Number) a.getOrDefault("index", 0)).intValue()));

            for (Map<String, Object> aProps : attrList) {
                String attrName = (String) aProps.get("name");
                String umlType = UmlTypeTranslator.toUmlTypeString((String) aProps.get("type"));
                tempApi.createAttribute(dbCls.getName(), attrName, umlType);
            }
        }
        // BƯỚC 3: Nạp OPERATIONS (Xử lý lỗi Void, Tham số và Ngữ cảnh OCL)
        // BƯỚC 3: Nạp OPERATIONS (Signature & Parameters)
        for (ClassState dbCls : dbSnapshot.values()) {
            String className = dbCls.getName();

            for (Map.Entry<String, Map<String, Object>> opEntry : dbCls.getOperations().entrySet()) {
                String opName = opEntry.getKey();
                Map<String, Object> opProps = opEntry.getValue();

                try {
                    // 1. Xử lý kiểu trả về
                    String rawRetType = (String) opProps.get("returnType");
                    String umlRetType = UmlTypeTranslator.toUmlTypeString(rawRetType);

                    // 2. Xử lý danh sách tham số (Lấy từ dữ liệu lồng nhau đã nạp ở trên)
                    List<ClassState.OpParam> paramList = dbCls.getOpParamsSorted(opName);
                    String[][] parameterArray = new String[paramList.size()][2];

                    for (int i = 0; i < paramList.size(); i++) {
                        ClassState.OpParam p = paramList.get(i);
                        String pName = p.getName();

                        // Fix lỗi tên tham số bị rỗng
                        if (pName == null || pName.isEmpty()) pName = "arg" + i;

                        // Translate kiểu (vd: "Double" -> "Real", "Person" -> "Person")
                        String pType = UmlTypeTranslator.toUmlTypeString(p.getType());

                        parameterArray[i][0] = pName;
                        parameterArray[i][1] = pType;

                        System.out.println("DEBUG: Rebuilding Op [" + opName + "] with param [" + pName + ":" + pType + "]");
                    }

                    // 3. Gọi API của USE tạo Signature hoàn chỉnh
                    // Sau dòng này, đối tượng MOperation trong bộ nhớ Java đã biết biến 'p' là gì
                    tempApi.createOperation(className, opName, parameterArray, umlRetType);

                    WorkLogManager.getInstance().log("PULL_OP", "Created Signature: " + className + "::" + opName);

                } catch (Exception e) {
                    String errMsg = String.format("Failed to define Op [%s] in Class [%s]: %s",
                            opName, className, e.getMessage());
                    WorkLogManager.getInstance().log("PULL_OP_ERROR", errMsg);
                }
            }
        }

        // BƯỚC 4: Thiết lập quan hệ Kế thừa (Extends)
        for (ClassState dbCls : dbSnapshot.values()) {
            MClass child = targetModel.getClass(dbCls.getName());
            for (String pName : dbCls.getParents()) {
                MClass parent = targetModel.getClass(pName);
                if (child != null && parent != null) {
                    tempApi.createGeneralizationEx(child, parent);
                }
            }
        }

        // BƯỚC 5: Nạp Associations (Đặc tả: Fix AssociateWith và Linkage)
        // Lưu ý: Phải truyền targetModel vào hàm này để nó nạp đúng chỗ
        syncAssociationsToTargetModel(targetModel, tempApi);


        // --- BƯỚC 6: NẠP CLASS INVARIANTS (OCL) ---
        applyInvariantsFromSnapshot(targetModel, dbSnapshot);

        // --- BƯỚC 7: NẠP PRE/POST CONDITIONS (OCL) ---
        applyOperationConstraintsFromSnapshot(targetModel, dbSnapshot);
    }

    /**
     * Helper nạp Invariants (Tách riêng cho gọn)
     */
    private void applyInvariantsFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);
        for (ClassState dbCls : dbSnapshot.values()) {
            for (Map<String, Object> invProps : dbCls.getInvariants()) {
                String invName = (String) invProps.get("name"); // Key từ Cypher
                String oclBody = (String) invProps.get("expr"); // Key từ Cypher
                boolean isExist = (boolean) invProps.get("exist"); // Key từ Cypher

                try {
                    if (oclBody != null) {
                        tempApi.createInvariant(invName, dbCls.getName(), oclBody, isExist);
                    }
                } catch (Exception e) {
                    System.err.println("OCL Invariant Error in " + dbCls.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private void applyOperationConstraintsFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);
        PrintWriter nullWriter = NullPrintWriter.getInstance();

        for (ClassState dbCls : dbSnapshot.values()) {
            String className = dbCls.getName();
            MClass useCls = targetModel.getClass(className);
            if (useCls == null) continue;

            for (String opName : dbCls.getOperations().keySet()) {
                // Lấy đối tượng Operation đã nạp ở Bước 3
                MOperation useOp = useCls.operation(opName, false);
                if (useOp == null) continue;

                // --- NẠP PRE-CONDITIONS ---
                for (Map<String, Object> pc : dbCls.getPreConditions(opName)) {
                    compileAndAddConstraint(targetModel, tempApi, useCls, useOp, pc, true);
                }

                // --- NẠP POST-CONDITIONS ---
                for (Map<String, Object> pc : dbCls.getPostConditions(opName)) {
                    compileAndAddConstraint(targetModel, tempApi, useCls, useOp, pc, false);
                }
            }
        }
    }

    /**
     * GIẢI PHÁP THEN CHỐT: Tự xây dựng Symbol Table bao gồm cả tham số hàm
     */
    private void compileAndAddConstraint(MModel model, org.tzi.use.api.UseModelApi api,
                                         MClass cls, MOperation op, Map<String, Object> props, boolean isPre) {
        String condName = (String) props.get("name");
        String oclExpr = (String) props.get("expr");
        if (oclExpr == null || oclExpr.isEmpty()) return;

        try {
            // 1. Khởi tạo bảng ký hiệu (Symtable)
            Symtable symtable = new Symtable();

            // 2. Đưa 'self' vào ngữ cảnh
            symtable.add("self", cls, null);

            // 3. Đưa TẤT CẢ tham số của hàm vào ngữ cảnh (Để máy hiểu 'p' là gì)
            for (int i = 0; i < op.paramList().size(); i++) {
                VarDecl param = op.paramList().varDecl(i);
                symtable.add(param.name(), param.type(), null);
            }

            // 4. Nếu là Post-condition, đưa thêm biến 'result' vào (nếu hàm có kiểu trả về)
            if (!isPre && op.hasResultType()) {
                symtable.add("result", op.resultType(), null);
            }

            // 5. Biên dịch chuỗi OCL với bảng ký hiệu đầy đủ
            Expression compiledExpr = OCLCompiler.compileExpression(
                    model, oclExpr, "SyncPull", NullPrintWriter.getInstance(), symtable);

            if (compiledExpr != null) {
                // 6. Dùng hàm "Ex" để nạp Expression đã biên dịch xong vào Model
                api.createPrePostConditionEx(condName, op, isPre, compiledExpr);
                WorkLogManager.getInstance().log("PULL_CONST", "Applied: " + op.name() + "::" + condName);
            }
        } catch (Exception e) {
            System.err.println("Failed to compile constraint [" + condName + "]: " + e.getMessage());
        }
    }

    public boolean isQuickSyncCheck() {
        try {
            long localHash = SyncObjectCoordinator.calculateModelHash(useModel);
            long remoteHash = SyncObjectCoordinator.getRemoteModelHash(); // Lấy từ node ModelVersion
            return localHash == remoteHash;
        } catch (Exception e) {
            return false;
        }
    }

    public void syncForward() {
        // 1. Lấy định danh người dùng đầy đủ (Tài khoản + Key + Tên máy)
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        // 2. Lock DB (Ticket Mechanism) - Trả về LockResult chứa owner nếu thất bại
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if (!lock.success) {
            JOptionPane.showMessageDialog(null,
                    "Database is currently LOCKED by:\n" + lock.owner +
                            "\nPlease wait until they finish or the lock expires.",
                    "Sync Blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            // 3. So sánh Snapshot giữa Java và Neo4j (Neutral Comparison)
            ModelDiff diff = compareWithNeo4j();

            if (diff.hasDifference()) {
                // 4. Hiển thị Pop-up thống kê cho chiều XUÔI (Java -> DB)
                JTextArea textArea = new JTextArea(diff.generateForwardReport());
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
                textArea.setEditable(false);
                JScrollPane scrollPane = new JScrollPane(textArea);
                scrollPane.setPreferredSize(new Dimension(600, 450));

                int res = JOptionPane.showConfirmDialog(null, scrollPane,
                        "Push Model to Neo4j - Review Changes", JOptionPane.OK_CANCEL_OPTION);

                if (res != JOptionPane.OK_OPTION) {
                    WorkLogManager.getInstance().log("SYNC_CANCEL", "User cancelled sync after reviewing diff.");
                    return;
                }

                // 5. Xử lý chiến lược xóa (Nếu DB đang thừa so với Java)
                // Những Class chỉ có ở Neo4j (neo4jOnlyClasses) sẽ bị xóa khỏi DB
                if (!diff.neo4jOnlyClasses.isEmpty()) {
                    handleDeletionStrategy(diff.neo4jOnlyClasses);
                }
            }

            // 6. Thực thi ghi đè/bổ sung cấu trúc UML xuống Neo4j
            // bridge.pushModelToNeo4j đã bao gồm lệnh MERGE và InstanceOf
            bridge.pushModelToNeo4j();

            // 7. Cập nhật ModelVersion (Hash + Timestamp + User)
            // Đây là bước quan trọng để "mở khóa" cho tầng Object Sync
            bridge.updateModelVersion(session, userIdentifier);

            WorkLogManager.getInstance().log("SYNC_FORWARD_SUCCESS", "Model pushed by: " + userIdentifier);
            JOptionPane.showMessageDialog(null, "Synchronization to Neo4j completed successfully!");

        } catch (Exception e) {
            WorkLogManager.getInstance().log("SYNC_FORWARD_ERROR", e.getMessage());
            JOptionPane.showMessageDialog(null, "Sync failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        } finally {
            // 8. Giải phóng Ticket Lock
            LockManager.releaseLock();
        }
    }

    /**
     * Chiến lược xử lý khi DB có Class mà Java không có (C1/C2)
     */
    private void handleDeletionStrategy(List<String> classesToRemoveFromDb) {
        String[] options = {"C1: Delete all instances & links", "C2: Scan (Advanced)", "Keep in DB (Inconsistent)"};
        int choice = JOptionPane.showOptionDialog(null,
                "The following classes exist in DB but NOT in your USE model:\n" + classesToRemoveFromDb +
                        "\nHow should we handle their objects under Neo4j?",
                "Data Integrity Strategy", 0, JOptionPane.WARNING_MESSAGE, null, options, options[0]);

        if (choice == 0) { // C1: Xóa sạch triệt để
            try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
                for (String label : classesToRemoveFromDb) {
                    // Xóa Node và mọi quan hệ liên quan (HasAttribute, InstanceOf, HAS_VALUE...)
                    session.run("MATCH (n:`" + label + "`) DETACH DELETE n");
                    // Xóa cả định nghĩa Class M1 tương ứng
                    session.run("MATCH (c {name: $name}) DETACH DELETE c",
                            org.neo4j.driver.Values.parameters("name", label));
                }
            }
            WorkLogManager.getInstance().log("CLEANUP", "Executed C1 strategy for: " + classesToRemoveFromDb);
        }
        // Các lựa chọn khác có thể bỏ qua hoặc xử lý sau
    }

    // Trong lớp SyncCoordinator.java (Tầng Model)
    public String getQuickSummary() {
        try {
            if (isQuickSyncCheck()) { // Check ModelHash
                return "All Synced";
            }

            ModelDiff diff = compareWithNeo4j();
            if (!diff.hasDifference()) return "All Synced";

            return String.format("%d New, %d Missing, %d Mismatch",
                    diff.javaOnlyClasses.size(),
                    diff.neo4jOnlyClasses.size(),
                    diff.mismatchedClasses.size());
        } catch (Exception e) {
            return "Check Failed";
        }
    }

}