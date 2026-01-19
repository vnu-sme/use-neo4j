package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
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
import org.uet.dse.neo4j.model.AssociationState;
import org.uet.dse.neo4j.model.ClassState;
import org.uet.dse.neo4j.model.FullModelSnapshot;
import org.uet.dse.neo4j.phase2.Neo4jUmlBridge;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.phase2.WorkLogManager;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

public class ModelSyncCoordinator {
    private final Session useSession; // Thay vì chỉ giữ MModel, hãy giữ cả Session
    private final MModel useModel;

    private final MainWindow mainWindow;
    private final UseModelApi modelApi;
    private final Neo4jUmlBridge bridge;

    public ModelSyncCoordinator(Session session, MainWindow mainWindow, MModel useModel, UseModelApi modelApi, Neo4jUmlBridge bridge) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = useModel;
        this.modelApi = modelApi;
        this.bridge = bridge;
    }

    public ModelSyncCoordinator(Session session, MainWindow mainWindow) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = session.system().model();
        this.modelApi = new UseModelApi(session.system().model());
        this.bridge = new Neo4jUmlBridge(this.modelApi);
    }

    public ModelSyncCoordinator(Session session) {
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



    public void syncForward() {
        //get user identification
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        //lock db while working
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if (!lock.success) {
            JOptionPane.showMessageDialog(null,
                    "Database is currently LOCKED by:\n" + lock.owner +
                            "\nPlease wait until they finish or the lock expires.",
                    "Sync Blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
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

                //delete strategy
                if (!diff.neo4jOnlyClasses.isEmpty()) {
                    handleDeletionStrategy(diff.neo4jOnlyClasses);
                }
            }


            //overwrite and add stuff that is in USE but not neo4j
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
            LockManager.releaseLock();
        }
    }


    /**
     * Phân tích sâu sự khác biệt giữa 2 Snapshot
     */
    public ModelDiff compareWithNeo4j() {
        ModelDiff diff = new ModelDiff();
        FullModelSnapshot neoSnap = getFullModelSnapshotFromNeo4j();
        FullModelSnapshot javaSnap = getFullModelSnapshotFromUSE();

        // 1. Tìm những thứ Java có (có thể là javaOnly hoặc mismatched)
        for (String className : javaSnap.classes.keySet()) {
            ClassState useCls = javaSnap.classes.get(className);
            if (!neoSnap.classes.containsKey(className)) {
                // Java có mà DB không có
                diff.javaOnlyClasses.add(className);
            } else {
                // Cả hai cùng có, kiểm tra deep equal
                ClassState n4jCls = neoSnap.classes.get(className);
                if (!useCls.isSameAs(n4jCls)) {
                    diff.mismatchedClasses.add(className);
                    // Kiểm tra chi tiết Attribute
                    compareAttributesDeep(className, useCls, n4jCls, diff);
                }
            }
        }

        // 2. Tìm những thứ chỉ Neo4j mới có
        for (String className : neoSnap.classes.keySet()) {
            if (!javaSnap.classes.containsKey(className)) {
                diff.neo4jOnlyClasses.add(className);
            }
        }


        // --- SO SÁNH ASSOCIATION (MỚI) ---
        // 1. Tìm Association chỉ có ở Java hoặc bị Mismatch -> Cần Push
        for (String name : javaSnap.associations.keySet()) {
            AssociationState jAssoc = javaSnap.associations.get(name);
            if (!neoSnap.associations.containsKey(name)) {
                diff.javaOnlyAssociations.add(name);
            } else if (!jAssoc.isSameAs(neoSnap.associations.get(name))) {
                diff.mismatchedAssociations.add(name);
            }
        }

        // 2. Tìm Association chỉ có ở Neo4j -> Cần Delete ở DB (khi push) hoặc Add vào Java (khi pull)
        for (String name : neoSnap.associations.keySet()) {
            if (!javaSnap.associations.containsKey(name)) {
                diff.neo4jOnlyAssociations.add(name);
            }
        }

        return diff;
    }

    private Map<String, ClassState> getModelSnapshotFromNeo4j() {
        Map<String, ClassState> stateMap = new HashMap<>();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            String cypher =
                    // get class & enums
                    "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                            "MATCH (inst)-[:InstanceOf]->(meta) " +
                            "WITH inst, meta, labels(inst)[0] as classType " +

                            //attribute
                            "OPTIONAL MATCH (inst)-[:HasAttribute]->(a) " +
                            "OPTIONAL MATCH (a)-[r1]->(aRef) WHERE type(r1) = 'ReferenceType' " +
                            "WITH inst, meta, classType, collect(DISTINCT {name: a.attrName, type: a.type, idx: a.index, ref: aRef.name, sCollection: a.isCollection, collectionType: a.collectionType}) as attrs " +

                            //invariants
                            "OPTIONAL MATCH (inst)-[:HasInvariant]->(inv) " +
                            "WITH inst, meta, classType, attrs, collect(DISTINCT {name: inv.invName, expr: inv.expression, exist: inv.isExistential}) as invariants " +

                            //association class
                            "OPTIONAL MATCH (src)-[s:sourceAssoClass]->(inst) " +
                            "OPTIONAL MATCH (inst)-[t:targetAssoClass]->(tgt) " +
                            "WITH inst, meta, classType, attrs, invariants, " +
                            "{name: src.name, role: s.role, mult: s.multiplicity} as acSrc, " +
                            "{name: tgt.name, role: t.role, mult: t.multiplicity} as acTgt " +

                            //operation & param & pre-post
                            "OPTIONAL MATCH (inst)-[:HasOperation]->(o) " +
                            "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                            "OPTIONAL MATCH (p)-[r2]->(pRef) WHERE type(r2) = 'ReferenceType' " +
                            "OPTIONAL MATCH (o)-[r3]->(retRef) WHERE type(r3) = 'ReferenceReturnType' " +
                            "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                            "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                            "WITH inst, meta, classType, attrs, invariants, acSrc, acTgt, o, retRef, " +
                            "collect(DISTINCT {pName: p.pName, pType: p.type, pOrder: p.order, pRef: pRef.name}) as opParams, " +
                            "collect(DISTINCT {name: pre.condName, expr: pre.expression}) as opPres, " +
                            "collect(DISTINCT {name: post.condName, expr: post.expression}) as opPosts " +

                            "WITH inst, meta, classType, attrs, invariants, acSrc, acTgt, " +
                            "collect(DISTINCT {name: o.opName, ret: o.returnType, retRef: retRef.name, params: opParams, pre: opPres, post: opPosts}) as ops " +

                            "RETURN inst.name as className, meta.name as metaName, classType, inst.value as enumValues, attrs, invariants, ops, acSrc, acTgt";
            Result result = session.run(cypher);

            while (result.hasNext()) {
                org.neo4j.driver.Record row = result.next();

                ClassState cls = new ClassState();
                cls.setName(row.get("className").asString());
                cls.setType(row.get("classType").asString());
                cls.setMetaNodeName(row.get("metaName").asString());

                if ("NodeEnumeration".equals(cls.getMetaNodeName())) {
                    Value enumVals = row.get("enumValues");
                    if (!enumVals.isNull()) {
                        cls.setEnumLiterals(enumVals.asList(Value::asString));
                    }
                }

                if ("NodeAssociationClass".equals(cls.getMetaNodeName())) {
                    Value src = row.get("acSrc");
                    Value tgt = row.get("acTgt");
                    if (!src.get("name").isNull())
                        cls.setAcSource(src.get("name").asString(), src.get("role").asString(), src.get("mult").asString());
                    if (!tgt.get("name").isNull())
                        cls.setAcTarget(tgt.get("name").asString(), tgt.get("role").asString(), tgt.get("mult").asString());
                }

                Value attrsVal = row.get("attrs");
                if (!attrsVal.isNull()) {
                    for (Value aVal : attrsVal.asList(v -> v)) {
                        // Kiểm tra node rỗng (Tránh lỗi do OPTIONAL MATCH không khớp)
                        if (aVal.get("name").isNull()) continue;

                        Map<String, Object> props = new HashMap<>();

                        // Tên thực tế của thuộc tính (ví dụ: 'age', 'sex')
                        String attrName = aVal.get("name").asString();
                        props.put("attrName", attrName);

                        // Dùng làm ID duy nhất trong Map
                        props.put("name", attrName);

                        // Xử lý kiểu dữ liệu (Kiểm tra Object Reference)
                        String rawType = aVal.get("type").asString("String");
                        if ("Object".equals(rawType)) {
                            // Nếu là Object, lấy tên Class/Enum đích từ trường 'ref'
                            props.put("type", aVal.get("ref").asString("String"));
                        } else {
                            props.put("type", rawType);
                        }

                        // Thứ tự hiển thị
                        props.put("index", aVal.get("idx").asInt(0));

                        // Thông tin tập hợp (Dùng đúng key sCollection từ Cypher)
                        props.put("isCollection", aVal.get("sCollection").asBoolean(false));
                        props.put("collectionType", aVal.get("collectionType").asString("Set"));

                        // Đưa vào danh sách thuộc tính của ClassState
                        cls.getAttributes().put(attrName, props);
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
//    private Map<String, ClassState> getModelSnapshotFromUSE() {
//        Map<String, ClassState> stateMap = new HashMap<>();
//
//        // ============================================================
//        // PHẦN 1: QUÉT TẤT CẢ CLASSES (CONCRETE, ABSTRACT, ASSOC-CLASS)
//        // ============================================================
//        for (MClass mCls : useModel.classes()) {
//            ClassState cls = new ClassState();
//            cls.setName(mCls.name());
//
//            // Gán MetaNodeName chuẩn (Để so sánh và nạp M2)
//            String metaName = "NodeConcreteClass";
//            if (mCls instanceof MAssociationClass) metaName = "NodeAssociationClass";
//            else if (mCls.isAbstract()) metaName = "NodeAbstractClass";
//            cls.setMetaNodeName(metaName);
//
//            // 1. Map Attributes
//            // Trong vòng lặp map Attributes của getModelSnapshotFromUSE
//            for (MAttribute attr : mCls.attributes()) {
//                Map<String, Object> props = new HashMap<>();
//                props.put("attrName", attr.name());
//                props.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
//                props.put("index", attr.getPositionInModel());
//
//                // SỬA LỖI Ở ĐÂY: Sử dụng interface Type để kiểm tra
//                org.tzi.use.uml.ocl.type.Type type = attr.type();
//                boolean isColl = type.isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
//                props.put("isCollection", isColl);
//
//                if (isColl) {
//                    // Xác định loại tập hợp dựa trên các hàm isKindOf...
//                    if (type.isKindOfSet(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
//                        props.put("collectionType", "Set");
//                    } else if (type.isKindOfOrderedSet(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
//                        props.put("collectionType", "OrderedSet");
//                    } else if (type.isKindOfSequence(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
//                        props.put("collectionType", "Sequence");
//                    } else if (type.isKindOfBag(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
//                        props.put("collectionType", "Bag");
//                    } else {
//                        props.put("collectionType", "Set"); // Default
//                    }
//                } else {
//                    props.put("collectionType", "None");
//                }
//
//                cls.getAttributes().put(attr.name(), props);
//            }
//
//            // 2. Map Operations & Constraints
//            for (MOperation op : mCls.operations()) {
//                Map<String, Object> opProps = new HashMap<>();
//                opProps.put("returnType", UmlTypeTranslator.toDatabaseType(op.resultType()));
//
//                // Thêm check collection cho Return Type
//                boolean retColl = op.resultType() != null && op.resultType().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
//                opProps.put("isReturnCollection", retColl);
//
//                cls.getOperations().put(op.name(), opProps);
//
//                // Map Params
//                int pIdx = 0;
//                for (org.tzi.use.uml.ocl.expr.VarDecl p : op.paramList()) {
//                    cls.addOperationParam(op.name(), p.name(), UmlTypeTranslator.toDatabaseType(p.type()), pIdx++);
//                }
//
//                // Map Pre/Post Conditions
//                for (MPrePostCondition pre : op.preConditions()) {
//                    Map<String, Object> pc = new HashMap<>();
//                    pc.put("name", pre.name());
//                    pc.put("expr", pre.expression().toString());
//                    cls.addPreCondition(op.name(), pc);
//                }
//                for (MPrePostCondition post : op.postConditions()) {
//                    Map<String, Object> pc = new HashMap<>();
//                    pc.put("name", post.name());
//                    pc.put("expr", post.expression().toString());
//                    cls.addPostCondition(op.name(), pc);
//                }
//            }
//
//            // 3. Map Class Invariants
//            for (MClassInvariant inv : useModel.classInvariants(mCls)) {
//                Map<String, Object> invMap = new HashMap<>();
//                invMap.put("name", inv.name());
//                invMap.put("expr", inv.bodyExpression().toString());
//                invMap.put("exist", inv.isExistential());
//                cls.addInvariant(invMap);
//            }
//
//            // 4. Map Kế thừa và AssociationClass info
//            mCls.parents().forEach(p -> cls.getParents().add(p.name()));
//
//            if (mCls instanceof MAssociationClass) {
//                MAssociationClass ac = (MAssociationClass) mCls;
//                cls.setAcSource(ac.associationEnds().get(0).cls().name(), ac.associationEnds().get(0).name(), ac.associationEnds().get(0).multiplicity().toString());
//                cls.setAcTarget(ac.associationEnds().get(1).cls().name(), ac.associationEnds().get(1).name(), ac.associationEnds().get(1).multiplicity().toString());
//            }
//
//            stateMap.put(cls.getName(), cls);
//        }
//
//        // ============================================================
//        // PHẦN 2: QUÉT TẤT CẢ ENUMERATIONS (SỬA LỖI POPUP CỦA BẠN)
//        // ============================================================
//        for (org.tzi.use.uml.ocl.type.EnumType e : useModel.enumTypes()) {
//            ClassState enumCls = new ClassState();
//            enumCls.setName(e.name());
//            enumCls.setMetaNodeName("NodeEnumeration");
//            enumCls.setEnumLiterals(e.getLiterals());
//
//            stateMap.put(enumCls.getName(), enumCls);
//        }
//
//        return stateMap;
//    }

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
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        LockManager.LockResult lock = LockManager.acquireLock(user);
        if (!lock.success) {
            JOptionPane.showMessageDialog(null, "Database is locked by: " + lock.owner);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                Map<String, ClassState> dbSnapshot = getModelSnapshotFromNeo4j();
                if (dbSnapshot.isEmpty()) {
                    JOptionPane.showMessageDialog(null, "No model found in Database.");
                    return;
                }

                String actualModelName = getModelNameFromDb(); // Hàm này đọc từ node ModelVersion

                // 3. QUAN TRỌNG: Đóng các view đang mở để tránh NullPointer khi xóa data cũ
                // Đã thảo luận: GUI của USE liên kết cứng với đối tượng trong bộ nhớ, phải đóng trước khi Replace
                //mainWindow.closeAllViews();

                //create new model
                ModelFactory factory = new ModelFactory();
                MModel newModel = factory.createModel(actualModelName);

                //map data to USE layer
                // Hàm này chứa 5 bước: Class -> Attr (Index) -> Op (Ref) -> Gen -> Assoc
                applyChangesFromSnapshot(newModel, dbSnapshot);

                // 6. THAY THẾ SESSION
                MSystem newSystem = new MSystem(newModel);
                useSession.setSystem(newSystem);

                //update & reload folder structure
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
     * Xây dựng toàn bộ cấu trúc Model vào một đối tượng MModel mục tiêu
     * @param targetModel Đối tượng model mới tinh (thường dùng cho chiến lược REPLACE)
     * @param dbSnapshot Snapshot dữ liệu metadata lấy từ Neo4j
     */
    private void applyChangesFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) throws Exception {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);

        //enums must be loaded first
        for (ClassState dbCls : dbSnapshot.values()) {
            if ("NodeEnumeration".equals(dbCls.getMetaNodeName())) {
                List<String> literals = dbCls.getEnumLiterals();
                if (literals != null && !literals.isEmpty()) {
                    tempApi.createEnumeration(dbCls.getName(), literals);
                    WorkLogManager.getInstance().log("PULL_ENUM", "Successfully created Enum: " + dbCls.getName());
                }
            }
        }

        //create concrete and abstract class first
        for (ClassState dbCls : dbSnapshot.values()) {
            if (!"NodeAssociationClass".equals(dbCls.getMetaNodeName()) && !"NodeEnumeration".equals(dbCls.getMetaNodeName())) {
                tempApi.createClass(dbCls.getName(), "NodeAbstractClass".equals(dbCls.getMetaNodeName()));
            }
        }

        //create association class
        for (ClassState dbCls : dbSnapshot.values()) {
            if ("NodeAssociationClass".equals(dbCls.getMetaNodeName())) {
                Map<String, Object> src = dbCls.getAcSource();
                Map<String, Object> tgt = dbCls.getAcTarget();

                tempApi.createAssociationClass(
                        dbCls.getName(), false,
                        (String)src.get("name"), (String)src.get("role"), (String)src.get("mult"), 0,
                        (String)tgt.get("name"), (String)tgt.get("role"), (String)tgt.get("mult"), 0
                );
            }
        }

        //all types are ready -> map attributes
        for (ClassState dbCls : dbSnapshot.values()) {
            // Enum không có thuộc tính, bỏ qua để tránh lỗi
            if ("NodeEnumeration".equals(dbCls.getMetaNodeName())) continue;

            List<Map<String, Object>> attrList = new ArrayList<>(dbCls.getAttributes().values());
            attrList.sort(Comparator.comparingInt(a -> ((Number) a.getOrDefault("index", 0)).intValue()));

            for (Map<String, Object> aProps : attrList) {
                String attrName = (String) aProps.get("attrName");
                // Hàm này sẽ trả về đúng chuỗi "ContractType"
                String umlTypeString = UmlTypeTranslator.reconstructFullTypeString(aProps);

                try {
                    // Lúc này tempApi.getType("ContractType") sẽ thành công!
                    tempApi.createAttribute(dbCls.getName(), attrName, umlTypeString);
                } catch (Exception e) {
                    System.err.println("Still failing at attribute: " + attrName + " Type: " + umlTypeString);
                }
            }
        }


        //operations mappings
        for (ClassState dbCls : dbSnapshot.values()) {
            String className = dbCls.getName();

            for (Map.Entry<String, Map<String, Object>> opEntry : dbCls.getOperations().entrySet()) {
                String opName = opEntry.getKey();
                Map<String, Object> opProps = opEntry.getValue();

                try {
                    //handle return type
                    String rawRetType = (String) opProps.get("returnType");
                    String umlRetType = UmlTypeTranslator.toUmlTypeString(rawRetType);

                    //handle parameter list
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

                    tempApi.createOperation(className, opName, parameterArray, umlRetType);

                    WorkLogManager.getInstance().log("PULL_OP", "Created Signature: " + className + "::" + opName);

                } catch (Exception e) {
                    String errMsg = String.format("Failed to define Op [%s] in Class [%s]: %s",
                            opName, className, e.getMessage());
                    WorkLogManager.getInstance().log("PULL_OP_ERROR", errMsg);
                }
            }
        }

        //handle extends association
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
            long localHash = ObjectSyncCoordinator.calculateModelHash(useModel);
            long remoteHash = ObjectSyncCoordinator.getRemoteModelHash(); // Lấy từ node ModelVersion
            return localHash == remoteHash;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Chiến lược xử lý khi DB có Class mà Java không có (C1/C2)
     */
    private void handleDeletionStrategy(List<String> classesToRemoveFromDb) {
        String[] options = {"C1: Delete all instances & metadata", "C2: Scan (Advanced)", "Keep in DB (Inconsistent)"};
        int choice = JOptionPane.showOptionDialog(null,
                "The following elements exist in DB but NOT in your USE model:\n" + classesToRemoveFromDb +
                        "\nChoosing C1 will permanently REMOVE their objects and all internal members (Attrs, Ops, OCL).",
                "Data Integrity Strategy", 0, JOptionPane.WARNING_MESSAGE, null, options, options[0]);

        if (choice == 0) { // CHIẾN LƯỢC C1: XÓA SÂU TRIỆT ĐỂ
            try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
                for (String label : classesToRemoveFromDb) {

                    // 1. Xóa toàn bộ thực thể Object (M0 Layer) có nhãn này
                    session.run("MATCH (n:`" + label + "`) DETACH DELETE n");

                    // 2. TRUY QUÉT VÀ XÓA SÂU METADATA (M1 Layer)
                    // Câu lệnh này tìm Class và toàn bộ "cây" thành phần của nó
                    String deepDeleteCypher =
                            // 1. Tìm đúng node Class/Enum dựa trên quan hệ InstanceOf tới MetaNode (Y hệt query snapshot)
                            "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                                    "MATCH (c {name: $name})-[:InstanceOf]->(meta) " +

                                    // 2. Tìm tất cả các thành phần phụ thuộc (Attributes, Operations, Invariants)
                                    "OPTIONAL MATCH (c)-[:HasAttribute]->(a) " +
                                    "OPTIONAL MATCH (c)-[:HasInvariant]->(inv) " +
                                    "OPTIONAL MATCH (c)-[:HasOperation]->(o) " +

                                    // 3. Tìm các thành phần con của Operation (Params, Pre, Post)
                                    "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                                    "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                                    "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                                    // 4. GOM TẤT CẢ VÀO DANH SÁCH (Tránh lỗi Cartesian Product làm sót Node)
                                    "WITH c, " +
                                    "collect(DISTINCT a) as attrs, " +
                                    "collect(DISTINCT inv) as invs, " +
                                    "collect(DISTINCT o) as ops, " +
                                    "collect(DISTINCT p) as params, " +
                                    "collect(DISTINCT pre) as pres, " +
                                    "collect(DISTINCT post) as posts " +

                                    // 5. THỰC THI XÓA LẦN LƯỢT (Dùng FOREACH để xử lý danh sách an toàn)
                                    "FOREACH (x IN attrs | DETACH DELETE x) " +
                                    "FOREACH (x IN invs | DETACH DELETE x) " +
                                    "FOREACH (x IN params | DETACH DELETE x) " +
                                    "FOREACH (x IN pres | DETACH DELETE x) " +
                                    "FOREACH (x IN posts | DETACH DELETE x) " +
                                    "FOREACH (x IN ops | DETACH DELETE x) " + // Xóa Operation sau khi đã dọn con của nó
                                    "DETACH DELETE c"; // Cuối cùng xóa chính Class đó

                    session.run(deepDeleteCypher, org.neo4j.driver.Values.parameters("name", label));

                    // 3. Dọn dẹp Association liên quan (Xóa link nếu tên trùng hoặc AC bị xóa)
                    session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                            org.neo4j.driver.Values.parameters("name", label));

                    WorkLogManager.getInstance().log("CLEANUP", "Deep cascade delete finished for: " + label);
                }
            } catch (Exception e) {
                System.err.println("Cascade cleanup error: " + e.getMessage());
                WorkLogManager.getInstance().log("ERROR", "Cleanup failed for " + classesToRemoveFromDb);
            }
        }
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

    //////

    private FullModelSnapshot getFullModelSnapshotFromNeo4j() {
        FullModelSnapshot fullSnapshot = new FullModelSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            Map<String, ClassState> stateMap = new HashMap<>();
            // --- PHẦN 1: QUÉT CLASSES & ENUMS (Giữ nguyên logic xử lý ClassState của bạn) ---
            String cypher =
                    // get class & enums
                    "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                            "MATCH (inst)-[:InstanceOf]->(meta) " +
                            "WITH inst, meta, labels(inst)[0] as classType " +

                            //attribute
                            "OPTIONAL MATCH (inst)-[:HasAttribute]->(a) " +
                            "OPTIONAL MATCH (a)-[r1]->(aRef) WHERE type(r1) = 'ReferenceType' " +
                            "WITH inst, meta, classType, collect(DISTINCT {name: a.attrName, type: a.type, idx: a.index, ref: aRef.name, sCollection: a.isCollection, collectionType: a.collectionType}) as attrs " +

                            //invariants
                            "OPTIONAL MATCH (inst)-[:HasInvariant]->(inv) " +
                            "WITH inst, meta, classType, attrs, collect(DISTINCT {name: inv.invName, expr: inv.expression, exist: inv.isExistential}) as invariants " +

                            //association class
                            "OPTIONAL MATCH (src)-[s:sourceAssoClass]->(inst) " +
                            "OPTIONAL MATCH (inst)-[t:targetAssoClass]->(tgt) " +
                            "WITH inst, meta, classType, attrs, invariants, " +
                            "{name: src.name, role: s.role, mult: s.multiplicity} as acSrc, " +
                            "{name: tgt.name, role: t.role, mult: t.multiplicity} as acTgt " +

                            //operation & param & pre-post
                            "OPTIONAL MATCH (inst)-[:HasOperation]->(o) " +
                            "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                            "OPTIONAL MATCH (p)-[r2]->(pRef) WHERE type(r2) = 'ReferenceType' " +
                            "OPTIONAL MATCH (o)-[r3]->(retRef) WHERE type(r3) = 'ReferenceReturnType' " +
                            "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                            "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                            "WITH inst, meta, classType, attrs, invariants, acSrc, acTgt, o, retRef, " +
                            "collect(DISTINCT {pName: p.pName, pType: p.type, pOrder: p.order, pRef: pRef.name}) as opParams, " +
                            "collect(DISTINCT {name: pre.condName, expr: pre.expression}) as opPres, " +
                            "collect(DISTINCT {name: post.condName, expr: post.expression}) as opPosts " +

                            "WITH inst, meta, classType, attrs, invariants, acSrc, acTgt, " +
                            "collect(DISTINCT {name: o.opName, ret: o.returnType, retRef: retRef.name, params: opParams, pre: opPres, post: opPosts}) as ops " +

                            "RETURN inst.name as className, meta.name as metaName, classType, inst.value as enumValues, attrs, invariants, ops, acSrc, acTgt";
            Result result = session.run(cypher);

            while (result.hasNext()) {
                org.neo4j.driver.Record row = result.next();

                ClassState cls = new ClassState();
                cls.setName(row.get("className").asString());
                cls.setType(row.get("classType").asString());
                cls.setMetaNodeName(row.get("metaName").asString());

                if ("NodeEnumeration".equals(cls.getMetaNodeName())) {
                    Value enumVals = row.get("enumValues");
                    if (!enumVals.isNull()) {
                        cls.setEnumLiterals(enumVals.asList(Value::asString));
                    }
                }

                if ("NodeAssociationClass".equals(cls.getMetaNodeName())) {
                    Value src = row.get("acSrc");
                    Value tgt = row.get("acTgt");
                    if (!src.get("name").isNull())
                        cls.setAcSource(src.get("name").asString(), src.get("role").asString(), src.get("mult").asString());
                    if (!tgt.get("name").isNull())
                        cls.setAcTarget(tgt.get("name").asString(), tgt.get("role").asString(), tgt.get("mult").asString());
                }

                Value attrsVal = row.get("attrs");
                if (!attrsVal.isNull()) {
                    for (Value aVal : attrsVal.asList(v -> v)) {
                        // Kiểm tra node rỗng (Tránh lỗi do OPTIONAL MATCH không khớp)
                        if (aVal.get("name").isNull()) continue;

                        Map<String, Object> props = new HashMap<>();

                        // Tên thực tế của thuộc tính (ví dụ: 'age', 'sex')
                        String attrName = aVal.get("name").asString();
                        props.put("attrName", attrName);

                        // Dùng làm ID duy nhất trong Map
                        props.put("name", attrName);

                        // Xử lý kiểu dữ liệu (Kiểm tra Object Reference)
                        String rawType = aVal.get("type").asString("String");
                        if ("Object".equals(rawType)) {
                            // Nếu là Object, lấy tên Class/Enum đích từ trường 'ref'
                            props.put("type", aVal.get("ref").asString("String"));
                        } else {
                            props.put("type", rawType);
                        }

                        // Thứ tự hiển thị
                        props.put("index", aVal.get("idx").asInt(0));

                        // Thông tin tập hợp (Dùng đúng key sCollection từ Cypher)
                        props.put("isCollection", aVal.get("sCollection").asBoolean(false));
                        props.put("collectionType", aVal.get("collectionType").asString("Set"));

                        // Đưa vào danh sách thuộc tính của ClassState
                        cls.getAttributes().put(attrName, props);
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
            fullSnapshot.classes = stateMap;

            // --- PHẦN 2: QUÉT REGULAR ASSOCIATIONS (MỚI) ---
            String assocCypher =
                    "MATCH (s)-[r]->(t) " +
                            "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
                            "RETURN r.associationName as name, type(r) as type, " +
                            "s.name as src, r.sourceClassrole as sRole, r.sourceMultiplicity as sMult, " +
                            "t.name as tgt, r.targerClassrole as tRole, r.targetMultiplicity as tMult";

            Result assocResult = session.run(assocCypher);
            while (assocResult.hasNext()) {
                Record rec = assocResult.next();
                AssociationState as = new AssociationState();
                as.name = rec.get("name").asString();
                as.type = rec.get("type").asString();
                as.srcName = rec.get("src").asString();
                as.srcRole = rec.get("sRole").asString("");
                as.srcMult = rec.get("sMult").asString("1");
                as.tgtName = rec.get("tgt").asString();
                as.tgtRole = rec.get("tRole").asString("");
                as.tgtMult = rec.get("tMult").asString("*");

                fullSnapshot.associations.put(as.name, as);
            }
        }
        return fullSnapshot;
    }

    public FullModelSnapshot getFullModelSnapshotFromUSE() {
        FullModelSnapshot fullSnapshot = new FullModelSnapshot();

        // 1. Duyệt Class & Enum (Giữ nguyên logic cũ của bạn)
        Map<String, ClassState> stateMap = new HashMap<>();

        // ============================================================
        // PHẦN 1: QUÉT TẤT CẢ CLASSES (CONCRETE, ABSTRACT, ASSOC-CLASS)
        // ============================================================
        for (MClass mCls : useModel.classes()) {
            ClassState cls = new ClassState();
            cls.setName(mCls.name());

            // Gán MetaNodeName chuẩn (Để so sánh và nạp M2)
            String metaName = "NodeConcreteClass";
            if (mCls instanceof MAssociationClass) metaName = "NodeAssociationClass";
            else if (mCls.isAbstract()) metaName = "NodeAbstractClass";
            cls.setMetaNodeName(metaName);

            // 1. Map Attributes
            // Trong vòng lặp map Attributes của getModelSnapshotFromUSE
            for (MAttribute attr : mCls.attributes()) {
                Map<String, Object> props = new HashMap<>();
                props.put("attrName", attr.name());
                props.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
                props.put("index", attr.getPositionInModel());

                // SỬA LỖI Ở ĐÂY: Sử dụng interface Type để kiểm tra
                org.tzi.use.uml.ocl.type.Type type = attr.type();
                boolean isColl = type.isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
                props.put("isCollection", isColl);

                if (isColl) {
                    // Xác định loại tập hợp dựa trên các hàm isKindOf...
                    if (type.isKindOfSet(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
                        props.put("collectionType", "Set");
                    } else if (type.isKindOfOrderedSet(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
                        props.put("collectionType", "OrderedSet");
                    } else if (type.isKindOfSequence(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
                        props.put("collectionType", "Sequence");
                    } else if (type.isKindOfBag(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
                        props.put("collectionType", "Bag");
                    } else {
                        props.put("collectionType", "Set"); // Default
                    }
                } else {
                    props.put("collectionType", "None");
                }

                cls.getAttributes().put(attr.name(), props);
            }

            // 2. Map Operations & Constraints
            for (MOperation op : mCls.operations()) {
                Map<String, Object> opProps = new HashMap<>();
                opProps.put("returnType", UmlTypeTranslator.toDatabaseType(op.resultType()));

                // Thêm check collection cho Return Type
                boolean retColl = op.resultType() != null && op.resultType().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
                opProps.put("isReturnCollection", retColl);

                cls.getOperations().put(op.name(), opProps);

                // Map Params
                int pIdx = 0;
                for (org.tzi.use.uml.ocl.expr.VarDecl p : op.paramList()) {
                    cls.addOperationParam(op.name(), p.name(), UmlTypeTranslator.toDatabaseType(p.type()), pIdx++);
                }

                // Map Pre/Post Conditions
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

            // 4. Map Kế thừa và AssociationClass info
            mCls.parents().forEach(p -> cls.getParents().add(p.name()));

            if (mCls instanceof MAssociationClass) {
                MAssociationClass ac = (MAssociationClass) mCls;
                cls.setAcSource(ac.associationEnds().get(0).cls().name(), ac.associationEnds().get(0).name(), ac.associationEnds().get(0).multiplicity().toString());
                cls.setAcTarget(ac.associationEnds().get(1).cls().name(), ac.associationEnds().get(1).name(), ac.associationEnds().get(1).multiplicity().toString());
            }

            stateMap.put(cls.getName(), cls);
        }

        // ============================================================
        // PHẦN 2: QUÉT TẤT CẢ ENUMERATIONS (SỬA LỖI POPUP CỦA BẠN)
        // ============================================================
        for (org.tzi.use.uml.ocl.type.EnumType e : useModel.enumTypes()) {
            ClassState enumCls = new ClassState();
            enumCls.setName(e.name());
            enumCls.setMetaNodeName("NodeEnumeration");
            enumCls.setEnumLiterals(e.getLiterals());

            fullSnapshot.classes.put(enumCls.getName(), enumCls);
        }


        // [Thay đổi stateMap.put... thành fullSnapshot.classes.put...]

        // 2. Duyệt Associations (MỚI)
        for (MAssociation mAssoc : useModel.associations()) {
            // Chỉ xử lý association thường, bỏ qua AssociationClass vì nó đã nằm trong danh sách Class
            if (mAssoc instanceof MAssociationClass) continue;

            AssociationState as = new AssociationState();
            as.name = mAssoc.name();

            MAssociationEnd srcEnd = mAssoc.associationEnds().get(0);
            MAssociationEnd tgtEnd = mAssoc.associationEnds().get(1);

            as.srcName = srcEnd.cls().name();
            as.srcRole = srcEnd.name();
            as.srcMult = srcEnd.multiplicity().toString();

            as.tgtName = tgtEnd.cls().name();
            as.tgtRole = tgtEnd.name();
            as.tgtMult = tgtEnd.multiplicity().toString();

            // Xác định type dựa trên aggregationKind (0: None, 1: Aggregation, 2: Composition)
            int kind = tgtEnd.aggregationKind();
            as.type = (kind == 2) ? "ComposeOf" : (kind == 1 ? "Aggregates" : "AssociateWith");

            fullSnapshot.associations.put(as.name, as);
        }

        return fullSnapshot;
    }

    /**
     * HÀM ĐỒNG BỘ THỜI GIAN THỰC (JAVA -> NEO4J)
     * Tự động dọn dẹp và cập nhật không cần tương tác người dùng.
     */
    public void syncForwardRealtime() {
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        // 1. Thử lấy Lock. Trong Real-time, nếu không lấy được Lock (do người khác đang giữ),
        // ta im lặng bỏ qua để không chặn đứng luồng làm việc của UI.
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if (!lock.success) {
            return;
        }

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            // 2. So sánh trạng thái hiện tại
            ModelDiff diff = compareWithNeo4j();

            // 3. TỰ ĐỘNG DỌN DẸP (Nếu có sự khác biệt)
            if (diff.hasDifference()) {

                // A. Xóa Association không còn tồn tại
                if (!diff.neo4jOnlyAssociations.isEmpty()) {
                    for (String assocName : diff.neo4jOnlyAssociations) {
                        session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                                Values.parameters("name", assocName));
                    }
                }

                // B. Xóa Class/Enum không còn tồn tại (Chiến lược C1 tự động)
                if (!diff.neo4jOnlyClasses.isEmpty()) {
                    for (String label : diff.neo4jOnlyClasses) {
                        // 1. Xóa các thực thể Object (M0) của Class này
                        // Ví dụ: Xóa tất cả các node có nhãn :Person
                        session.run("MATCH (n:`" + label + "`) DETACH DELETE n");

                        // 2. XÓA SÂU ĐỊNH NGHĨA MODEL (M1): Cascade Delete
                        // Tìm Class và tất cả các node Attribute, Operation, Invariant, Param nối với nó
                        String deepDeleteCypher =
                                // 1. Tìm đúng node Class/Enum dựa trên quan hệ InstanceOf tới MetaNode (Y hệt query snapshot)
                                "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                                        "MATCH (c {name: $name})-[:InstanceOf]->(meta) " +

                                        // 2. Tìm tất cả các thành phần phụ thuộc (Attributes, Operations, Invariants)
                                        "OPTIONAL MATCH (c)-[:HasAttribute]->(a) " +
                                        "OPTIONAL MATCH (c)-[:HasInvariant]->(inv) " +
                                        "OPTIONAL MATCH (c)-[:HasOperation]->(o) " +

                                        // 3. Tìm các thành phần con của Operation (Params, Pre, Post)
                                        "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                                        "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                                        "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                                        // 4. GOM TẤT CẢ VÀO DANH SÁCH (Tránh lỗi Cartesian Product làm sót Node)
                                        "WITH c, " +
                                        "collect(DISTINCT a) as attrs, " +
                                        "collect(DISTINCT inv) as invs, " +
                                        "collect(DISTINCT o) as ops, " +
                                        "collect(DISTINCT p) as params, " +
                                        "collect(DISTINCT pre) as pres, " +
                                        "collect(DISTINCT post) as posts " +

                                        // 5. THỰC THI XÓA LẦN LƯỢT (Dùng FOREACH để xử lý danh sách an toàn)
                                        "FOREACH (x IN attrs | DETACH DELETE x) " +
                                        "FOREACH (x IN invs | DETACH DELETE x) " +
                                        "FOREACH (x IN params | DETACH DELETE x) " +
                                        "FOREACH (x IN pres | DETACH DELETE x) " +
                                        "FOREACH (x IN posts | DETACH DELETE x) " +
                                        "FOREACH (x IN ops | DETACH DELETE x) " + // Xóa Operation sau khi đã dọn con của nó
                                        "DETACH DELETE c"; // Cuối cùng xóa chính Class đó

                        session.run(deepDeleteCypher, Values.parameters("name", label));

                        // 3. Dọn dẹp các Association mồ côi
                        // Nếu Association Class bị xóa, hoặc Association thường mang tên Class này bị xóa
                        session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                                Values.parameters("name", label));

                        WorkLogManager.getInstance().log("CLEANUP", "Deep deleted class and all its components: " + label);
                    }
                }

                WorkLogManager.getInstance().log("AUTO_CLEANUP", "Removed obsolete elements: " + diff.neo4jOnlyClasses);
            }

            // 4. ĐẨY DỮ LIỆU MỚI (Sử dụng hàm bridge đã viết)
            // Hàm này thực hiện MERGE nên sẽ cập nhật mismatchedClasses và tạo javaOnlyClasses
            bridge.pushModelToNeo4j();

            // 5. CẬP NHẬT PHIÊN BẢN (Mở khóa cho Object Sync)
            bridge.updateModelVersion(session, userIdentifier);

            System.out.println("Real-time Sync: Database matches USE model.");

        } catch (Exception e) {
            System.err.println("Real-time Sync Error: " + e.getMessage());
            WorkLogManager.getInstance().log("REALTIME_ERROR", e.getMessage());
        } finally {
            // 6. Giải phóng Lock
            LockManager.releaseLock();
        }
    }

}