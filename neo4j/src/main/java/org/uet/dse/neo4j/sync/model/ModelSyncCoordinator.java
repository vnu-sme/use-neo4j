package org.uet.dse.neo4j.sync.model;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.tzi.use.api.UseModelApi;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.parser.Context;
import org.tzi.use.parser.Symtable;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.util.NullPrintWriter;
import org.uet.dse.neo4j.gui.ModelExplorerDialog;
import org.uet.dse.neo4j.manager.Neo4jAuditLogService;
import org.uet.dse.neo4j.model.AssociationState;
import org.uet.dse.neo4j.model.ClassState;
import org.uet.dse.neo4j.model.FullModelSnapshot;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.WorkLogManager;
import org.uet.dse.neo4j.sync.lock.LockManager;
import org.uet.dse.neo4j.sync.helper.UmlTypeTranslator;
import org.uet.dse.neo4j.sync.model.simpleDomain.KusakabeModelDiff;
import org.uet.dse.neo4j.sync.object.ObjectSyncHelper;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

public class ModelSyncCoordinator {
    private final Session useSession;
    private final MModel useModel;

    private final MainWindow mainWindow;
    private final UseModelApi modelApi;

    private final ModelSnapshotsAnalyzer modelSnapshotsAnalyzer;

    public ModelSyncCoordinator(Session session, MainWindow mainWindow, MModel useModel, UseModelApi modelApi) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = useModel;
        this.modelApi = modelApi;
        //configured
        this.modelSnapshotsAnalyzer = new ModelIncrementalCompare(useModel);
    }

    public ModelSyncCoordinator(Session session, MainWindow mainWindow) {
        this.useSession = session;
        this.mainWindow = mainWindow;
        this.useModel = session.system().model();
        this.modelApi = new UseModelApi(session.system().model());
        this.modelSnapshotsAnalyzer = new ModelIncrementalCompare(useModel);
    }

    public ModelSyncCoordinator(Session session) {
        this.useSession = session;
        this.mainWindow = null;
        this.useModel = session.system().model();
        this.modelApi = new UseModelApi(session.system().model());
        this.modelSnapshotsAnalyzer = new ModelIncrementalCompare(useModel);
    }

    public MModel getUseModel() {
        return useModel;
    }

    public UseModelApi getModelApi() {
        return modelApi;
    }

    public ModelSnapshotsAnalyzer getModelSnapshotsAnalyzer() {
        return this.modelSnapshotsAnalyzer;
    }

    public void syncForward() {
        //get user identification
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        Neo4jAuditLogService.log("START", "MODEL_SYNC", "Global", "User initiated manual model synchronization.");

        //lock db while working
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if(!checkLock(lock)) return;
        try {
            //bug
            //ignore conflict at this point, handling over this for the incremental model update phase, good luck :)
            ModelDiff diff = modelSnapshotsAnalyzer.compareWithNeo4j();

            //assume that model name doesnt matter, I mean we must assure that the model we are going to push to neo4j has unique name, otherwise it wont be synced
            if (checkConflictedModel(diff)) {
                return;
            }

            final Boolean freeForAllPushItAll = true;//baseBean.checkBlankWorkingSpace();

            if (freeForAllPushItAll == null) {
                JOptionPane.showMessageDialog(null, "Bug");
                return;
            }
            if (freeForAllPushItAll) {
                //overwrite and add stuff that is in USE but not neo4j
                CoreModelPushService coreModelPushService = new CoreModelPushService(this.modelApi);
                //already handle the case of syncing forward different model without stupid overriding
                coreModelPushService.pushModelToNeo4j();
            } else {
                //just a testing feature, thats why freeForAllPushItAll is true
                IncrementalModelPushService incrementalModelPushService = new IncrementalModelPushService(this.modelApi);
                incrementalModelPushService.pushModelToNeo4j(diff, this.useModel);
            }

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

    private boolean checkConflictedModel(ModelDiff diff) {
        KusakabeModelDiff kusakabeModelDiff = (KusakabeModelDiff) diff;
        List<String> modelNames = getAllModelNamesFromDb();
        if (kusakabeModelDiff.hasConflict() && modelNames.contains(useSession.system().model().name())) {
            JOptionPane.showMessageDialog(
                null,
                "The model is in conflict.",
                "Conflict Detected",
                JOptionPane.WARNING_MESSAGE
            );
            return true;
        }
        return false;
    }

    private boolean checkLock(LockManager.LockResult lock) {
        if (!lock.success) {
            JOptionPane.showMessageDialog(null,
                "Database is currently LOCKED by:\n" + lock.owner +
                    "\nPlease wait until they finish or the lock expires.",
                "Sync Blocked", JOptionPane.WARNING_MESSAGE);

            Neo4jAuditLogService.log("LOCK_DENIED", "DATABASE", "Ticket", "Sync attempt blocked by: " + lock.owner);
            return false;
        }
        return true;
    }



    public void syncBackward() {
        ModelExplorerDialog explorer = new ModelExplorerDialog(mainWindow);
        explorer.setVisible(true);

        String selectedModelName = explorer.getSelectedModelName();
        if (selectedModelName == null) return;
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        LockManager.LockResult lock = LockManager.acquireLock(user);
        if (!lock.success) {
            JOptionPane.showMessageDialog(null, "Database is locked by: " + lock.owner);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                FullModelSnapshot fullModelSnapshot;
                if (selectedModelName.equals(ModelExplorerDialog.DEFAULT_OPTION)) {
                    mainWindow.logWriter().println("Neo4j Sync: Pulling from legacy/obsolete snapshot...");
                    fullModelSnapshot = getFullModelSnapshotFromNeo4jObsolete();
                } else {
                    fullModelSnapshot = getFullModelSnapshotFromNeo4j(selectedModelName);
                }
                Map<String, ClassState> dbSnapshot = fullModelSnapshot.classes;
                if (dbSnapshot.isEmpty()) {
                    JOptionPane.showMessageDialog(null, "No model found in Database.");
                    return;
                }

                //String actualModelName = getModelNameFromDb();

                //create new model
                ModelFactory factory = new ModelFactory();
                String modelNameToCreate = selectedModelName.equals(ModelExplorerDialog.DEFAULT_OPTION) ? "AllFromNeo4j" : selectedModelName;
                MModel newModel = factory.createModel(modelNameToCreate);

                ModelPullService modelPullService = new ModelPullService();
                modelPullService.pullModelFromNeo4jAfterSnapshotComparing(newModel, fullModelSnapshot);

                MSystem newSystem = new MSystem(newModel);
                useSession.setSystem(newSystem);

                //update & reload folder structure
                mainWindow.getModelBrowser().updateUI();
                mainWindow.logWriter().println("Neo4j Sync: Model '" + selectedModelName + "' successfully pulled.");

                JOptionPane.showMessageDialog(mainWindow, "Sync Backward Success!");

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Sync Error: " + e.getMessage());
            } finally {
                LockManager.releaseLock();
            }
        });
    }

    /**
     * Quy trình Sync Ngược: Neo4j -> Java
     */
    private String getModelNameFromDb() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(
                org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            org.neo4j.driver.Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.modelName as name");
            if (res.hasNext()) {
                return res.single().get("name").asString();
            }
        }
        return "UntitledModel";
    }

    public List<String> getAllModelNamesFromDb() {
        List<String> modelNames = new ArrayList<>();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(
            org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            String cypher = "MATCH (m:ManageModel) RETURN m.name AS name ORDER BY name ASC";

            Result res = session.run(cypher);

            while (res.hasNext()) {
                Record record = res.next();
                String name = record.get("name").asString();
                if (name != null && !name.isEmpty()) {
                    modelNames.add(name);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching model names: " + e.getMessage());
        }

        return modelNames;
    }

    private void compileOpBodySafe(MModel model, MClass cls, MOperation op, String body, boolean isQuery) {
        if (op == null || body == null || body.equals("null")) return;
        body = body.trim();
        try {
            if (isQuery) {
                // --- XỬ LÝ OCL QUERY (=) ---
                // Loại bỏ dấu ngoặc kép bọc ngoài nếu có do dữ liệu DB
                if (body.startsWith("\"") && body.endsWith("\"")) {
                    body = body.substring(1, body.length() - 1);
                }
                // --- BỘ MÁY 1: OCL COMPILER (Cho succ, pred, isFinalStep) ---
                Symtable symtable = createSymtable(model, cls, op);
                Expression expr = org.tzi.use.parser.ocl.OCLCompiler.compileExpression(
                        model, body, "OCL_Parser", org.tzi.use.util.NullPrintWriter.getInstance(), symtable);

                if (expr != null) {
                    op.setExpression(expr);
                }
            } else {
                // --- XỬ LÝ SOIL IMPERATIVE (begin...end) ---
                // ĐẶC TẢ QUAN TRỌNG: Nếu thiếu begin/end, phải bọc lại để SoilParser không lỗi 'declare'
                String soilCode = body;
                if (!soilCode.toLowerCase().startsWith("begin")) {
                    soilCode = "begin\n" + soilCode + "\nend";
                }
                // --- BỘ MÁY 2: SOIL COMPILER (Cho hàm copy có 'declare') ---
                // SOIL cần input stream để đọc khối lệnh
                java.io.InputStream is = new java.io.ByteArrayInputStream(soilCode.getBytes());
                java.io.PrintWriter errWriter = new java.io.PrintWriter(System.err);

                // 1. Tạo cây cú pháp SOIL
                org.tzi.use.parser.soil.ast.ASTStatement ast =
                        org.tzi.use.parser.soil.SoilCompiler.constructAST(is, "SOIL_Parser", errWriter, false);

                if (ast != null) {
                    // 2. Tạo ngữ cảnh thực thi SOIL
                    Context soilCtx =
                            new Context("SOIL_Context", errWriter, new org.tzi.use.uml.ocl.value.VarBindings(), null);
                    soilCtx.setModel(model);

                    // 3. Biến đổi AST thành Statement và gán vào hàm
                    org.tzi.use.uml.sys.soil.MStatement statement = ast.generateStatement(soilCtx, op);
                    op.setStatement(statement);
                    WorkLogManager.getInstance().log("PULL_SOIL", "Successfully compiled SOIL: " + op.name());
                }
            }
        } catch (Exception e) {
            System.err.println("Compilation failed for " + op.name() + ": " + e.getMessage());
        }
    }



    /**
     * Tạo bảng ký hiệu (Context) cho trình biên dịch OCL.
     * Bao gồm biến 'self' và toàn bộ tham số của hàm.
     */
    private Symtable createSymtable(MModel model, MClass cls, MOperation op) {
        // Khởi tạo bảng ký hiệu từ thư viện của tool USE
        Symtable symtable = new Symtable();

        try {
            // 1. Nạp biến 'self': Đại diện cho chính thực thể của lớp chứa hàm
            // Trong OCL, self luôn trỏ về class ngữ cảnh.
            symtable.add("self", cls, null);

            // 2. Nạp toàn bộ THAM SỐ của hàm vào ngữ cảnh
            // Việc này giúp fix lỗi "Undefined operation/variable named p"
            // vì trình biên dịch sẽ biết 'p' là một biến đã được khai báo.
            if (op != null && op.paramList() != null) {
                org.tzi.use.uml.ocl.expr.VarDeclList paramList = op.paramList();
                for (int i = 0; i < paramList.size(); i++) {
                    org.tzi.use.uml.ocl.expr.VarDecl var = paramList.varDecl(i);
                    // Nạp tên biến (ví dụ: 'p') và kiểu dữ liệu của nó (ví dụ: 'Person')
                    symtable.add(var.name(), var.type(), null);

                    // Debug để kiểm tra
                    // System.out.println("Context: Added param '" + var.name() + "' to symtable.");
                }
            }

            // 3. (Tùy chọn) Nếu bạn đang xử lý hậu điều kiện (Post-condition),
            // bạn có thể nạp thêm biến 'result' tại đây nếu hàm có kiểu trả về.

        } catch (org.tzi.use.parser.SemanticException e) {
            // Lỗi xảy ra nếu có hai biến trùng tên trong cùng một ngữ cảnh
            System.err.println("Symtable Error: " + e.getMessage());
        }

        return symtable;
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
            long localHash = ObjectSyncHelper.calculateModelHash(useModel);
            long remoteHash = ObjectSyncHelper.getRemoteModelHash(); // Lấy từ node ModelVersion
            return localHash == remoteHash;
        } catch (Exception e) {
            return false;
        }
    }

    private FullModelSnapshot getFullModelSnapshotFromNeo4j(String modelName) {
        Neo4jModelSnapshotUtils neo4JModelSnapshotUtils = new Neo4jModelSnapshotUtils();
        return neo4JModelSnapshotUtils.getFullModelSnapshotFromNeo4j(modelName);
    }

    private FullModelSnapshot getFullModelSnapshotFromNeo4jObsolete() {
        FullModelSnapshot fullSnapshot = new FullModelSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            Map<String, ClassState> stateMap = new HashMap<>();
            String cypher =
                    // get class & enums
                    "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                    "MATCH (inst)-[:InstanceOf]->(meta) " +
                    "WITH inst, meta, labels(inst)[0] as classType, inst.value as enumValues " +

                    //attribute
                    // 1. Thu thập Attributes và Sợi xích lồng nhau (Fix độ sâu vô hạn)
                    "OPTIONAL MATCH (inst)-[:HasAttribute]->(a) " +
                    "OPTIONAL MATCH (a)-[rRef]->(aRef) WHERE type(rRef) = 'ReferenceType' " +
                    // Tìm đường đi xuyên qua tất cả các tầng NestedCollection
                    "OPTIONAL MATCH p = (a)-[:HasNestedCollection*]->(n:NestedCollection) " +
                    "WITH inst, meta, classType, a, aRef, p ORDER BY length(p) DESC " +
                    // Với mỗi attribute, chỉ lấy con đường dài nhất để có đủ các tầng
                    "WITH inst, meta, classType, a, aRef, head(collect(p)) as longestPath " +
                    // Trích xuất mảng tên các Collection từ các node trong path (Bỏ qua node attribute đầu tiên)
                    "WITH inst, meta, classType, a, aRef, " +
                    "     [node in nodes(longestPath) WHERE 'NestedCollection' IN labels(node) | node.collectionName] as fullChain " +
                    "WITH inst, meta, classType, collect(DISTINCT { " +
                    "  name: a.attrName, type: a.type, idx: a.index, ref: aRef.name, " +
                    "  sCollection: a.isCollection, isNested: a.isNestedCollection, " +
                    "  collectionType: a.collectionType, chain: fullChain " +
                    "}) as attrs " +

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
                    "collect(DISTINCT {name: o.opName,  body: o.body, isQuery: o.isQuery, ret: o.returnType, retRef: retRef.name, params: opParams, pre: opPres, post: opPosts}) as ops " +

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

                        props.put("isCollection", aVal.get("sCollection").asBoolean(false));
                        props.put("collectionType", aVal.get("collectionType").asString("Set"));

                        props.put("isNestedCollection", aVal.get("isNested").asBoolean(false));

                        Value chainVal = aVal.get("chain");
                        if (chainVal.isNull()) {
                            props.put("nestedChain", new ArrayList<String>());
                        } else {
                            props.put("nestedChain", chainVal.asList(Value::asString));
                        }

                        cls.getAttributes().put(attrName, props);
                    }
                }

                //invariant null-safe
                Value invsVal = row.get("invariants");
                if (!invsVal.isNull()) {
                    for (Value invVal : invsVal.asList(v -> v)) {
                        if (invVal.get("expr").isNull()) continue;
                        Map<String, Object> invMap = new HashMap<>(invVal.asMap());
                        invMap.put("exist", invVal.get("exist").asBoolean(false));
                        cls.addInvariant(invMap);
                    }
                }

                //operations null safe
                Value opsVal = row.get("ops");
                if (!opsVal.isNull()) {
                    for (Value oVal : opsVal.asList(v -> v)) {
                        if (oVal.get("name").isNull()) continue;

                        String opName = oVal.get("name").asString();
                        Map<String, Object> opProps = new HashMap<>();
                        String finalRet = "Object".equals(oVal.get("ret").asString()) ?
                                oVal.get("retRef").asString() : oVal.get("ret").asString();
                        opProps.put("returnType", finalRet);
                        // Lấy Body và Loại hàm
                        opProps.put("body", oVal.get("body").asString(null));
                        opProps.put("isQuery", oVal.get("isQuery").asBoolean(true));

                        cls.getOperations().put(opName, opProps);

                        // params
                        Value paramsVal = oVal.get("params");
                        if (!paramsVal.isNull()) {
                            for (Value pVal : paramsVal.asList(v -> v)) {
                                if (pVal.get("pName").isNull()) continue;
                                int order = pVal.get("pOrder").asInt(0);
                                String pType = "Object".equals(pVal.get("pType").asString()) ? pVal.get("pRef").asString() : pVal.get("pType").asString();
                                cls.addOperationParam(opName, pVal.get("pName").asString(), pType, order);
                            }
                        }

                        //pre-cond
                        Value preVal = oVal.get("pre");
                        if (!preVal.isNull()) {
                            for (Value p : preVal.asList(v -> v)) {
                                if (!p.get("expr").isNull()) cls.addPreCondition(opName, new HashMap<>(p.asMap()));
                            }
                        }

                        //post-cond
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

            //generalization
            Result genRes = session.run("MATCH (c)-[:Extends]->(p) RETURN c.name as child, p.name as parent");
            while (genRes.hasNext()) {
                Record r = genRes.next();
                if (stateMap.containsKey(r.get("child").asString())) {
                    stateMap.get(r.get("child").asString()).getParents().add(r.get("parent").asString());
                }
            }
            fullSnapshot.classes = stateMap;

            //regular assoc
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

            String ternaryCypher =
                    "MATCH (c)-[r]->(hub)-[:InstanceOf]->(:MetaNode {name: 'NodeTernaryAssociation'}) " +
                            "WHERE r.isTernary = true " +
                            //Thu thập thông tin từ cạnh 'r' để phân biệt các đầu mút
                            "RETURN hub.name as assocName, " +
                            "collect({cls: c.name, role: r.sourceClassrole, mult: r.sourceMultiplicity, idx: r.index, type: type(r)}) as participants";

            Result res = session.run(ternaryCypher);
            while (res.hasNext()) {
                Record rec = res.next();
                Map<String, Object> data = new HashMap<>();
                data.put("name", rec.get("assocName").asString());
                data.put("participants", rec.get("participants").asList());
                fullSnapshot.ternaryAssociations.add(data);
            }
        }
        return fullSnapshot;
    }


    public void syncForwardIncrementalCase(MModel incrementalModel) {
        //get user identification
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        Neo4jAuditLogService.log("START", "MODEL_SYNC", "Global", "User initiated manual model synchronization.");

        //lock db while working
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if(!checkLock(lock)) return;
        try {
            //bug
            ModelDiff diff = modelSnapshotsAnalyzer.compareWithNeo4j(incrementalModel);
            //assums that model name doesnt matter
            KusakabeModelDiff kusakabeModelDiff = (KusakabeModelDiff) diff;

            if (kusakabeModelDiff.hasConflict()) {
                JOptionPane.showMessageDialog(
                    null,
                    "The model is in conflict.",
                    "Conflict Detected",
                    JOptionPane.WARNING_MESSAGE
                );
                return;
            }


            ModelSnapshotsAnalyzer baseBean = new ModelSnapshotCompareV1(this.useModel);
            Boolean freeForAllPushItAll = baseBean.checkBlankWorkingSpace();

            if (freeForAllPushItAll == null) {
                JOptionPane.showMessageDialog(null, "Bug");
                return;
            }
            if (freeForAllPushItAll) {
                //overwrite and add stuff that is in USE but not neo4j
                CoreModelPushService coreModelPushService = new CoreModelPushService(this.modelApi);
                coreModelPushService.pushModelToNeo4j();
            } else {
                IncrementalModelPushService incrementalModelPushService = new IncrementalModelPushService(this.modelApi);
                incrementalModelPushService.pushModelToNeo4j(diff, incrementalModel);

            }

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

}