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

    List<Object> dataList = row.get("data").asList();

    Map<String, Map<List<Integer>, TreeMap<Integer, String>>> refHelper = new HashMap<>();

    for (Object d : dataList) {
      Map<String, Object> item = (Map<String, Object>) d;
      String attrName = (String) item.get("attr");
      if (attrName == null)
        continue;

      if (!os.attributeTypes.containsKey(attrName)) {
        os.attributeTypes.put(attrName, (String) item.get("type"));
      }

      boolean isObjRef = Boolean.TRUE.equals(item.get("isObjRef")) || Boolean.TRUE.equals(item.get("nestIsObjRef"));
      boolean isColl = Boolean.TRUE.equals(item.get("isColl"));
      Object valueOnNode = item.get("value");
      String refId = (String) item.get("ref");

      List<Object> rawPath = (List<Object>) item.get("pathIdx");
      List<Integer> pathIdx = (rawPath == null) ? Collections.emptyList() : rawPath.stream().map(v -> ((Number) v).intValue()).collect(Collectors.toList());

      Integer itemIdx = item.get("idx") != null ? ((Number) item.get("idx")).intValue() : 0;

      if (isObjRef) {
        if (refId != null) {
          refHelper.computeIfAbsent(attrName, k -> new HashMap<>()).computeIfAbsent(pathIdx, k -> new TreeMap<>()).put(itemIdx, refId);
        }
      } else {
        if (!os.primitiveValues.containsKey(attrName) || os.primitiveValues.get(attrName) == null) {
          if (valueOnNode != null && !valueOnNode.toString().equals("NESTED_COLLECTION") && !valueOnNode.toString().equals("Undefined")) {
            if (isColl) {
              os.primitiveValues.put(attrName, decodePrimitiveCollection(valueOnNode.toString()));
            } else {
              os.primitiveValues.put(attrName, valueOnNode);
            }
          }
        }
      }
    }

    refHelper.forEach((attr, pathMap) -> {
      os.objectReferences.put(attr, reconstructDeepList(pathMap));
    });

    return os;
  }

  private static List<Object> reconstructDeepList(Map<List<Integer>, TreeMap<Integer, String>> pathMap) {
    if (pathMap.containsKey(Collections.emptyList()) && pathMap.size() == 1) {
      return new ArrayList<>(pathMap.get(Collections.emptyList()).values());
    }
    TreeMap<Integer, List<Object>> groups = new TreeMap<>();
    for (var entry : pathMap.entrySet()) {
      List<Integer> path = entry.getKey();
      if (path.isEmpty())
        continue;

      int groupIdx = path.get(0);
      List<Object> subList = new ArrayList<>(entry.getValue().values());
      groups.put(groupIdx, subList);
    }

    return new ArrayList<>(groups.values());
  }

  private static List<Object> decodePrimitiveCollection(String raw) {
    if (raw == null || raw.trim().isEmpty() || raw.equals("COLLECTION_EMPTY") || raw.equals("Undefined") || raw.equals("COLLECTION_DATA")) {
      return new ArrayList<>();
    }

    String[] parts = raw.split("\\s*\\|\\s*");
    List<Object> result = new ArrayList<>();
    for (String p : parts) {
      p = p.trim();
      if (p.equals("null"))
        result.add(null);
      else {
        try {
          if (p.contains("."))
            result.add(Double.parseDouble(p));
          else
            result.add(Long.parseLong(p));
        } catch (NumberFormatException e) {
          result.add(p);
        }
      }
    }
    return result;
  }

  static LinkState toBinaryLink(Record rec) {
    LinkState ls = new LinkState();
    ls.assocName = rec.get("assocName").asString();
    ls.edgeLabel = rec.get("label").asString();
    ls.participants = Arrays.asList(rec.get("src").asString(), rec.get("tgt").asString());
    return ls;
  }

  static LinkState toLinkObject(Record rec) {
    LinkState ls = new LinkState();
    ls.linkObjectName = rec.get("loName").asString();
    ls.assocName = rec.get("acName").asString();
    ls.edgeLabel = "LinkAssociateWith";

    rec.get("parts").asList(v -> v.asMap()).stream().sorted(Comparator.comparingInt(m -> ((Number) m.get("idx")).intValue())).map(m -> (String) m.get("p")).forEach(ls.participants::add);

    return ls;
  }

}
