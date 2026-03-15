package org.uet.dse.neo4j.sync.object;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.tzi.use.api.UseSystemApi;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystem;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.manager.WorkLogManager;
import org.uet.dse.neo4j.repo.query.Neo4jObjectQuery;
import org.uet.dse.neo4j.sync.helper.UmlTypeTranslator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ObjectPullService {
    private final UseSystemApi systemApi;
    private final MSystem system;

    public ObjectPullService(UseSystemApi systemApi, MSystem system) {
        this.systemApi = systemApi;
        this.system = system;
    }


    public void pullObjectFromNeo4j(ObjectDiff diff) throws Exception {
        deleteStaleLinks(diff);
        deleteStaleObjects(diff);
        createMissingObjects(diff);
        syncAllObjectAttributes(diff);
        pullLinks();
    }

    private void deleteStaleLinks(ObjectDiff diff) {
        for (String linkId : diff.javaOnlyLinks) {
            LinkState ls = diff.javaSnapshot.links.get(linkId);
            if (ls == null) continue;

            try {
                systemApi.deleteLink(ls.assocName, ls.participants.toArray(new String[0]));
                WorkLogManager.getInstance().log("LINK_PULL_DELETE", "Removed link: " + ls.getIdentity());
            } catch (Exception e) {
                WorkLogManager.getInstance().log("LINK_DELETE_ERROR",
                        "Failed to remove link " + linkId + ": " + e.getMessage());
            }
        }
    }

    private void deleteStaleObjects(ObjectDiff diff) throws Exception {
        for (String objName : diff.javaOnlyObjects) {
            if (systemApi.getObject(objName) == null) continue;

            systemApi.deleteObject(objName);
            WorkLogManager.getInstance().log("OBJECT_PULL_DELETE", "Removed object from USE: " + objName);
        }
    }

    private void createMissingObjects(ObjectDiff diff) throws Exception {
        for (String objName : diff.neo4jOnlyObjects) {
            ObjectState dbObj = diff.neo4jSnapshot.objects.get(objName);

            if (system.model().getClass(dbObj.className) != null) {
                systemApi.createObject(dbObj.className, objName);
                WorkLogManager.getInstance().log("OBJECT_PULL_CREATE", "Created object: " + objName);
            } else {
                WorkLogManager.getInstance().log("ERROR", "Class missing for object: " + objName);
            }
        }
    }

    private void syncAllObjectAttributes(ObjectDiff diff) {
        for (MObject useObj : system.state().allObjects()) {
            ObjectState dbState = diff.neo4jSnapshot.objects.get(useObj.name());
            if (dbState == null) continue;

            syncAttributesForObject(useObj, dbState);
        }
    }

    private void syncAttributesForObject(MObject useObj, ObjectState dbState) {
        for (MAttribute attrDef : useObj.cls().allAttributes()) {
            try {
                syncAttribute(useObj, attrDef, dbState);
            } catch (Exception e) {
                WorkLogManager.getInstance().log("ATTR_ERROR",
                        "Failed to sync " + attrDef.name() + " on " + useObj.name() + ": " + e.getMessage());
            }
        }
    }


    /**
     * resets an attribute to Undefined only when it currently holds a value —
     * avoids a no-op write and keeps the log meaningful.
     */
    private void resetIfDefined(MObject useObj, MAttribute attrDef, String objName, String attrName) throws Exception {
        boolean isDefined = !useObj.state(system.state())
                .attributeValue(attrDef)
                .isUndefined();
        if (!isDefined) return;

        systemApi.setAttributeValue(objName, attrName, "Undefined");
        WorkLogManager.getInstance().log("ATTR_RESET",
                "Reset " + attrName + " on " + objName + " to Undefined");
    }

    public void pullLinks() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = Neo4jDriverManager.getInstance().getDriver()
                .session(SessionConfig.forDatabase(dbName))) {

            pullBinaryLinks(session, systemApi);
            pullTernaryLinks(session, systemApi);
            pullLinkObjects(session, systemApi);
        }
    }

    private void pullBinaryLinks(Session session, UseSystemApi api) {
        session.run(Neo4jObjectQuery.PULL_BINARY_LINKS).forEachRemaining(rec -> {
            String assocName = rec.get("assocName").asString();
            String[] ends    = { rec.get("src").asString(), rec.get("tgt").asString() };
            createLinkSafely(api, assocName, ends);
        });
    }

    private void pullTernaryLinks(Session session, UseSystemApi api) {
        session.run(Neo4jObjectQuery.PULL_TERNARY_LINKS).forEachRemaining(rec -> {
            String   assocName = rec.get("assocName").asString();
            String[] objNames  = extractTernaryParticipants(rec);
            createLinkSafely(api, assocName, objNames);
        });
    }

    /**
     * sorts participant rows by their stored index, then extracts object names.
     * index ordering preserves the original association-end order from the model.
     */
    private String[] extractTernaryParticipants(Record rec) {
        return rec.get("participants").asList(org.neo4j.driver.Value::asMap).stream()
                .sorted(Comparator.comparingInt(p -> ((Number) p.get("idx")).intValue()))
                .map(p -> (String) p.get("obj"))
                .toArray(String[]::new);
    }

    private void pullLinkObjects(Session session, UseSystemApi api) {
        session.run(Neo4jObjectQuery.PULL_LINK_OBJECTS).forEachRemaining(rec -> {
            String   acName      = rec.get("acName").asString();
            String   loName      = rec.get("loName").asString();
            String[] participants = rec.get("participants")
                    .asList(Value::asString)
                    .toArray(new String[0]);
            createLinkObjectSafely(api, acName, loName, participants);
        });
    }

