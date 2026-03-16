package org.uet.dse.neo4j.sync.object;

import org.neo4j.driver.Record;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;

import java.util.*;
import java.util.stream.Collectors;

public class Neo4jObjectSnapshotMapper {
    public static ObjectState toObjectState(Record row) {
        ObjectState os = new ObjectState();
        os.name = row.get("name").asString();
        os.className = row.get("className").asString();

        Map<String, TreeMap<Integer, String>> refGroups = new HashMap<>();
        List<Object> dataList = row.get("data").asList();

        for (Object d : dataList) {
            Map<String, Object> item = (Map<String, Object>) d;
            String attrName = (String) item.get("attr");
            if (attrName == null) continue;

            os.attributeTypes.put(attrName, (String) item.get("type"));

            boolean isColl = Boolean.TRUE.equals(item.get("isColl"));
            boolean isObjRef = Boolean.TRUE.equals(item.get("isObjRef"));

            Object valueOnNode = item.get("value"); // Giá trị thô từ Neo4j

            boolean isRefType = Boolean.TRUE.equals(item.get("isObjRef"))
                || "COLLECTION_DATA".equals(valueOnNode)
                || "Object".equals(valueOnNode);

            String refId = (String) item.get("ref");
            Object idxObj = item.get("idx");

            if (isRefType) {
                if (refId != null) {
                    refGroups.computeIfAbsent(attrName, k -> new TreeMap<>());
                    int index = (idxObj != null) ? ((Number) idxObj).intValue() : 0;
                    refGroups.get(attrName).put(index, refId);
                }
//                else if (!os.objectReferences.containsKey(attrName)) {
//                    os.objectReferences.put(attrName, new ArrayList<>());
//                }
            } else {
                if (isColl && valueOnNode != null && !valueOnNode.equals("COLLECTION_EMPTY")) {
                    os.primitiveValues.put(attrName, decodePrimitiveCollection(valueOnNode.toString()));
                } else {
                    os.primitiveValues.put(attrName, valueOnNode);
                }
            }
            if (isObjRef) {
                if (refId != null) {
                    refGroups.computeIfAbsent(attrName, k -> new TreeMap<>());
                    int index = (idxObj != null) ? ((Number) idxObj).intValue() : 0;
                    refGroups.get(attrName).put(index, refId);
                }
            }
            else {
                if (isColl) {
                    if (valueOnNode != null) {
                        os.primitiveValues.put(attrName, decodePrimitiveCollection(valueOnNode.toString()));
                    }
                } else {
                    os.primitiveValues.put(attrName, valueOnNode);
                }
            }
        }

        refGroups.forEach((attr, map) -> {
            os.objectReferences.put(attr, new ArrayList<>(map.values()));
        });

        return os;
    }
    private static List<Object> decodePrimitiveCollection(String raw) {
        if (raw == null || raw.trim().isEmpty() ||
            raw.equals("COLLECTION_EMPTY") || raw.equals("Undefined") || raw.equals("COLLECTION_DATA")) {
            return new ArrayList<>();
        }

        String[] parts = raw.split("\\s*\\|\\s*");
        List<Object> result = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.equals("null")) {
                result.add(null);
            } else {
                try {
                    if (p.contains(".")) result.add(Double.parseDouble(p));
                    else result.add(Long.parseLong(p));
                } catch (NumberFormatException e) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    static LinkState toBinaryLink(Record rec) {
        LinkState ls = new LinkState();
        ls.assocName   = rec.get("assocName").asString();
        ls.edgeLabel   = rec.get("label").asString();
        ls.participants = Arrays.asList(
                rec.get("src").asString(),
                rec.get("tgt").asString()
        );
        return ls;
    }

    static LinkState toLinkObject(Record rec) {
        LinkState ls = new LinkState();
        ls.linkObjectName = rec.get("loName").asString();
        ls.assocName      = rec.get("acName").asString();
        ls.edgeLabel      = "LinkAssociateWith";

        rec.get("parts").asList(v -> v.asMap()).stream()
                .sorted(Comparator.comparingInt(m -> ((Number) m.get("idx")).intValue()))
                .map(m -> (String) m.get("p"))
                .forEach(ls.participants::add);

        return ls;
    }
}
