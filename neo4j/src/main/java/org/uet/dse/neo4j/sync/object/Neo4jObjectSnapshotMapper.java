package org.uet.dse.neo4j.sync.object;

import org.neo4j.driver.Record;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;

import java.util.*;
import java.util.stream.Collectors;

public class Neo4jObjectSnapshotMapper {
    static ObjectState toObjectState(Record row) {
        ObjectState os = new ObjectState();
        os.name      = row.get("name").asString();
        os.className = row.get("className").asString();

        Map<String, List<Map<String, Object>>> refGroups = new HashMap<>();

        for (org.neo4j.driver.Value v : row.get("data").asList(val -> val)) {
            if (v.get("attr").isNull()) continue;

            String attrName = v.get("attr").asString();
            os.attributeTypes.put(attrName, v.get("type").asString());

            if (!v.get("ref").isNull()) {
                refGroups.computeIfAbsent(attrName, k -> new ArrayList<>())
                        .add(new HashMap<>(v.asMap()));
            } else {
                os.primitiveValues.put(attrName, v.get("value").asObject());
            }
        }

        populateObjectReferences(os, refGroups);
        return os;
    }

    /**
     * Flattens grouped reference rows into ordered name-lists.
     * Index ordering matters for ordered collections (Sequence, OrderedSet).
     */
    private static void populateObjectReferences(
            ObjectState os,
            Map<String, List<Map<String, Object>>> refGroups) {

        for (Map.Entry<String, List<Map<String, Object>>> entry : refGroups.entrySet()) {
            List<String> sortedRefs = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(m -> ((Number) m.get("idx")).intValue()))
                    .map(m -> (String) m.get("ref"))
                    .collect(Collectors.toList());

            os.objectReferences.put(entry.getKey(), sortedRefs);
        }
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