// ── Safe creation wrappers ────────────────────────────────────────────────────

    /**
     * Swallows duplicate-link exceptions, which are expected when pulling into
     * a state that already partially exists. All other failures are logged.
     */
    private void createLinkSafely(UseSystemApi api, String assocName, String[] participants) {
        try {
            api.createLink(assocName, participants);
        } catch (Exception e) {
            WorkLogManager.getInstance().log("LINK_PULL_ERROR",
                    "Failed to create link [" + assocName + "]: " + e.getMessage());
        }
    }

    private void createLinkObjectSafely(UseSystemApi api, String acName, String loName, String[] participants) {
        try {
            api.createLinkObject(acName, loName, participants);
        } catch (Exception e) {
            WorkLogManager.getInstance().log("LINK_OBJECT_PULL_ERROR",
                    "Failed to create link object [" + loName + "] of [" + acName + "]: " + e.getMessage());
        }
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


    private void syncAttribute(MObject useObj, MAttribute attrDef, ObjectState dbState) throws Exception {
        String objName = useObj.name();
        String attrName = attrDef.name();

        Object dbValue = dbState.primitiveValues.get(attrName);
        org.tzi.use.uml.ocl.type.Type typeDef = attrDef.type();

        if ("Object".equals(dbValue)) {
            Object treeData = dbState.getRawValue(attrName);
            if (treeData != null) {
                String oclExpr = reconstructOclExpression(treeData, typeDef);
                systemApi.setAttributeValue(objName, attrName, oclExpr);
                return;
            }
        }

        if (typeDef.isKindOfCollection(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
            if (dbValue != null && !"Undefined".equals(dbValue)) {
                systemApi.setAttributeValue(objName, attrName, dbValue.toString());
                return;
            }
        }

        if (dbValue != null && !"Undefined".equals(dbValue)) {
            String oclLiteral = org.uet.dse.neo4j.sync.helper.UmlTypeTranslator.toOclLiteral(dbValue);
            systemApi.setAttributeValue(objName, attrName, oclLiteral);
        } else {
            resetIfDefined(useObj, attrDef, objName, attrName);
        }
    }
    private String reconstructOclExpression(Object data, org.tzi.use.uml.ocl.type.Type expectedType) {
        if (data == null || data.equals("Undefined") || data.equals("null")) {
            return "Undefined";
        }

        if (expectedType instanceof org.tzi.use.uml.ocl.type.EnumType) {
            String val = data.toString();
            return val.startsWith("#") ? val : "#" + val;
        }

        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;

            Object collTypeObj = map.get("collectionType");
            String collType = (collTypeObj != null) ? collTypeObj.toString() : "Set";

            Object itemsObj = map.get("items");
            if (!(itemsObj instanceof List)) return collType + "{}";

            List<?> items = (List<?>) itemsObj;
            if (items.isEmpty()) return collType + "{}";

            org.tzi.use.uml.ocl.type.Type elemType = expectedType;
            if (expectedType instanceof org.tzi.use.uml.ocl.type.CollectionType) {
                elemType = ((org.tzi.use.uml.ocl.type.CollectionType) expectedType).elemType();
            }

            final org.tzi.use.uml.ocl.type.Type finalElemType = elemType;

            String joined = items.stream()
                    .map(item -> reconstructOclExpression(item, finalElemType))
                    .collect(java.util.stream.Collectors.joining(", "));

            return String.format("%s{%s}", collType, joined);
        }

        if (data instanceof List) {
            List<String> refs = (List<String>) data;
            if (refs.isEmpty()) return "Set{}";
            if (refs.size() == 1) return refs.get(0);
            return "Set{" + String.join(", ", refs) + "}";
        }


        if (expectedType.isKindOfClass(org.tzi.use.uml.ocl.type.Type.VoidHandling.EXCLUDE_VOID)) {
            return data.toString();
        }
        return UmlTypeTranslator.toOclLiteral(data);
    }

    private void syncAttributeObsolete(MObject useObj, MAttribute attrDef, ObjectState dbState) throws Exception {
        String objName  = useObj.name();
        String attrName = attrDef.name();

        if (dbState.primitiveValues.containsKey(attrName)) {
            String oclVal = UmlTypeTranslator.toOclLiteral(dbState.primitiveValues.get(attrName));
            systemApi.setAttributeValue(objName, attrName, oclVal);
            return;
        }

        if (dbState.objectReferences.containsKey(attrName)) {
            String oclRef = formatToOclReference(dbState.objectReferences.get(attrName));
            systemApi.setAttributeValue(objName, attrName, oclRef);
            return;
        }

        resetIfDefined(useObj, attrDef, objName, attrName);
    }

}
