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

    private int showModelDiffBox(JTextArea textArea) {
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 450));

        return JOptionPane.showConfirmDialog(null, scrollPane,
            "Push Model to Neo4j - Review Changes", JOptionPane.OK_CANCEL_OPTION);
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

                String actualModelName = getModelNameFromDb();

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


    public ModelDiff compareWithNeo4jObsolete() {
        ModelDiff diff = new ModelDiff();
        FullModelSnapshot neoSnap = getFullModelSnapshotFromNeo4j(null);
        FullModelSnapshot javaSnap = getFullModelSnapshotFromUSE();

        for (String className : javaSnap.classes.keySet()) {
            ClassState useCls = javaSnap.classes.get(className);
            if (!neoSnap.classes.containsKey(className)) {

                diff.javaOnlyClasses.add(className);
            } else {
                ClassState n4jCls = neoSnap.classes.get(className);
                if (!useCls.isSameAs(n4jCls)) {
                    diff.mismatchedClasses.add(className);
                    compareAttributesDeepObsolete(className, useCls, n4jCls, diff);
                }
            }
        }

        for (String className : neoSnap.classes.keySet()) {
            if (!javaSnap.classes.containsKey(className)) {
                diff.neo4jOnlyClasses.add(className);
            }
        }

        for (String name : javaSnap.associations.keySet()) {
            AssociationState jAssoc = javaSnap.associations.get(name);
            if (!neoSnap.associations.containsKey(name)) {
                diff.javaOnlyAssociations.add(name);
            } else if (!jAssoc.isSameAs(neoSnap.associations.get(name))) {
                diff.mismatchedAssociations.add(name);
            }
        }
        for (String name : neoSnap.associations.keySet()) {
            if (!javaSnap.associations.containsKey(name)) {
                diff.neo4jOnlyAssociations.add(name);
            }
        }
        return diff;
    }
    private void compareAttributesDeepObsolete(String clsName, ClassState useCls, ClassState n4jCls, ModelDiff diff) {
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
    private void deleteObjectsByLabel(String label) {
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run("MATCH (n:`" + label + "`) DETACH DELETE n");
        }
    }

    private void syncAssociationsToTargetModel(MModel targetModel, org.tzi.use.api.UseModelApi tempApi) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(
                org.neo4j.driver.SessionConfig.forDatabase(dbName))) {

            // CẬP NHẬT TRUY VẤN: Lấy thêm r.sourceKind và r.targetKind
            String cypher = "MATCH (s)-[r]->(t) " +
                    "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
                    "AND NOT coalesce(r.isTernary, false) " +
                    "RETURN r.associationName as name, s.name as src, t.name as tgt, " +
                    "r.sourceClassrole as sRole, r.sourceMultiplicity as sMult, r.sourceKind as sKind, " +
                    "r.targerClassrole as tRole, r.targetMultiplicity as tMult, r.targetKind as tKind, " +
                    "type(r) as edgeType";

            org.neo4j.driver.Result res = session.run(cypher);

            while (res.hasNext()) {
                org.neo4j.driver.Record rec = res.next();
                String assocName = rec.get("name").asString();

                if (targetModel.getAssociation(assocName) != null) continue;

                // 1. Thông tin đầu Nguồn (End 0)
                String srcName = rec.get("src").asString();
                String sRole = rec.get("sRole").asString("src_" + assocName);
                String sMult = rec.get("sMult").asString("1");
                // Lấy kind từ DB, nếu null thì mặc định 0
                int sKind = rec.get("sKind").isNull() ? 0 : rec.get("sKind").asInt();

                // 2. Thông tin đầu Đích (End 1)
                String tgtName = rec.get("tgt").asString();
                String tRole = rec.get("tRole").asString("tgt_" + assocName);
                String tMult = rec.get("tMult").asString("*");
                int tKind = rec.get("tKind").isNull() ? 0 : rec.get("tKind").asInt();

                // 3. Logic Fallback (Dành cho dữ liệu cũ chưa có trường Kind trong DB)
                if (rec.get("sKind").isNull() && rec.get("tKind").isNull()) {
                    String edgeType = rec.get("edgeType").asString();
                    if (edgeType.equals("ComposeOf")) tKind = 2;
                    else if (edgeType.equals("Aggregates")) tKind = 1;
                }

                try {
                    MClass srcCls = targetModel.getClass(srcName);
                    MClass tgtCls = targetModel.getClass(tgtName);

                    if (srcCls == null || tgtCls == null) continue;

                    // 4. THỰC THI TẠO VỚI THỨ TỰ TUYỆT ĐỐI
                    // srcName luôn ở vị trí đầu tiên (End 0), tgtName luôn ở vị trí thứ hai (End 1)
                    tempApi.createAssociation(
                            assocName,
                            srcName, sRole, sMult, sKind, // Nạp Kind thực tế của đầu 0
                            tgtName, tRole, tMult, tKind  // Nạp Kind thực tế của đầu 1
                    );

                    WorkLogManager.getInstance().log("PULL_ASSOC", "Restored: " + assocName);

                } catch (Exception e) {
                    System.err.println("Error creating assoc " + assocName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            WorkLogManager.getInstance().log("PULL_DB_ERROR", e.getMessage());
        }
    }

    private void applyChangesFromSnapshot(MModel targetModel, FullModelSnapshot fullModelSnapshot) {
        ModelPullService modelPullService = new ModelPullService();
        modelPullService.pullModelFromNeo4jAfterSnapshotComparing(targetModel, fullModelSnapshot);
    }
    private void applyChangesFromSnapshotObsolete(MModel targetModel, FullModelSnapshot fullModelSnapshot) throws Exception {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);

        //enums must be loaded first
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
            if ("NodeEnumeration".equals(dbCls.getMetaNodeName())) {
                List<String> literals = dbCls.getEnumLiterals();
                if (literals != null && !literals.isEmpty()) {
                    tempApi.createEnumeration(dbCls.getName(), literals);
                    WorkLogManager.getInstance().log("PULL_ENUM", "Successfully created Enum: " + dbCls.getName());
                }
            }
        }

        //create concrete and abstract class first
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
            if (!"NodeAssociationClass".equals(dbCls.getMetaNodeName()) && !"NodeEnumeration".equals(dbCls.getMetaNodeName())) {
                tempApi.createClass(dbCls.getName(), "NodeAbstractClass".equals(dbCls.getMetaNodeName()));
            }
        }

        //create association class
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
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
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
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


        //operation mappings
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
            String className = dbCls.getName();
            for (Map.Entry<String, Map<String, Object>> opEntry : dbCls.getOperations().entrySet()) {
                String opName = opEntry.getKey();
                Map<String, Object> opProps = opEntry.getValue();

                try {
                    // 1. Xử lý kiểu trả về
                    String rawRetType = (String) opProps.get("returnType");
                    String umlRetType = UmlTypeTranslator.toUmlTypeString(rawRetType);

                    // 2. Xử lý danh sách tham số (Giữ nguyên logic cũ đang chạy tốt)
                    List<ClassState.OpParam> paramList = dbCls.getOpParamsSorted(opName);
                    String[][] parameterArray = new String[paramList.size()][2];

                    for (int i = 0; i < paramList.size(); i++) {
                        ClassState.OpParam p = paramList.get(i);
                        String pName = p.getName();
                        if (pName == null || pName.isEmpty()) pName = "arg" + i;
                        String pType = UmlTypeTranslator.toUmlTypeString(p.getType());
                        parameterArray[i][0] = pName;
                        parameterArray[i][1] = pType;
                    }
                    // FIX: DÒNG NÀY PHẢI CÓ ĐỂ TẠO OPERATION TRONG JAVA
                    tempApi.createOperation(className, opName, parameterArray, umlRetType);

                    WorkLogManager.getInstance().log("PULL_OP", "Created Signature: " + className + "::" + opName);

                } catch (Exception e) {
                    String errMsg = String.format("Failed to define Op [%s] in Class [%s]: %s",
                            opName, className, e.getMessage());
                    System.err.println(errMsg);
                    WorkLogManager.getInstance().log("PULL_OP_ERROR", errMsg);
                }
            }
        }

        //handle extends association
        for (ClassState dbCls : fullModelSnapshot.classes.values()) {
            MClass child = targetModel.getClass(dbCls.getName());
            for (String pName : dbCls.getParents()) {
                MClass parent = targetModel.getClass(pName);
                if (child != null && parent != null) {
                    tempApi.createGeneralizationEx(child, parent);
                }
            }
        }

        // Lưu ý: Phải truyền targetModel vào hàm này để nó nạp đúng chỗ
        syncAssociationsToTargetModel(targetModel, tempApi);

        syncTernaryAssociationsFromGraph(targetModel, tempApi, fullModelSnapshot.ternaryAssociations);

        //vi body access car data cuar association nen phari load association trc
        applyOperationBodiesFromSnapshot(targetModel, fullModelSnapshot.classes);

        applyInvariantsFromSnapshot(targetModel, fullModelSnapshot.classes);

        applyOperationConstraintsFromSnapshot(targetModel, fullModelSnapshot.classes);
    }

    private void syncTernaryAssociationsFromGraph(MModel targetModel, UseModelApi tempApi, List<Map<String, Object>> ternaryList) {
        for (Map<String, Object> ternaryData : ternaryList) {
            String assocName = (String) ternaryData.get("name");
            List<Object> rawParticipants = (List<Object>) ternaryData.get("participants");

            List<Map<String, Object>> participants = new ArrayList<>();
            for (Object o : rawParticipants) participants.add((Map<String, Object>) o);

            participants.sort(Comparator.comparingInt(p -> ((Number) p.get("idx")).intValue()));

            int n = participants.size();
            String[] classNames = new String[n];
            String[] roleNames = new String[n];
            String[] multiplicities = new String[n];
            int[] aggregationKinds = new int[n];
            boolean[] orderedInfo = new boolean[n];

            for (int i = 0; i < n; i++) {
                Map<String, Object> p = participants.get(i);
                classNames[i] = (String) p.get("cls");
                roleNames[i] = (String) p.get("role");
                multiplicities[i] = (String) p.get("mult");

                String edgeType = (String) p.get("type");
                aggregationKinds[i] = edgeType.equals("ComposeOf") ? 2 : (edgeType.equals("Aggregates") ? 1 : 0);
                orderedInfo[i] = false;
            }

            try {
                tempApi.createAssociation(assocName, classNames, roleNames, multiplicities, aggregationKinds, orderedInfo, new String[0][][]);
                WorkLogManager.getInstance().log("PULL_TERNARY", "Restored: " + assocName + " with " + n + " ends.");
            } catch (Exception e) {
                System.err.println("N-ary Assoc Fail [" + assocName + "]: " + e.getMessage());
            }
        }
    }
    private void applyOperationBodiesFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) {
        for (ClassState dbCls : dbSnapshot.values()) {
            String className = dbCls.getName();
            MClass useCls = targetModel.getClass(className);
            if (useCls == null) continue;

            for (Map.Entry<String, Map<String, Object>> opEntry : dbCls.getOperations().entrySet()) {
                String opName = opEntry.getKey();
                Map<String, Object> opProps = opEntry.getValue();
                String body = (String) opProps.get("body");
                boolean isQuery = (boolean) opProps.get("isQuery");

                if (body == null || body.trim().isEmpty() || body.equals("null")) continue;

                MOperation useOp = useCls.operation(opName, false);

                if (useOp != null) {
                    compileOpBodySafe(targetModel, useCls, useOp, body, isQuery);
                }
            }
        }
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
     * Trích xuất tham số từ đối tượng MOperation có sẵn sang mảng String[][]
     */
    private String[][] buildParamArrayFromUSEOperation(MOperation op) {
        org.tzi.use.uml.ocl.expr.VarDeclList varList = op.paramList();
        String[][] params = new String[varList.size()][2];

        for (int i = 0; i < varList.size(); i++) {
            org.tzi.use.uml.ocl.expr.VarDecl var = varList.varDecl(i);
            params[i][0] = var.name();
            // Lấy tên kiểu dữ liệu (Sử dụng shortName() từ interface Type của USE)
            params[i][1] = var.type().shortName();
        }
        return params;
    }
    /**
     * Helper nạp Invariants (Tách riêng cho gọn)
     */
    private void applyInvariantsFromSnapshot(MModel targetModel, Map<String, ClassState> dbSnapshot) {
        org.tzi.use.api.UseModelApi tempApi = new org.tzi.use.api.UseModelApi(targetModel);
        for (ClassState dbCls : dbSnapshot.values()) {
            for (Map<String, Object> invProps : dbCls.getInvariants()) {
                String invName = (String) invProps.get("name");
                String oclBody = (String) invProps.get("expr");
                boolean isExist = (boolean) invProps.get("exist");

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
            long localHash = ObjectSyncHelper.calculateModelHash(useModel);
            long remoteHash = ObjectSyncHelper.getRemoteModelHash(); // Lấy từ node ModelVersion
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

        if (choice == 0) {
            try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
                for (String label : classesToRemoveFromDb) {

                    session.run("MATCH (n:`" + label + "`) DETACH DELETE n");

                    String deepDeleteCypher =
                            "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                                    "MATCH (c {name: $name})-[:InstanceOf]->(meta) " +

                                    "OPTIONAL MATCH (c)-[:HasAttribute]->(a) " +
                                    "OPTIONAL MATCH (c)-[:HasInvariant]->(inv) " +
                                    "OPTIONAL MATCH (c)-[:HasOperation]->(o) " +

                                    "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                                    "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                                    "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                                    "WITH c, " +
                                    "collect(DISTINCT a) as attrs, " +
                                    "collect(DISTINCT inv) as invs, " +
                                    "collect(DISTINCT o) as ops, " +
                                    "collect(DISTINCT p) as params, " +
                                    "collect(DISTINCT pre) as pres, " +
                                    "collect(DISTINCT post) as posts " +

                                    "FOREACH (x IN attrs | DETACH DELETE x) " +
                                    "FOREACH (x IN invs | DETACH DELETE x) " +
                                    "FOREACH (x IN params | DETACH DELETE x) " +
                                    "FOREACH (x IN pres | DETACH DELETE x) " +
                                    "FOREACH (x IN posts | DETACH DELETE x) " +
                                    "FOREACH (x IN ops | DETACH DELETE x) " +
                                    "DETACH DELETE c";

                    session.run(deepDeleteCypher, org.neo4j.driver.Values.parameters("name", label));

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

    public String getQuickSummary() {
        try {
            if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
                return "No Connection";
            }

            if (isQuickSyncCheck()) return "All Synced";

            ModelDiff diff = modelSnapshotsAnalyzer.compareWithNeo4j();
            if (diff == null || !diff.hasDifference()) return "All Synced";

            return String.format("%d New, %d Missing, %d Mismatch",
                    diff.javaOnlyClasses.size(),
                    diff.neo4jOnlyClasses.size(),
                    diff.mismatchedClasses.size());
        } catch (Exception e) {
            return "Check Failed";
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

    public FullModelSnapshot getFullModelSnapshotFromUSE() {
        USEModelSnapshotUtils useModelSnapshotUtils = new USEModelSnapshotUtils(this.useModel);
        return useModelSnapshotUtils.getFullModelSnapshotFromUSE();

    }

    public FullModelSnapshot getFullModelSnapshotFromUSEObsolete() {
        FullModelSnapshot fullSnapshot = new FullModelSnapshot();
        Map<String, ClassState> stateMap = new HashMap<>();

        for (MClass mCls : useModel.classes()) {
            ClassState cls = new ClassState();
            cls.setName(mCls.name());

            String metaName = "NodeConcreteClass";
            if (mCls instanceof MAssociationClass) metaName = "NodeAssociationClass";
            else if (mCls.isAbstract()) metaName = "NodeAbstractClass";
            cls.setMetaNodeName(metaName);

            for (MAttribute attr : mCls.attributes()) {
                Map<String, Object> props = new HashMap<>();
                props.put("attrName", attr.name());
                props.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
                props.put("index", attr.getPositionInModel());

                org.tzi.use.uml.ocl.type.Type type = attr.type();
                boolean isColl = type.isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
                props.put("isCollection", isColl);

                if (isColl) {
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

            for (MOperation op : mCls.operations()) {
                Map<String, Object> opProps = new HashMap<>();
                opProps.put("returnType", UmlTypeTranslator.toDatabaseType(op.resultType()));


                boolean retColl = op.resultType() != null && op.resultType().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID);
                opProps.put("isReturnCollection", retColl);

                cls.getOperations().put(op.name(), opProps);

                int pIdx = 0;
                for (org.tzi.use.uml.ocl.expr.VarDecl p : op.paramList()) {
                    cls.addOperationParam(op.name(), p.name(), UmlTypeTranslator.toDatabaseType(p.type()), pIdx++);
                }

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

            for (MClassInvariant inv : useModel.classInvariants(mCls)) {
                Map<String, Object> invMap = new HashMap<>();
                invMap.put("name", inv.name());
                invMap.put("expr", inv.bodyExpression().toString());
                invMap.put("exist", inv.isExistential());
                cls.addInvariant(invMap);
            }

            mCls.parents().forEach(p -> cls.getParents().add(p.name()));

            if (mCls instanceof MAssociationClass) {
                MAssociationClass ac = (MAssociationClass) mCls;
                cls.setAcSource(ac.associationEnds().get(0).cls().name(), ac.associationEnds().get(0).name(), ac.associationEnds().get(0).multiplicity().toString());
                cls.setAcTarget(ac.associationEnds().get(1).cls().name(), ac.associationEnds().get(1).name(), ac.associationEnds().get(1).multiplicity().toString());
            }

            stateMap.put(cls.getName(), cls);
        }

        for (org.tzi.use.uml.ocl.type.EnumType e : useModel.enumTypes()) {
            ClassState enumCls = new ClassState();
            enumCls.setName(e.name());
            enumCls.setMetaNodeName("NodeEnumeration");
            enumCls.setEnumLiterals(e.getLiterals());

            fullSnapshot.classes.put(enumCls.getName(), enumCls);
        }

        for (MAssociation mAssoc : useModel.associations()) {
            //association class has already been handled in class part
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

            int kind = tgtEnd.aggregationKind();
            as.type = (kind == 2) ? "ComposeOf" : (kind == 1 ? "Aggregates" : "AssociateWith");

            fullSnapshot.associations.put(as.name, as);
        }

        return fullSnapshot;
    }


    /**
     * Thực hiện Pull dữ liệu từ DB về Java mà không hiển thị bảng Diff Preview.
     * Dùng cho các quy trình tự động hoặc đã được phê duyệt qua Voting.
     */
    public void syncBackwardSilent() {
        try {
            FullModelSnapshot dbSnapshot = getFullModelSnapshotFromNeo4j(null);
            if (dbSnapshot.isEmpty()) return;

            String actualModelName = getModelNameFromDb();

            org.tzi.use.uml.mm.ModelFactory factory = new org.tzi.use.uml.mm.ModelFactory();
            org.tzi.use.uml.mm.MModel newModel = factory.createModel(actualModelName);

            ModelPullService modelPullService = new ModelPullService();
            modelPullService.pullModelFromNeo4jAfterSnapshotComparing(newModel, dbSnapshot);

            org.tzi.use.uml.sys.MSystem newSystem = new org.tzi.use.uml.sys.MSystem(newModel);
            useSession.setSystem(newSystem);


        } catch (Exception e) {
            e.printStackTrace();
        }
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