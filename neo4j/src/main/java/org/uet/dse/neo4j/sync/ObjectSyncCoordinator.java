package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.helper.ValueMapper;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;
import org.uet.dse.neo4j.repo.Neo4jObjectRepository;
import org.uet.dse.neo4j.repo.Neo4jSideRepository;
import org.uet.dse.neo4j.sync.feedback.SyncFeedbackHandler;
import org.uet.dse.neo4j.sync.feedback.object.AutoSyncFeedbackHandler;
import org.uet.dse.neo4j.sync.feedback.object.ManualSyncFeedbackHandler;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class ObjectSyncCoordinator {
    //changes from user /plugin
    private static boolean isApplyingInternalChange = false;
    private static long lastLocalObjectSyncTimestamp = 0;
    private final MSystem system;
    private final Neo4jModelRepository repo;

    private final Neo4jObjectRepository objectRepository;
    private final UseSystemApi systemApi;

    private final ObjectSnapshotCompare objectSnapshotCompare;

    public ObjectSyncCoordinator(MSystem system) {
        this.system = system;
        this.systemApi = UseSystemApi.create(system, true);
        this.repo = new Neo4jModelRepository();
        this.objectRepository = new Neo4jObjectRepository();
        objectSnapshotCompare = new ObjectSnapshotCompare(this.system);
    }

    /**
     * check model version (important)
     */
    private boolean isModelInSync() {
        long localHash = calculateModelHash(system.model());
        long remoteHash = objectRepository.getRemoteModelHash();
        return localHash != -1 && localHash == remoteHash;
    }

    public void syncObjectsForward(boolean isManual) {
        SyncFeedbackHandler feedback = isManual
                ? new ManualSyncFeedbackHandler(GUIHelper::showSyncPreview)
                : new AutoSyncFeedbackHandler();

        syncObjectsForward(feedback);
    }

    private void syncObjectsForward(SyncFeedbackHandler feedback) {
        if (!isModelInSync()) {
            feedback.onModelOutOfSync();
            return;
        }

        ObjectDiff diff = objectSnapshotCompare.compareObjects();
        if (!diff.hasDifference()) {
            feedback.onAlreadyInSync();
            return;
        }

        if (!feedback.onPreviewAndConfirm(diff)) {
            return;
        }

        try {
            executePush(diff);
            feedback.onSuccess(diff);
        } catch (Exception e) {
            feedback.onFailure(e);
        }
    }

    public void syncObjectsForwardObsolete(boolean isManual) {
        if (!isModelInSync()) {
            if (isManual) {
                GUIHelper.handleInconsistentModel("Push Objects");
            } else {
                System.out.println("Real-time sync skipped: Model version mismatch.");
            }
            return;
        }

        ObjectDiff diff = objectSnapshotCompare.compareObjects();

        if (!diff.hasDifference()) {
            if (isManual) {
                JOptionPane.showMessageDialog(null, "Object states are already in sync.", "No Changes", JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }

        if (isManual) {
            if (!GUIHelper.showSyncPreview(diff, "Forward (Java -> DB)")) {
                return;
            }
        }

        try {
            executePush(diff);

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
        if (!isModelInSync()) {
            GUIHelper.handleInconsistentModel("Pull Objects");
            return;
        }

        ObjectDiff diff = objectSnapshotCompare.compareObjects(); //objectSnapshotCompare.compareObjects();


        if (isManual) {
            if (!diff.hasDifference()) {
                JOptionPane.showMessageDialog(null, "Your local state matches the Database.");
                return;
            }
            if (!GUIHelper.showSyncPreview(diff, "Pull Objects (DB -> Java)")) return;
        }

        try {
            applyChangesToUSEState(diff);

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
                for (String objName : diff.neo4jOnlyObjects) {
                    objectRepository.deleteObjectDeeply(tx, objName);
                    WorkLogManager.getInstance().log("CLEANUP", "Deep deleted object: " + objName);
                }
                for (String linkName : diff.neo4jOnlyLinks) {
                    tx.run("MATCH ()-[r {name: $name}]-() WHERE type(r) STARTS WITH 'Link' DELETE r",
                            Map.of("name", linkName));
                    tx.run("MATCH (h:LinkHub {name: $name}) DETACH DELETE h",
                            Map.of("name", linkName));
                }

                List<String> toPushObjs = new ArrayList<>(diff.javaOnlyObjects);
                toPushObjs.addAll(diff.mismatchedObjects);

                for (String objName : toPushObjs) {
                    MObject obj = state.objectByName(objName);
                    if (!objectRepository.checkClassExistsInDb(tx, obj.cls().name())) {
                        throw new RuntimeException("Class '" + obj.cls().name() + "' missing. Push Model first!");
                    }
                    pushObject(tx, obj, state); // Hàm tạo Node Object và AttributeValue
                }

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

            Neo4jSideRepository.updateObjectVersionOnServer();
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

            String ternaryCypher =
                    "MATCH (p)-[r]->(hub:LinkHub) WHERE r.isTernary = true " +
                            "RETURN hub.name as assocName, collect({obj: p.use_id, idx: r.index}) as participants";

            Result terRes = session.run(ternaryCypher);
            while (terRes.hasNext()) {
                Record rec = terRes.next();
                List<Map<String, Object>> parts = rec.get("participants").asList(v -> (Map<String, Object>)v.asMap());

                parts.sort(Comparator.comparingInt(p -> ((Number)p.get("idx")).intValue()));

                String[] objNames = new String[parts.size()];
                for(int i=0; i<parts.size(); i++) objNames[i] = (String)parts.get(i).get("obj");

                try {
                    api.createLink(rec.get("assocName").asString(), objNames);
                } catch (Exception e) { /* Log error */ }
            }
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


    private void pushSingleObject(TransactionContext tx, ObjectState state) {
        objectRepository.upsertObjectNode(tx, state.name, state.className);

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

                for (String attr : refGroups.keySet()) {
                    List<Map<String, Object>> refs = refGroups.get(attr);
                    refs.sort(Comparator.comparingInt(m -> ((Number)m.get("idx")).intValue()));
                    List<String> sortedNames = new ArrayList<>();
                    refs.forEach(m -> sortedNames.add((String)m.get("ref")));
                    os.objectReferences.put(attr, sortedNames);
                }
                fullSnapshot.objects.put(os.name, os);
            }

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

        for (String name : javaSnap.objects.keySet()) {
            if (!neoSnap.objects.containsKey(name)) {
                diff.javaOnlyObjects.add(name);
            } else {
                ObjectState javaObj = javaSnap.objects.get(name);
                ObjectState neoObj = neoSnap.objects.get(name);

                if (!javaObj.isSameAs(neoObj)) {
                    diff.mismatchedObjects.add(name);

                    List<String> details = findObjectMismatchDetails(javaObj, neoObj);
                    diff.mismatchDetails.put(name, details);
                }
            }
        }

        for (String name : neoSnap.objects.keySet()) {
            if (!javaSnap.objects.containsKey(name)) {
                diff.neo4jOnlyObjects.add(name);
            }
        }

        for (String identity : javaSnap.links.keySet()) {
            if (!neoSnap.links.containsKey(identity)) {
                diff.javaOnlyLinks.add(identity);
            } else if (!javaSnap.links.get(identity).isSameAs(neoSnap.links.get(identity))) {
                diff.mismatchedLinks.add(identity);
            }
        }

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
            long remoteTs = repo.getRemoteObjectTimestamp();
            //nothing found
            if (remoteTs == 0) return true;

            return lastLocalObjectSyncTimestamp == remoteTs;
        } catch (Exception e) {
            return false;
        }
    }


    //db -? java
    //db is sot
    private void applyChangesToUSEState(ObjectDiff diff) throws Exception {
        for (String linkId : diff.javaOnlyLinks) {
            LinkState ls = diff.javaSnapshot.links.get(linkId);
            if (ls != null) {
                String[] parts = ls.participants.toArray(new String[0]);
                try {
                    systemApi.deleteLink(ls.assocName, parts);
                    WorkLogManager.getInstance().log("LINK_PULL_DELETE", "Removed link: " + ls.getIdentity());
                } catch (Exception e) {

                }
            }
        }

        for (String objName : diff.javaOnlyObjects) {
            if (systemApi.getObject(objName) != null) {
                systemApi.deleteObject(objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_DELETE", "Removed object from USE: " + objName);
            }
        }

        for (String objName : diff.neo4jOnlyObjects) {
            ObjectState dbObj = diff.neo4jSnapshot.objects.get(objName);
            if (system.model().getClass(dbObj.className) != null) {
                systemApi.createObject(dbObj.className, objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_CREATE", "Created object: " + objName);
            } else {

                WorkLogManager.getInstance().log("ERROR", "Class missing for object: " + objName);
            }
        }

        for (org.tzi.use.uml.sys.MObject useObj : system.state().allObjects()) {
            String objName = useObj.name();
            ObjectState dbState = diff.neo4jSnapshot.objects.get(objName);

            if (dbState == null) continue;

            for (org.tzi.use.uml.mm.MAttribute attrDef : useObj.cls().allAttributes()) {
                String attrName = attrDef.name();

                boolean hasPrimitiveValue = dbState.primitiveValues.containsKey(attrName);
                boolean hasObjectReference = dbState.objectReferences.containsKey(attrName);

                try {
                    if (hasPrimitiveValue) {

                        String oclVal = UmlTypeTranslator.toOclLiteral(dbState.primitiveValues.get(attrName));
                        systemApi.setAttributeValue(objName, attrName, oclVal);
                    }
                    else if (hasObjectReference) {

                        String oclRef = formatToOclReference(dbState.objectReferences.get(attrName));
                        systemApi.setAttributeValue(objName, attrName, oclRef);
                    }
                    else {

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
        pullLinks(this.systemApi);
    }
    /**
     * Helper: Định dạng giá trị sang chuẩn OCL Expression để nạp vào USE
     */
    private String formatToOclExpression(Object val) {
        if (val == null || val.toString().equals("null")) return "Undefined";

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
        if (names.size() == 1) return names.get(0);

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
            if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
                return "No Connection";
            }

            if (isQuickSyncCheck()) return "All Synced";

            // Deep check
//            FullObjectSnapshot javaSnap = getJavaObjectSnapshot();
//            FullObjectSnapshot neoSnap = getNeo4jObjectSnapshot();
//            ObjectDiff diff = compareObjects(javaSnap, neoSnap);

            ObjectDiff diff = objectSnapshotCompare.compareObjects();

            if (diff == null || !diff.hasDifference()) return "All Synced";

            return String.format("%d Obj, %d Link diff",
                    (diff.javaOnlyObjects.size() + diff.mismatchedObjects.size()),
                    (diff.javaOnlyLinks.size() + diff.mismatchedLinks.size()));
        } catch (Exception e) {
            return "Check Failed";
        }
    }

    /**
     * CHỈ ĐẨY 1 OBJECT LÊN DB (JAVA -> DB)
     */
    public void pushSingleObjectByName(String objName) {
//        MObject obj = system.state().objectByName(objName);
//        if (obj == null) return;
//
//        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
//            session.executeWrite(tx -> {
//                // 1. Chụp ảnh trạng thái của đúng Object này
//                ObjectState state = captureSingleObjectState(obj);
//
//                // 2. Đẩy lên server (Sử dụng hàm pushSingleObject đã viết ở phần executePush)
//                // Lưu ý: pushSingleObject cần Transaction (tx)
//                pushSingleObject(tx, state);
//
//                return null;
//            });
//
//            // Cập nhật timestamp để dashboard nhận diện
//            Neo4jUmlBridge.updateObjectVersionOnServer();
//            System.out.println("SELECTIVE_PUSH_OBJ" + "Pushed object: " + objName);
//        }
    }


    private ObjectState captureSingleObjectState(MObject obj) {
        ObjectState os = new ObjectState();
        os.name = obj.name();
        os.className = obj.cls().name();

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

    public void syncObjectsForwardRealtime() {
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        LockManager.LockResult lock = LockManager.acquireLock(user);
        if (!lock.success) return;

        try {
            if (!isModelInSync()) return;

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

            if (useVal.isUndefined()) {
                objectRepository.removeAttributeValueNode(tx, objName, attr.name());
                continue;
            }

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


    public void syncObjectsBackwardRealtime() {
        try {
            isApplyingInternalChange = true;

            ObjectDiff diff = compareObjects(getJavaObjectSnapshot(), getNeo4jObjectSnapshot());
            if (diff.hasDifference()) {
                applyChangesToUSEState(diff);
                updateLocalSyncTimestamp(new Neo4jObjectRepository().getRemoteObjectTimestamp());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isApplyingInternalChange = false;
        }
    }

    public static boolean isProcessingInternalUpdate() {
        return isApplyingInternalChange;
    }
}