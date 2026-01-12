package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.sys.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.phase2.Neo4jUmlBridge;
import org.uet.dse.neo4j.phase2.ValueMapper;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.repo.Neo4jRepository;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class SyncObjectCoordinator {
    private static long lastLocalObjectSyncTimestamp = 0;
    private final MSystem system;
    private final Neo4jRepository repo;
    private final UseSystemApi systemApi;

    public SyncObjectCoordinator(MSystem system) {
        this.system = system;
        // Sử dụng UseSystemApi để thao tác với Object State của USE
        this.systemApi = UseSystemApi.create(system, true);
        this.repo = new Neo4jRepository();
    }

    /**
     * KIỂM TRA ĐIỀU KIỆN TIÊN QUYẾT (Pre-condition)
     * Model phải đồng bộ mới cho Sync Object
     */
    private boolean isModelInSync() {
        long localHash = calculateModelHash(system.model());
        long remoteHash = getRemoteModelHash();
        return localHash == remoteHash;
    }

    /**
     * SYNC XUÔI (Java -> Neo4j)
     */
    public void syncObjectsForward(boolean isManual) {
        if (!isModelInSync()) {
            JOptionPane.showMessageDialog(null, "Model mismatch! Please sync Model first.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Chụp ảnh trạng thái hiện tại
        Map<String, ObjectState> javaSnapshot = getJavaObjectSnapshot();
        Map<String, ObjectState> neo4jSnapshot = getNeo4jObjectSnapshot();

        // So sánh để tìm Added, Deleted, Updated
        ObjectDiff diff = compareObjects(javaSnapshot, neo4jSnapshot);

        if (isManual) {
            String report = diff.generateForwardReport();
            int confirm = JOptionPane.showConfirmDialog(null, new JScrollPane(new JTextArea(report)), 
                    "Confirm Object Sync to DB", JOptionPane.OK_CANCEL_OPTION);
            if (confirm != JOptionPane.OK_OPTION) return;
        }

        // Thực thi xuống DB
        executePush(diff);
        WorkLogManager.getInstance().log("OBJECT_SYNC_FORWARD", "Successfully pushed object states.");
    }

    /**
     * THỰC THI PUSH (Java -> DB): Cập nhật trạng thái thực thể xuống đồ thị
     * Tuân thủ quy tắc: Chặn nếu Class tương ứng không tồn tại dưới Neo4j (M1 Layer)
     */
    private void executePush(ObjectDiff diff) {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.executeWrite(tx -> {

                // 1. XỬ LÝ XÓA: Những đối tượng chỉ có ở DB mà không có ở Java (Source of Truth)
                for (String objName : diff.neo4jOnlyObjects) {
                    tx.run("MATCH (n {name: $name}) DETACH DELETE n",
                            Values.parameters("name", objName));
                }

                // 2. XỬ LÝ TẠO MỚI VÀ CẬP NHẬT
                // Danh sách bao gồm các Object mới hoàn toàn và các Object bị lệch giá trị
                List<String> toPush = new ArrayList<>(diff.javaOnlyObjects);
                toPush.addAll(diff.mismatchedObjects);

                for (String objName : toPush) {
                    // Lấy dữ liệu trạng thái từ Snapshot của Java
                    ObjectState state = diff.javaSnapshot.get(objName);

                    // QUY TẮC BẢO MẬT/HỢP LỆ: Kiểm tra xem môi trường DB có thỏa mãn Object này không
                    // Tìm định nghĩa Class (M1) của Object này dưới đồ thị
                    String checkClassCypher =
                            "MATCH (meta:MetaNode {name:'NodeConcreteClass'}) " +
                                    "MATCH (cls:ConcreteClass {name: $clsName})-[:InstanceOf]->(meta) " +
                                    "RETURN cls";

                    Result res = tx.run(checkClassCypher, Values.parameters("clsName", state.className));

                    if (!res.hasNext()) {
                        // ĐẶC TẢ: "Better be chặn ngay khi tạo ở USE"
                        // Nếu Class không tồn tại dưới Neo4j, ném lỗi để Rollback toàn bộ giao dịch (Transaction)
                        throw new RuntimeException("SYNC BLOCKED: Object '" + objName +
                                "' cannot be pushed because its class '" + state.className +
                                "' does not exist in Neo4j. Please sync Model first!");
                    }

                    // 3. THỰC THI UPSERT: Nếu kiểm tra hợp lệ, tiến hành đẩy dữ liệu
                    // Hàm này sẽ MERGE Node Object và cập nhật HAS_VALUE / REFERENCES
                    pushSingleObject(tx, state);
                }

                return null; // Kết thúc Transaction thành công
            });

            // 4. SAU KHI PUSH THÀNH CÔNG: Cập nhật timestamp trên server để đồng bộ Dashboard
            Neo4jUmlBridge.updateObjectVersionOnServer();

            WorkLogManager.getInstance().log("OBJECT_PUSH_SUCCESS",
                    (diff.javaOnlyObjects.size()) + " created, " + diff.mismatchedObjects.size() + " updated.");

        } catch (Exception e) {
            WorkLogManager.getInstance().log("OBJECT_PUSH_FAILED", e.getMessage());
            JOptionPane.showMessageDialog(null, e.getMessage(), "Sync Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void pushSingleObject(TransactionContext tx, ObjectState state) {
        // MERGE Object Node
        tx.run(String.format("MATCH (cls {name: $clsName}) MERGE (obj:`%s` {name: $name}) MERGE (obj)-[:InstanceOf]->(cls)", 
                state.className), Values.parameters("clsName", state.className, "name", state.name));

        // Sync Primitive Values
        for (Map.Entry<String, Object> entry : state.primitiveValues.entrySet()) {
            tx.run("MATCH (obj {name: $objName}), (attr:Attribute {name: $attrId}) " +
                   "MERGE (obj)-[r:HAS_VALUE]->(attr) SET r.value = $val",
                   Values.parameters("objName", state.name, "attrId", state.className + "_" + entry.getKey(), "val", entry.getValue()));
        }

        // Sync References
        for (Map.Entry<String, List<String>> entry : state.objectReferences.entrySet()) {
            // Xóa cũ nối mới (Overwrite)
            tx.run("MATCH (s {name: $sName})-[r:REFERENCES {role: $role}]->() DELETE r", 
                   Values.parameters("sName", state.name, "role", entry.getKey()));
            
            for (String targetName : entry.getValue()) {
                tx.run("MATCH (s {name: $sName}), (t {name: $tName}) MERGE (s)-[:REFERENCES {role: $role}]->(t)",
                       Values.parameters("sName", state.name, "tName", targetName, "role", entry.getKey()));
            }
        }
    }

    public static long calculateModelHash(org.tzi.use.uml.mm.MModel model) {
        // Tạo một chuỗi đại diện cho cấu trúc Model và lấy HashCode
        StringBuilder sb = new StringBuilder();
        // Sắp xếp tên class để đảm bảo tính nhất quán
        model.classes().stream()
                .sorted(Comparator.comparing(org.tzi.use.uml.mm.MModelElement::name))
                .forEach(cls -> {
                    sb.append("Class:").append(cls.name()).append(cls.isAbstract());
                    cls.allAttributes().forEach(a -> sb.append("Attr:").append(a.name()).append(a.type().toString()));
                    cls.allOperations().forEach(o -> sb.append("Op:").append(o.name()));
                });
        return (long) sb.toString().hashCode();
    }

    public Map<String, ObjectState> getJavaObjectSnapshot() {
        Map<String, ObjectState> snapshot = new HashMap<>();
        org.tzi.use.uml.sys.MSystemState state = system.state();

        for (org.tzi.use.uml.sys.MObject obj : state.allObjects()) {
            ObjectState os = new ObjectState();
            os.name = obj.name();
            os.className = obj.cls().name();

            Map<org.tzi.use.uml.mm.MAttribute, org.tzi.use.uml.ocl.value.Value> values = obj.state(state).attributeValueMap();
            for (Map.Entry<org.tzi.use.uml.mm.MAttribute, org.tzi.use.uml.ocl.value.Value> entry : values.entrySet()) {
                org.tzi.use.uml.mm.MAttribute attr = entry.getKey();
                Object mappedValue = org.uet.dse.neo4j.phase2.ValueMapper.mapUseValue(entry.getValue());

                if (attr.type().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID) ||
                        (attr.type().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID) &&
                                ((org.tzi.use.uml.ocl.type.CollectionType)attr.type()).elemType().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID))) {
                    // Là tham chiếu Object
                    if (mappedValue instanceof List) os.objectReferences.put(attr.name(), (List<String>) mappedValue);
                    else if (mappedValue != null) os.objectReferences.put(attr.name(), Collections.singletonList(mappedValue.toString()));
                } else {
                    // Là giá trị nguyên thủy
                    os.primitiveValues.put(attr.name(), mappedValue);
                }
            }
            snapshot.put(os.name, os);
        }
        return snapshot;
    }

    public Map<String, ObjectState> getNeo4jObjectSnapshot() {
        Map<String, ObjectState> snapshot = new HashMap<>();
        String dbName = org.uet.dse.neo4j.manager.Neo4jDriverManager.getInstance().getActiveDatabase();

        try (org.neo4j.driver.Session session = org.uet.dse.neo4j.manager.Neo4jDriverManager.getInstance().getDriver().session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            // Query lấy tất cả Object (M0), giá trị (HAS_VALUE) và tham chiếu (REFERENCES)
            String cypher =
                    "MATCH (meta:MetaNode {name: 'NodeConcreteClass'}) " +
                            "MATCH (cls:ConcreteClass)-[:InstanceOf]->(meta) " +
                            "MATCH (obj)-[:InstanceOf]->(cls) " +
                            "OPTIONAL MATCH (obj)-[hv:HAS_VALUE]->(attr) " +
                            "OPTIONAL MATCH (obj)-[ref:REFERENCES]->(tgt) " +
                            "RETURN obj.name as name, cls.name as className, " +
                            "collect(DISTINCT {attr: attr.attrName, val: hv.value}) as prims, " +
                            "collect(DISTINCT {role: ref.role, target: tgt.name}) as refs";

            org.neo4j.driver.Result result = session.run(cypher);
            while (result.hasNext()) {
                org.neo4j.driver.Record row = result.next();
                ObjectState os = new ObjectState();
                os.name = row.get("name").asString();
                os.className = row.get("className").asString();

                // Parse Primitive Values
                for (Object p : row.get("prims").asList()) {
                    Map<String, Object> m = (Map<String, Object>) p;
                    if (m.get("attr") != null) os.primitiveValues.put((String)m.get("attr"), m.get("val"));
                }

                // Parse References
                for (Object r : row.get("refs").asList()) {
                    Map<String, Object> m = (Map<String, Object>) r;
                    if (m.get("role") != null) {
                        os.objectReferences.computeIfAbsent((String)m.get("role"), k -> new ArrayList<>()).add((String)m.get("target"));
                    }
                }
                snapshot.put(os.name, os);
            }
        }
        return snapshot;
    }

    public ObjectDiff compareObjects(Map<String, ObjectState> javaSnap, Map<String, ObjectState> neoSnap) {
        ObjectDiff diff = new ObjectDiff();
        diff.javaSnapshot = javaSnap;
        diff.neo4jSnapshot = neoSnap;

        // 1. Duyệt qua các đối tượng phía Java (USE)
        for (String name : javaSnap.keySet()) {
            if (!neoSnap.containsKey(name)) {
                // Chỉ có ở Java -> Máy mình đang thừa hoặc chưa Push
                diff.javaOnlyObjects.add(name);
            } else {
                // Có ở cả hai, thực hiện so sánh sâu (Deep Comparison)
                ObjectState javaObj = javaSnap.get(name);
                ObjectState neoObj = neoSnap.get(name);

                if (!javaObj.isSameAs(neoObj)) {
                    // Có sai lệch về giá trị hoặc tham chiếu
                    diff.mismatchedObjects.add(name);

                    // Thu thập chi tiết lý do sai lệch để hiện lên Dashboard
                    List<String> details = findObjectMismatchDetails(javaObj, neoObj);
                    diff.mismatchDetails.put(name, details);
                }
            }
        }

        // 2. Duyệt qua các đối tượng phía Neo4j (DB)
        for (String name : neoSnap.keySet()) {
            if (!javaSnap.containsKey(name)) {
                // Chỉ có ở Neo4j -> DB đang thừa hoặc máy mình chưa Pull
                diff.neo4jOnlyObjects.add(name);
            }
        }

        return diff;
    }

    /**
     * Hàm phụ trợ: Tìm ra chính xác thuộc tính nào hoặc tham chiếu nào bị lệch
     */
    private List<String> findObjectMismatchDetails(ObjectState javaObj, ObjectState neoObj) {
        List<String> details = new ArrayList<>();

        // So sánh các thuộc tính nguyên thủy (Primitive Attributes)
        for (String attr : javaObj.primitiveValues.keySet()) {
            Object vJava = javaObj.primitiveValues.get(attr);
            Object vNeo = neoObj.primitiveValues.get(attr);

            if (vNeo == null) {
                details.add("Attribute '" + attr + "' is missing in DB");
            } else if (!vJava.toString().equals(vNeo.toString())) {
                details.add("Attr '" + attr + "' mismatch: Java(" + vJava + ") vs DB(" + vNeo + ")");
            }
        }

        // So sánh các tham chiếu đối tượng (Object References)
        for (String role : javaObj.objectReferences.keySet()) {
            List<String> refsJava = javaObj.objectReferences.get(role);
            List<String> refsNeo = neoObj.objectReferences.get(role);

            if (refsNeo == null || !refsJava.equals(refsNeo)) {
                details.add("Reference '" + role + "' has different targets");
            }
        }

        return details;
    }

    public static long getRemoteModelHash() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().getDriver().session(org.neo4j.driver.SessionConfig.forDatabase(dbName))) {
            org.neo4j.driver.Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.modelHash AS hash");
            if (res.hasNext()) return res.single().get("hash").asLong();
        }
        return -1;
    }

    /**
     * Cập nhật lại thời gian local sau khi Push/Pull thành công
     */
    public static void updateLocalSyncTimestamp(long ts) {
        lastLocalObjectSyncTimestamp = ts;
    }

    public boolean isQuickSyncCheck() {
        try {
            // 1. Lấy thời điểm thay đổi Object gần nhất trên Neo4j (do bất kỳ ai làm)
            long remoteTs = repo.getRemoteObjectTimestamp();

            // 2. Nếu remoteTs = 0 nghĩa là DB chưa bao giờ có object nào
            if (remoteTs == 0) return true;

            // 3. So sánh: Nếu remote mới hơn local -> Đã lỗi thời (Out of sync)
            return lastLocalObjectSyncTimestamp == remoteTs;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SYNC NGƯỢC: Neo4j -> Java (USE System State)
     * @param isManual Nếu true sẽ hiện Pop-up thống kê trước khi nạp
     */
    /**
     * SYNC NGƯỢC: Neo4j -> Java (Kéo trạng thái thực thể về USE)
     * @param isManual Nếu true sẽ hiện Pop-up thống kê trước khi nạp
     */
    public void syncObjectsBackward(boolean isManual) {
        // 1. RULE TỐI THƯỢNG: Phải đồng bộ Model trước khi đồng bộ Object (Đặc tả yêu cầu)
        if (!isModelInSync()) {
            JOptionPane.showMessageDialog(null,
                    "Model mismatch! You must PULL Model from Neo4j before syncing Objects.\n" +
                            "Model is the Source of Truth for Objects.",
                    "Sync Pre-condition Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            // 2. Chụp ảnh trạng thái 2 bên (Snapshot)
            Map<String, ObjectState> javaSnap = getJavaObjectSnapshot();
            Map<String, ObjectState> neoSnap = getNeo4jObjectSnapshot();

            // 3. So sánh khách quan (Neutral Comparison)
            ObjectDiff diff = compareObjects(javaSnap, neoSnap);

            // 4. Xử lý thông báo Manual Sync
            if (isManual) {
                // Nếu không có gì khác biệt thì báo tin vui và dừng lại
                if (!diff.hasDifference()) {
                    JOptionPane.showMessageDialog(null, "Your local objects are perfectly in sync with the Database.");
                    return;
                }

                // Hiển thị báo cáo chi tiết cho chiều NGƯỢC (Đặc tả: thống kê rõ ràng phần thiếu/thừa)
                JTextArea textArea = new JTextArea(diff.generateBackwardReport());
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 12)); // Font đều để bảng thống kê đẹp
                textArea.setEditable(false);
                JScrollPane scrollPane = new JScrollPane(textArea);
                scrollPane.setPreferredSize(new Dimension(600, 400));

                int res = JOptionPane.showConfirmDialog(null, scrollPane,
                        "Preview Object Sync (Neo4j -> USE UML)", JOptionPane.OK_CANCEL_OPTION);

                if (res != JOptionPane.OK_OPTION) {
                    WorkLogManager.getInstance().log("PULL_CANCEL", "User cancelled object pull after review.");
                    return;
                }
            }

            // 5. THỰC THI CẬP NHẬT: Đẩy thay đổi vào bộ nhớ USE (Source of Truth = DB)
            applyChangesToUSEState(diff);

            // 6. ĐỒNG BỘ TRẠNG THÁI DASHBOARD (Real-time Requirement)
            // Lấy timestamp từ server và cập nhật local để đèn báo chuyển sang XANH
            long remoteTs = repo.getRemoteObjectTimestamp();
            updateLocalSyncTimestamp(remoteTs);

            // 7. Ghi Log và thông báo thành công
            String summary = String.format("Pulled: %d new, %d removed, %d updated objects.",
                    diff.neo4jOnlyObjects.size(), diff.javaOnlyObjects.size(), diff.mismatchedObjects.size());

            WorkLogManager.getInstance().log("OBJECT_SYNC_BACKWARD_SUCCESS", summary);
            JOptionPane.showMessageDialog(null, "Object state successfully updated from Database!\n" + summary);

        } catch (Exception e) {
            WorkLogManager.getInstance().log("OBJECT_PULL_ERROR", e.getMessage());
            JOptionPane.showMessageDialog(null, "Error pulling objects: " + e.getMessage(), "Sync Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    /**
     * Cập nhật trạng thái thực thể vào USE (DB -> Java)
     * Tuân thủ quy tắc: Database là nguồn chân lý (Source of Truth)
     */
    private void applyChangesToUSEState(ObjectDiff diff) throws Exception {

        // 1. XỬ LÝ XÓA: Những đối tượng chỉ có ở Java mà không có ở DB (Rule đồng bộ triệt để)
        for (String objName : diff.javaOnlyObjects) {
            if (systemApi.getObject(objName) != null) {
                systemApi.deleteObject(objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_DELETE", "Removed object from USE: " + objName);
            }
        }

        // 2. XỬ LÝ TẠO MỚI: Những đối tượng chỉ có ở DB mà máy Java chưa có
        for (String objName : diff.neo4jOnlyObjects) {
            ObjectState dbObj = diff.neo4jSnapshot.get(objName);
            // Kiểm tra xem class của nó đã được đồng bộ Model chưa
            if (system.model().getClass(dbObj.className) != null) {
                systemApi.createObject(dbObj.className, objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_CREATE", "Created object in USE: " + objName);
            } else {
                throw new RuntimeException("Cannot pull object '" + objName + "' because Class '" +
                        dbObj.className + "' is missing in local model.");
            }
        }

        // 3. CẬP NHẬT GIÁ TRỊ: Cho cả những Object mới tạo và những Object bị lệch giá trị (Mismatched)
        List<String> toUpdate = new ArrayList<>(diff.neo4jOnlyObjects);
        toUpdate.addAll(diff.mismatchedObjects);

        for (String objName : toUpdate) {
            ObjectState dbState = diff.neo4jSnapshot.get(objName);

            // A. Cập nhật các thuộc tính nguyên thủy (Primitive Values)
            for (Map.Entry<String, Object> entry : dbState.primitiveValues.entrySet()) {
                String attrName = entry.getKey();
                Object value = entry.getValue();

                // Chuyển đổi giá trị thô từ Neo4j sang biểu thức OCL (ví dụ: 'John', 25)
                // DÙNG TRANSLATOR: Format 20.0 cho Real, 'John' cho String
                String oclExpression = UmlTypeTranslator.toOclLiteral(entry.getValue());

                systemApi.setAttributeValue(objName, entry.getKey(), oclExpression);
                try {
                    systemApi.setAttributeValue(objName, attrName, oclExpression);
                } catch (Exception e) {
                    WorkLogManager.getInstance().log("OBJECT_PULL_ERROR",
                            "Failed to set attr '" + attrName + "' for '" + objName + "': " + e.getMessage());
                }
            }

            // B. Cập nhật các tham chiếu đối tượng (Object References / Link)
            // Lưu ý: Trong UML, nếu Attribute là Object Type, ta gán bằng tên của Object đó
            for (Map.Entry<String, List<String>> entry : dbState.objectReferences.entrySet()) {
                String attrName = entry.getKey();
                List<String> targetNames = entry.getValue();

                if (!targetNames.isEmpty()) {
                    // Nếu là Single Reference, lấy phần tử đầu tiên
                    // Nếu là Collection Reference, USE API thường xử lý qua việc gán tập hợp Set{obj1, obj2}
                    String oclRefExpression = formatToOclReference(targetNames);
                    systemApi.setAttributeValue(objName, attrName, oclRefExpression);
                }
            }
        }
    }

    /**
     * Helper: Định dạng giá trị sang chuẩn OCL Expression để nạp vào USE
     */
    private String formatToOclExpression(Object val) {
        if (val == null || val.toString().equals("null")) return "Undefined";

        // Nếu là String (không phải Boolean hay Number) thì bọc trong nháy đơn
        String s = val.toString();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return s.toLowerCase();
        if (s.matches("-?\\d+(\\.\\d+)?")) return s; // Kiểm tra số (Integer/Real)

        return "'" + s + "'"; // Mặc định là String literal
    }

    /**
     * Helper: Định dạng danh sách Object Name thành tập hợp OCL (ví dụ: Set{p1, p2})
     */
    private String formatToOclReference(List<String> names) {
        if (names.isEmpty()) return "Set{}";
        if (names.size() == 1) return names.get(0); // Gán trực tiếp tên object

        StringBuilder sb = new StringBuilder("Set{");
        for (int i = 0; i < names.size(); i++) {
            sb.append(names.get(i));
            if (i < names.size() - 1) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String formatToOclExpression(String val) {
        if (val == null || val.equals("null")) return "Undefined";
        // Nếu là String (không phải số/boolean) thì bọc trong nháy đơn
        if (val.matches("-?\\d+(\\.\\d+)?") || val.equals("true") || val.equals("false")) {
            return val;
        }
        return "'" + val + "'";
    }

    /**
     * Lấy tóm tắt trạng thái đồng bộ Object để hiển thị trên Dashboard
     * Trả về chuỗi: "All Synced" hoặc "X New, Y Missing, Z Mismatch"
     */
    public String getQuickSummary() {
        try {
            // Bước 1: Kiểm tra nhanh bằng Timestamp
            // Nếu timestamp khớp, coi như đã đồng bộ hoàn toàn (Tiết kiệm tài nguyên)
            if (isQuickSyncCheck()) {
                return "All Synced";
            }

            // Bước 2: Nếu Timestamp lệch, thực hiện quét sâu (Deep Comparison) để lấy con số cụ thể
            Map<String, ObjectState> javaSnap = getJavaObjectSnapshot();
            Map<String, ObjectState> neoSnap = getNeo4jObjectSnapshot();

            ObjectDiff diff = compareObjects(javaSnap, neoSnap);

            if (!diff.hasDifference()) {
                // Trường hợp hy hữu: Timestamp lệch nhưng dữ liệu thực tế lại giống nhau
                return "All Synced";
            }

            // Trả về thống kê theo đúng đặc tả "Thống kê rõ ràng"
            return String.format("%d New, %d Missing, %d Mismatch",
                    diff.javaOnlyObjects.size(),   // Có ở Java nhưng DB không có
                    diff.neo4jOnlyObjects.size(),  // Có ở DB nhưng Java không có
                    diff.mismatchedObjects.size() // Cả hai cùng có nhưng khác giá trị
            );

        } catch (Exception e) {
            return "Check Failed";
        }
    }

    /**
     * CHỈ ĐẨY 1 OBJECT LÊN DB (JAVA -> DB)
     */
    public void pushSingleObjectByName(String objName) {
        MObject obj = system.state().objectByName(objName);
        if (obj == null) return;

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.executeWrite(tx -> {
                // 1. Chụp ảnh trạng thái của đúng Object này
                ObjectState state = captureSingleObjectState(obj);

                // 2. Đẩy lên server (Sử dụng hàm pushSingleObject đã viết ở phần executePush)
                // Lưu ý: pushSingleObject cần Transaction (tx)
                pushSingleObject(tx, state);

                return null;
            });

            // Cập nhật timestamp để dashboard nhận diện
            Neo4jUmlBridge.updateObjectVersionOnServer();
            System.out.println("SELECTIVE_PUSH_OBJ" + "Pushed object: " + objName);
        }
    }

    /**
     * Helper: Chụp ảnh trạng thái của 1 Object duy nhất
     */
    private ObjectState captureSingleObjectState(MObject obj) {
        ObjectState os = new ObjectState();
        os.name = obj.name();
        os.className = obj.cls().name();

        // Quét giá trị và tham chiếu (tương tự hàm getJavaObjectSnapshot nhưng cho 1 obj)
        var attrMap = obj.state(system.state()).attributeValueMap();
        attrMap.forEach((attr, val) -> {
            Object mapped = ValueMapper.mapUseValue(val);
            if (attr.type().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
                os.objectReferences.put(attr.name(), Collections.singletonList(mapped.toString()));
            } else {
                os.primitiveValues.put(attr.name(), mapped);
            }
        });
        return os;
    }
}