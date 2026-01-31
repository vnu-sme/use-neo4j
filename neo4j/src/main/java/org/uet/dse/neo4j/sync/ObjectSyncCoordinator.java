package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.*;
import org.uet.dse.neo4j.manager.Neo4jAuditLogService;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.phase2.Neo4jUmlBridge;
import org.uet.dse.neo4j.phase2.ValueMapper;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;
import org.uet.dse.neo4j.repo.Neo4jObjectRepository;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class ObjectSyncCoordinator {
    // Cờ chặn để phân biệt: Thay đổi do người dùng gõ lệnh hay do Plugin đang tự nạp dữ liệu
    private static boolean isApplyingInternalChange = false;
    private static long lastLocalObjectSyncTimestamp = 0;
    private final MSystem system;
    private final Neo4jModelRepository repo;

    private final Neo4jObjectRepository objectRepository;
    private final UseSystemApi systemApi;

    public ObjectSyncCoordinator(MSystem system) {
        this.system = system;
        // Sử dụng UseSystemApi để thao tác với Object State của USE
        this.systemApi = UseSystemApi.create(system, true);
        this.repo = new Neo4jModelRepository();
        this.objectRepository = new Neo4jObjectRepository();
    }

    /**
     * KIỂM TRA PHIÊN BẢN MODEL (Rule quan trọng nhất)
     * Đảm bảo Java và DB đang dùng chung 1 version Model
     */
    private boolean isModelInSync() {
        // Tính hash của model hiện tại trong Java
        long localHash = calculateModelHash(system.model());
        // Lấy hash lưu trên Node ModelVersion ở DB
        long remoteHash = objectRepository.getRemoteModelHash();

        return localHash != -1 && localHash == remoteHash;
    }

    /**
     * SYNC XUÔI (Java -> Neo4j)
     */
    public void syncObjectsForward(boolean isManual) {
        // 1. KIỂM TRA ĐIỀU KIỆN MODEL (QUY TẮC CHUNG - MODEL LÀ SOT)
        if (!isModelInSync()) {
            if (isManual) {
                // Nếu người dùng nhấn nút -> Báo lỗi để họ đi sync model trước
                handleInconsistentModel("Push Objects");
            } else {
                // Nếu là Real-time -> Chỉ in log console để không làm phiền người dùng gõ lệnh
                System.out.println("Real-time sync skipped: Model version mismatch.");
            }
            return;
        }

        // 2. CHỤP ẢNH VÀ SO SÁNH
        ObjectDiff diff = compareObjects(getJavaObjectSnapshot(), getNeo4jObjectSnapshot());

        // 3. KIỂM TRA SỰ THAY ĐỔI
        if (!diff.hasDifference()) {
            if (isManual) {
                JOptionPane.showMessageDialog(null, "Object states are already in sync.", "No Changes", JOptionPane.INFORMATION_MESSAGE);
            }
            return; // Không có gì để sync thì thoát
        }

        // 4. XỬ LÝ PREVIEW (CHỈ DÀNH CHO MANUAL)
        if (isManual) {
            if (!showSyncPreview(diff, "Forward (Java -> DB)")) {
                return; // Người dùng nhấn Cancel trong bảng Preview
            }
        }

        // 5. THỰC THI PUSH DỮ LIỆU
        try {
            // executePush đã bao gồm logic check Class tồn tại dưới DB và dọn dẹp Link rác
            executePush(diff);

            // 6. THÔNG BÁO THÀNH CÔNG (CHỈ DÀNH CHO MANUAL)
            if (isManual) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null,
                            "<html><b>Object Sync Successful!</b><br/>" +
                                    "Created: " + diff.javaOnlyObjects.size() + "<br/>" +
                                    "Updated: " + diff.mismatchedObjects.size() + "<br/>" +
                                    "Deleted from DB: " + diff.neo4jOnlyObjects.size() + "</html>",
                            "Success", JOptionPane.INFORMATION_MESSAGE);
                });
            } else {
                // Nếu tự động -> Chỉ ghi log ngầm
                System.out.println("Real-time: Successfully pushed " + diff.javaOnlyObjects.size() + " new objects.");
            }

        } catch (Exception e) {
            if (isManual) {
                JOptionPane.showMessageDialog(null, "Sync failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                System.err.println("Auto-sync error: " + e.getMessage());
                WorkLogManager.getInstance().log("REALTIME_ERROR", e.getMessage());
            }
        }
    }

    public void syncObjectsBackward(boolean isManual) {
        // 1. Kiểm tra Model Version (Model là SOT của Object)
        if (!isModelInSync()) {
            handleInconsistentModel("Pull Objects");
            return;
        }

        // 2. Chụp ảnh snapshot đầy đủ (Objects + Links)
        FullObjectSnapshot javaSnap = getJavaObjectSnapshot();
        FullObjectSnapshot neoSnap = getNeo4jObjectSnapshot();

        // 3. So sánh
        ObjectDiff diff = compareObjects(javaSnap, neoSnap);

        // 4. Manual Preview
        if (isManual) {
            if (!diff.hasDifference()) {
                JOptionPane.showMessageDialog(null, "Your local state matches the Database.");
                return;
            }
            // generateBackwardReport() bây giờ sẽ hiển thị cả Link thay đổi
            if (!showSyncPreview(diff, "Pull Objects (DB -> Java)")) return;
        }

        // 5. Thực thi nạp vào USE
        try {
            applyChangesToUSEState(diff);

            // 6. Cập nhật local timestamp để dashboard báo XANH
            long remoteTs = objectRepository.getRemoteObjectTimestamp();
            updateLocalSyncTimestamp(remoteTs);

            String summary = String.format("Sync Complete: %d objects, %d links updated.",
                    diff.neo4jOnlyObjects.size(), diff.neo4jOnlyLinks.size());
            JOptionPane.showMessageDialog(null, summary);
            WorkLogManager.getInstance().log("OBJECT_PULL_SUCCESS", summary);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Pull failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    /**
     * THỰC THI PUSH VỚI CƠ CHẾ CHẶN (Validation Rule)
     */
    public void executePush(ObjectDiff diff) {
        MSystemState state = system.state(); // Lấy state hiện hành

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.executeWrite(tx -> {
                // --- PHẦN 1: DỌN DẸP (CLEANUP) ---

                // --- BƯỚC 1: XỬ LÝ XÓA (CASCADE DELETE) ---
                for (String objName : diff.neo4jOnlyObjects) {
                    // GỌI HÀM XÓA SÂU Ở ĐÂY
                    objectRepository.deleteObjectDeeply(tx, objName);
                    WorkLogManager.getInstance().log("CLEANUP", "Deep deleted object: " + objName);
                }
                // B. Xóa Link rác (Cạnh có thuộc tính isObjectLink hoặc LinkHub)
                for (String linkName : diff.neo4jOnlyLinks) {
                    tx.run("MATCH ()-[r {name: $name}]-() WHERE type(r) STARTS WITH 'Link' DELETE r",
                            Map.of("name", linkName));
                    tx.run("MATCH (h:LinkHub {name: $name}) DETACH DELETE h",
                            Map.of("name", linkName));
                }

                // --- PHẦN 2: PUSH OBJECTS (NODES) ---
                List<String> toPushObjs = new ArrayList<>(diff.javaOnlyObjects);
                toPushObjs.addAll(diff.mismatchedObjects);

                for (String objName : toPushObjs) {
                    MObject obj = state.objectByName(objName);
                    if (!objectRepository.checkClassExistsInDb(tx, obj.cls().name())) {
                        throw new RuntimeException("Class '" + obj.cls().name() + "' missing. Push Model first!");
                    }
                    pushObject(tx, obj, state); // Hàm tạo Node Object và AttributeValue
                }

                // --- PHẦN 3: PUSH LINKS (EDGES) ---
                for (MLink link : state.allLinks()) {
                    if (link instanceof MLinkObject) {
                        processPushLinkObject(tx, (MLinkObject) link);
                    } else if (link.linkedObjects().size() > 2) {
                        processPushTernaryLink(tx, link);
                    } else {
                        processPushBinaryLink(tx, link);
                    }
                }
                return null;
            });

            Neo4jUmlBridge.updateObjectVersionOnServer();
            WorkLogManager.getInstance().log("SYNC", "Object & Link sync completed.");

        }
    }
    private void processPushBinaryLink(TransactionContext tx, MLink link) {
        MObject s = link.linkedObjects().get(0);
        MObject t = link.linkedObjects().get(1);
        MAssociationEnd endS = link.association().associationEnds().get(0);
        MAssociationEnd endT = link.association().associationEnds().get(1);

        // Xác định nhãn dựa trên aggregationKind (0:None, 1:Agg, 2:Comp)
        String label = "LinkAssociateWith";
        if (endT.aggregationKind() == 2 || endS.aggregationKind() == 2) label = "LinkComposeOf";
        else if (endT.aggregationKind() == 1 || endS.aggregationKind() == 1) label = "LinkAggregates";

        objectRepository.upsertBinaryLink(tx, s.name(), t.name(), link.association().name(),
                label, endS.name(), endT.name());
    }

    private void processPushTernaryLink(TransactionContext tx, MLink link) {
        List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < link.linkedObjects().size(); i++) {
            MObject obj = link.linkedObjects().get(i);
            MAssociationEnd end = link.association().associationEnds().get(i);

            Map<String, Object> p = new HashMap<>();
            p.put("objName", obj.name());
            p.put("role", end.name());
            p.put("index", i);
            p.put("label", "LinkAssociateWith"); // Ternary thường là Association
            participants.add(p);
        }
        objectRepository.upsertTernaryLink(tx, link.association().name(), participants);
    }

    private void processPushLinkObject(TransactionContext tx, MLinkObject lo) {
        List<Map<String, Object>> participants = new ArrayList<>();
        for (int i = 0; i < lo.linkedObjects().size(); i++) {
            Map<String, Object> p = new HashMap<>();
            p.put("objName", lo.linkedObjects().get(i).name());
            p.put("role", lo.association().associationEnds().get(i).name());
            p.put("label", "LinkAssociateWith");
            participants.add(p);
        }
        // Vừa tạo Node cho LinkObject, vừa nối cạnh tới các participant
        objectRepository.upsertLinkObject(tx, lo.name(), lo.cls().name(), participants);
    }

    private String getLinkLabel(int kindT, int kindS) {
        int maxKind = Math.max(kindT, kindS);
        if (maxKind == 2) return "LinkComposeOf";
        if (maxKind == 1) return "LinkAggregates";
        return "LinkAssociateWith";
    }

    public void pullLinks(UseSystemApi api) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            // --- 1. PULL BINARY LINKS (Quan hệ 2 bên) ---
            String binaryCypher =
                    "MATCH (a)-[r]->(b) WHERE type(r) STARTS WITH 'Link' " +
                            "AND NOT coalesce(r.isTernary, false) AND NOT coalesce(r.isLinkObjectPart, false) " +
                            "RETURN r.name as assocName, a.use_id as src, b.use_id as tgt";

            Result binRes = session.run(binaryCypher);
            while (binRes.hasNext()) {
                Record rec = binRes.next();
                try {
                    api.createLink(rec.get("assocName").asString(),
                            new String[]{rec.get("src").asString(), rec.get("tgt").asString()});
                } catch (Exception e) { /* Bỏ qua nếu link đã tồn tại */ }
            }

            // --- 2. PULL TERNARY LINKS (Quan hệ N-bên qua Hub) ---
            String ternaryCypher =
                    "MATCH (p)-[r]->(hub:LinkHub) WHERE r.isTernary = true " +
                            "RETURN hub.name as assocName, collect({obj: p.use_id, idx: r.index}) as participants";

            Result terRes = session.run(ternaryCypher);
            while (terRes.hasNext()) {
                Record rec = terRes.next();
                List<Map<String, Object>> parts = rec.get("participants").asList(v -> (Map<String, Object>)v.asMap());

                // Sắp xếp người tham gia theo đúng thứ tự index (0, 1, 2...)
                parts.sort(Comparator.comparingInt(p -> ((Number)p.get("idx")).intValue()));

                String[] objNames = new String[parts.size()];
                for(int i=0; i<parts.size(); i++) objNames[i] = (String)parts.get(i).get("obj");

                try {
                    api.createLink(rec.get("assocName").asString(), objNames);
                } catch (Exception e) { /* Log error */ }
            }

            // --- 3. PULL LINK OBJECTS (Thực thể Association Class) ---
            String loCypher =
                    "MATCH (lo)-[:ObjectInstanceOf]->(ac:AssociationClass) " +
                            "MATCH (lo)-[r]->(p) WHERE r.isLinkObjectPart = true " +
                            "RETURN lo.use_id as loName, ac.name as acName, collect(p.use_id) as participants";

            Result loRes = session.run(loCypher);
            while (loRes.hasNext()) {
                Record rec = loRes.next();
                List<String> participants = rec.get("participants").asList(org.neo4j.driver.Value::asString);
                try {
                    api.createLinkObject(rec.get("acName").asString(),
                            rec.get("loName").asString(),
                            participants.toArray(new String[0]));
                } catch (Exception e) { /* Log error */ }
            }
        }
    }

    private boolean showSyncPreview(ObjectDiff diff, String title) {
        String report = title.contains("Forward") ? diff.generateForwardReport() : diff.generateBackwardReport();
        JTextArea textArea = new JTextArea(report);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(false);
        int res = JOptionPane.showConfirmDialog(null, new JScrollPane(textArea),
                "Sync Preview: " + title, JOptionPane.OK_CANCEL_OPTION);
        return res == JOptionPane.OK_OPTION;
    }

    private void handleInconsistentModel(String action) {
        JOptionPane.showMessageDialog(null,
                "<html><b>" + action + " Failed!</b><br/>" +
                        "Your local Model version does not match Database.<br/>" +
                        "<i>Reason: Model is the Source of Truth for Objects.</i><br/>" +
                        "Please perform a <b>Model Sync</b> first.</html>",
                "Version Conflict", JOptionPane.WARNING_MESSAGE);
    }

    private void pushSingleObject(TransactionContext tx, ObjectState state) {
        // 1. Tạo node thực thể chính (vd: :Person {use_id: 'p1'})
        objectRepository.upsertObjectNode(tx, state.name, state.className);

        // Tập hợp tất cả các thuộc tính để duyệt
        Set<String> allAttrNames = new HashSet<>();
        allAttrNames.addAll(state.primitiveValues.keySet());
        allAttrNames.addAll(state.objectReferences.keySet());

        for (String attrName : allAttrNames) {
            String actualType = state.attributeTypes.get(attrName);
            Object value = state.primitiveValues.containsKey(attrName) ?
                    state.primitiveValues.get(attrName) : state.objectReferences.get(attrName);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", actualType); // LƯU KIỂU THỰC TẾ: "Int", "Sex", v.v.
            metadata.put("isCollection", value instanceof List);

            // Bạn có thể lấy thêm collectionType từ Model nếu muốn chi tiết hơn (Set/Sequence)
            metadata.put("collectionType", (value instanceof List) ? "Collection" : "None");

            // Đẩy xuống Repo để tạo node AttributeValue
            objectRepository.setAttributeValueNode(tx, state.name, state.className, attrName, value, metadata);
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

    public FullObjectSnapshot getJavaObjectSnapshot() {
        FullObjectSnapshot fullSnapshot = new FullObjectSnapshot();
        MSystemState state = system.state();

        // 1. QUÉT TẤT CẢ OBJECTS (Tầng M0)
        for (MObject obj : state.allObjects()) {
            ObjectState os = new ObjectState();
            os.name = obj.name();
            os.className = obj.cls().name();

            Map<MAttribute, Value> values = obj.state(state).attributeValueMap();
            for (Map.Entry<MAttribute, Value> entry : values.entrySet()) {
                MAttribute attr = entry.getKey();
                Value useValue = entry.getValue();

                // Lưu kiểu dữ liệu thực tế (M1) vào ObjectState (M0)
                String actualType = UmlTypeTranslator.toDatabaseType(attr.type());
                os.attributeTypes.put(attr.name(), actualType);

                Object mappedValue = ValueMapper.mapUseValue(useValue);

                // Phân loại: Tham chiếu đối tượng hay Giá trị nguyên thủy
                if (attr.type().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID) ||
                        (attr.type().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID) &&
                                ((org.tzi.use.uml.ocl.type.CollectionType)attr.type()).elemType().isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID))) {

                    if (mappedValue instanceof List) os.objectReferences.put(attr.name(), (List<String>) mappedValue);
                    else if (mappedValue != null) os.objectReferences.put(attr.name(), Collections.singletonList(mappedValue.toString()));
                } else {
                    os.primitiveValues.put(attr.name(), mappedValue);
                }
            }
            fullSnapshot.objects.put(os.name, os);
        }

        // 2. QUÉT TẤT CẢ LINKS (Binary, Ternary, LinkObjects)
        for (MLink link : state.allLinks()) {
            LinkState ls = new LinkState();
            ls.assocName = link.association().name();
            link.linkedObjects().forEach(o -> ls.participants.add(o.name()));

            if (link instanceof MLinkObject) {
                ls.linkObjectName = ((MLinkObject) link).name();
                ls.edgeLabel = "LinkAssociateWith"; // Mặc định cho LinkObject
            } else if (link.linkedObjects().size() > 2) {
                ls.isTernary = true;
                ls.edgeLabel = "LinkAssociateWith";
            } else {
                // Binary Link: Xác định nhãn dựa trên AggregationKind
                MAssociationEnd end1 = link.association().associationEnds().get(0);
                MAssociationEnd end2 = link.association().associationEnds().get(1);
                int maxKind = Math.max(end1.aggregationKind(), end2.aggregationKind());
                ls.edgeLabel = (maxKind == 2) ? "LinkComposeOf" : (maxKind == 1 ? "LinkAggregates" : "LinkAssociateWith");
            }
            fullSnapshot.links.put(ls.getIdentity(), ls);
        }

        return fullSnapshot;
    }

    public FullObjectSnapshot getNeo4jObjectSnapshot() {
        FullObjectSnapshot fullSnapshot = new FullObjectSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {

            // --- PHẦN 1: QUÉT OBJECTS & ATTRIBUTE NODES ---
            String objCypher =
                    "MATCH (o)-[:ObjectInstanceOf]->(cls) " +
                            "OPTIONAL MATCH (o)-[:ObjectHasAttribute]->(val:AttributeValue)-[:InstanceOf]->(attrDef) " +
                            "OPTIONAL MATCH (val)-[r:objectReference]->(target) " +
                            "RETURN o.use_id as name, cls.name as className, " +
                            "collect({ " +
                            "  attr: attrDef.attrName, " +
                            "  type: val.type, " +
                            "  value: val.value, " +
                            "  ref: target.use_id, " +
                            "  idx: r.index " +
                            "}) as data";

            Result objRes = session.run(objCypher);
            while (objRes.hasNext()) {
                Record row = objRes.next();
                ObjectState os = new ObjectState();
                os.name = row.get("name").asString();
                os.className = row.get("className").asString();

                Map<String, List<Map<String, Object>>> refGroups = new HashMap<>();

                for (org.neo4j.driver.Value v : row.get("data").asList(v -> v)) {
                    if (v.get("attr").isNull()) continue;
                    String attrName = v.get("attr").asString();
                    String type = v.get("type").asString();
                    os.attributeTypes.put(attrName, type);

                    if (!v.get("ref").isNull()) {
                        // Gom nhóm các tham chiếu để xử lý Collection
                        refGroups.computeIfAbsent(attrName, k -> new ArrayList<>()).add(new HashMap<>(v.asMap()));
                    } else {
                        os.primitiveValues.put(attrName, v.get("value").asObject());
                    }
                }

                // Xử lý nạp tham chiếu (Sắp xếp theo index nếu là tập hợp)
                for (String attr : refGroups.keySet()) {
                    List<Map<String, Object>> refs = refGroups.get(attr);
                    refs.sort(Comparator.comparingInt(m -> ((Number)m.get("idx")).intValue()));
                    List<String> sortedNames = new ArrayList<>();
                    refs.forEach(m -> sortedNames.add((String)m.get("ref")));
                    os.objectReferences.put(attr, sortedNames);
                }
                fullSnapshot.objects.put(os.name, os);
            }

            // --- PHẦN 2: QUÉT BINARY LINKS ---
            String binaryCypher =
                    "MATCH (a)-[r]->(b) WHERE type(r) STARTS WITH 'Link' AND NOT coalesce(r.isTernary, false) " +
                            "AND NOT coalesce(r.isLinkObjectPart, false) " +
                            "RETURN r.name as assocName, type(r) as label, a.use_id as src, b.use_id as tgt";

            Result binRes = session.run(binaryCypher);
            while (binRes.hasNext()) {
                Record rec = binRes.next();
                LinkState ls = new LinkState();
                ls.assocName = rec.get("assocName").asString();
                ls.edgeLabel = rec.get("label").asString();
                ls.participants = Arrays.asList(rec.get("src").asString(), rec.get("tgt").asString());
                fullSnapshot.links.put(ls.getIdentity(), ls);
            }

            // --- PHẦN 3: QUÉT LINK OBJECTS (AssociationClass Instances) ---
            String loCypher =
                    "MATCH (lo)-[:ObjectInstanceOf]->(ac:AssociationClass) " +
                            "MATCH (lo)-[r]->(p) WHERE r.isLinkObjectPart = true " +
                            "RETURN lo.use_id as loName, ac.name as acName, collect({p: p.use_id, idx: r.index}) as parts";

            Result loRes = session.run(loCypher);
            while (loRes.hasNext()) {
                Record rec = loRes.next();
                LinkState ls = new LinkState();
                ls.linkObjectName = rec.get("loName").asString();
                ls.assocName = rec.get("acName").asString();
                ls.edgeLabel = "LinkAssociateWith";

                List<Map<String, Object>> parts = rec.get("parts").asList(v -> (Map<String, Object>)v.asObject());
                // TODO: Sắp xếp parts theo idx nếu cần đúng thứ tự đầu mút
                parts.forEach(p -> ls.participants.add((String)p.get("p")));
                fullSnapshot.links.put(ls.getIdentity(), ls);
            }
        }
        return fullSnapshot;
    }



    public ObjectDiff compareObjects(FullObjectSnapshot javaSnap, FullObjectSnapshot neoSnap) {
        ObjectDiff diff = new ObjectDiff();
        diff.javaSnapshot = javaSnap;
        diff.neo4jSnapshot = neoSnap;

        // 1. Duyệt qua các đối tượng phía Java (USE)
        for (String name : javaSnap.objects.keySet()) {
            if (!neoSnap.objects.containsKey(name)) {
                // Chỉ có ở Java -> Máy mình đang thừa hoặc chưa Push
                diff.javaOnlyObjects.add(name);
            } else {
                // Có ở cả hai, thực hiện so sánh sâu (Deep Comparison)
                ObjectState javaObj = javaSnap.objects.get(name);
                ObjectState neoObj = neoSnap.objects.get(name);

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
        for (String name : neoSnap.objects.keySet()) {
            if (!javaSnap.objects.containsKey(name)) {
                // Chỉ có ở Neo4j -> DB đang thừa hoặc máy mình chưa Pull
                diff.neo4jOnlyObjects.add(name);
            }
        }

        // 2. So sánh Links (Logic Mới)
        // So sánh những gì máy Java đang có
        for (String identity : javaSnap.links.keySet()) {
            if (!neoSnap.links.containsKey(identity)) {
                diff.javaOnlyLinks.add(identity);
            } else if (!javaSnap.links.get(identity).isSameAs(neoSnap.links.get(identity))) {
                diff.mismatchedLinks.add(identity);
            }
        }
        // So sánh những gì Database đang thừa
        for (String identity : neoSnap.links.keySet()) {
            if (!javaSnap.links.containsKey(identity)) {
                diff.neo4jOnlyLinks.add(identity);
            }
        }




        return diff;
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
     * Cập nhật trạng thái thực thể vào USE (DB -> Java)
     * Tuân thủ quy tắc: Database là nguồn chân lý (Source of Truth)
     */
    private void applyChangesToUSEState(ObjectDiff diff) throws Exception {

        // --- BƯỚC 1: XÓA LINKS CŨ (Java thừa so với DB) ---
        // Phải xóa Link trước khi xóa Object để không vi phạm ràng buộc tham chiếu
        for (String linkId : diff.javaOnlyLinks) {
            LinkState ls = diff.javaSnapshot.links.get(linkId);
            if (ls != null) {
                String[] parts = ls.participants.toArray(new String[0]);
                try {
                    // Xóa Link thường hoặc LinkObject
                    systemApi.deleteLink(ls.assocName, parts);
                    WorkLogManager.getInstance().log("LINK_PULL_DELETE", "Removed link: " + ls.getIdentity());
                } catch (Exception e) {
                    // Im lặng nếu link đã biến mất do Cascade Delete của Object
                }
            }
        }

        // --- BƯỚC 2: XÓA OBJECTS CŨ (Java thừa so với DB) ---
        for (String objName : diff.javaOnlyObjects) {
            if (systemApi.getObject(objName) != null) {
                systemApi.deleteObject(objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_DELETE", "Removed object from USE: " + objName);
            }
        }

        // --- BƯỚC 3: TẠO OBJECTS MỚI (DB thừa so với Java) ---
        for (String objName : diff.neo4jOnlyObjects) {
            ObjectState dbObj = diff.neo4jSnapshot.objects.get(objName);
            if (system.model().getClass(dbObj.className) != null) {
                systemApi.createObject(dbObj.className, objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_CREATE", "Created object: " + objName);
            } else {
                // Trường hợp lỗi cực hiếm: Class chưa được pull mà Object đã đòi pull
                WorkLogManager.getInstance().log("ERROR", "Class missing for object: " + objName);
            }
        }

        // --- BƯỚC 4: ĐỒNG BỘ GIÁ TRỊ (Logic đối chiếu Metadata M1) ---
        // Duyệt qua TẤT CẢ các đối tượng đang có trong hệ thống sau khi đã thêm/xóa ở trên
        for (org.tzi.use.uml.sys.MObject useObj : system.state().allObjects()) {
            String objName = useObj.name();
            ObjectState dbState = diff.neo4jSnapshot.objects.get(objName);

            // Nếu đối tượng này không có trong Snapshot của DB, bỏ qua (trường hợp cực hiếm)
            if (dbState == null) continue;

            // QUAN TRỌNG: Duyệt qua TẤT CẢ các thuộc tính mà Class này quy định (Tầng Model M1)
            for (org.tzi.use.uml.mm.MAttribute attrDef : useObj.cls().allAttributes()) {
                String attrName = attrDef.name();

                // KIỂM TRA TRẠNG THÁI TRÊN DB
                boolean hasPrimitiveValue = dbState.primitiveValues.containsKey(attrName);
                boolean hasObjectReference = dbState.objectReferences.containsKey(attrName);

                try {
                    if (hasPrimitiveValue) {
                        // TRƯỜNG HỢP 1: DB CÓ GIÁ TRỊ NGUYÊN THỦY
                        String oclVal = UmlTypeTranslator.toOclLiteral(dbState.primitiveValues.get(attrName));
                        systemApi.setAttributeValue(objName, attrName, oclVal);
                    }
                    else if (hasObjectReference) {
                        // TRƯỜNG HỢP 2: DB CÓ THAM CHIẾU ĐỐI TƯỢNG
                        String oclRef = formatToOclReference(dbState.objectReferences.get(attrName));
                        systemApi.setAttributeValue(objName, attrName, oclRef);
                    }
                    else {
                        // TRƯỜNG HỢP 3: DB KHÔNG CÓ NODE GIÁ TRỊ NÀO
                        // Nghĩa là người dùng bên kia đã xóa/set null thuộc tính này
                        // Kiểm tra nếu Java hiện tại đang có giá trị thì mới gọi reset để tối ưu hiệu năng
                        if (!useObj.state(system.state()).attributeValue(attrDef).isUndefined()) {
                            systemApi.setAttributeValue(objName, attrName, "Undefined");
                            WorkLogManager.getInstance().log("ATTR_RESET", "Reset " + attrName + " of " + objName + " to null");
                        }
                    }
                } catch (Exception e) {
                    WorkLogManager.getInstance().log("ATTR_ERROR", "Failed to sync " + attrName + ": " + e.getMessage());
                }
            }
        }
        // --- BƯỚC 5: TÁI TẠO TẤT CẢ CÁC LOẠI LINKS ---
        // Việc gọi pullLinks(this.systemApi) ở đây là giải pháp THÔNG MINH nhất.
        // Nó quét trực tiếp DB để nối mạch máu cho các Object vừa nạp.
        pullLinks(this.systemApi);
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
//        try {
//            // Bước 1: Kiểm tra nhanh bằng Timestamp
//            // Nếu timestamp khớp, coi như đã đồng bộ hoàn toàn (Tiết kiệm tài nguyên)
//            if (isQuickSyncCheck()) {
//                return "All Synced";
//            }
//
//            // Bước 2: Nếu Timestamp lệch, thực hiện quét sâu (Deep Comparison) để lấy con số cụ thể
//            Map<String, ObjectState> javaSnap = getJavaObjectSnapshot().objects;
//            Map<String, ObjectState> neoSnap = getNeo4jObjectSnapshot().objects;
//
//            ObjectDiff diff = compareObjects(javaSnap, neoSnap);
//
//            if (!diff.hasDifference()) {
//                // Trường hợp hy hữu: Timestamp lệch nhưng dữ liệu thực tế lại giống nhau
//                return "All Synced";
//            }
//
//            // Trả về thống kê theo đúng đặc tả "Thống kê rõ ràng"
//            return String.format("%d New, %d Missing, %d Mismatch",
//                    diff.javaOnlyObjects.size(),   // Có ở Java nhưng DB không có
//                    diff.neo4jOnlyObjects.size(),  // Có ở DB nhưng Java không có
//                    diff.mismatchedObjects.size() // Cả hai cùng có nhưng khác giá trị
//            );
//
//        } catch (Exception e) {
//            return "Check Failed";
//        }
        return null;
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

    // Trong lớp ObjectSyncCoordinator
    public void syncObjectsForwardRealtime() {
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        // Thử lấy lock, nếu bận thì bỏ qua (Real-time ko đợi)
        LockManager.LockResult lock = LockManager.acquireLock(user);
        if (!lock.success) return;

        try {
            if (!isModelInSync()) return; // Chỉ sync object nếu model đã khớp

            ObjectDiff diff = compareObjects(getJavaObjectSnapshot(), getNeo4jObjectSnapshot());

            if (diff.hasDifference()) {
                // Thực thi push (Hàm này đã bao gồm logic xóa neo4jOnlyObjects và push javaOnly)
                executePush(diff);
            }
        } finally {
            LockManager.releaseLock();
        }
    }

    /**
     * Đẩy một thực thể Object đơn lẻ và toàn bộ các giá trị thuộc tính của nó xuống Neo4j
     */
    public void pushObject(org.neo4j.driver.TransactionContext tx, MObject obj, MSystemState state) {
        String objName = obj.name();
        String clsName = obj.cls().name();
        objectRepository.upsertObjectNode(tx, objName, clsName);

        Map<MAttribute, Value> attrValues = obj.state(state).attributeValueMap();
        for (Map.Entry<MAttribute, Value> entry : attrValues.entrySet()) {
            MAttribute attr = entry.getKey();
            Value useVal = entry.getValue();

            // NẾU GIÁ TRỊ RỖNG TRÊN JAVA -> XÓA NODE DƯỚI DB
            if (useVal.isUndefined()) {
                objectRepository.removeAttributeValueNode(tx, objName, attr.name());
                continue;
            }

            // CHỈ GÁN KHI CÓ GIÁ TRỊ
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", UmlTypeTranslator.toDatabaseType(attr.type()));
            metadata.put("isCollection", attr.type().isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID));
            metadata.put("collectionType", UmlTypeTranslator.getCollectionType(attr.type()));

            Object mappedValue = ValueMapper.mapUseValue(useVal);
            objectRepository.setAttributeValueNode(tx, objName, clsName, attr.name(), mappedValue, metadata);
        }
    }
    private List<String> findObjectMismatchDetails(ObjectState javaObj, ObjectState neoObj) {
        List<String> details = new ArrayList<>();

        // Lấy tập hợp tất cả các tên thuộc tính từ cả 2 phía để so sánh
        Set<String> allAttrNames = new HashSet<>(javaObj.primitiveValues.keySet());
        allAttrNames.addAll(neoObj.primitiveValues.keySet());

        for (String attr : allAttrNames) {
            Object vJava = javaObj.primitiveValues.get(attr);
            Object vNeo = neoObj.primitiveValues.get(attr);

            // Chuyển đổi an toàn sang String để so sánh (null sẽ thành chuỗi "null" hoặc "" tùy bạn chọn)
            String sJava = (vJava == null) ? "null" : vJava.toString();
            String sNeo = (vNeo == null) ? "null" : vNeo.toString();

            if (!sJava.equals(sNeo)) {
                details.add("Attr '" + attr + "' mismatch: Java(" + sJava + ") vs DB(" + sNeo + ")");
            }
        }

        // Tương tự cho Object References
        Set<String> allRefRoles = new HashSet<>(javaObj.objectReferences.keySet());
        allRefRoles.addAll(neoObj.objectReferences.keySet());

        for (String role : allRefRoles) {
            List<String> refsJava = javaObj.objectReferences.get(role);
            List<String> refsNeo = neoObj.objectReferences.get(role);

            // Sử dụng Objects.equals để so sánh 2 List (có thể null) một cách an toàn
            if (!Objects.equals(refsJava, refsNeo)) {
                details.add("Reference '" + role + "' has different targets.");
            }
        }

        return details;
    }



    /**
     * AUTO-PULL: Kéo dữ liệu về Java mà không hiện Pop-up
     */
    public void syncObjectsBackwardRealtime() {
        try {
            isApplyingInternalChange = true; // Bắt đầu nạp dữ liệu -> Chặn Auto-Push

            ObjectDiff diff = compareObjects(getJavaObjectSnapshot(), getNeo4jObjectSnapshot());
            if (diff.hasDifference()) {
                applyChangesToUSEState(diff);
                // Cập nhật timestamp local để đồng bộ với server
                updateLocalSyncTimestamp(new Neo4jObjectRepository().getRemoteObjectTimestamp());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isApplyingInternalChange = false; // Nạp xong -> Mở lại Auto-Push
        }
    }

    public static boolean isProcessingInternalUpdate() {
        return isApplyingInternalChange;
    }
}