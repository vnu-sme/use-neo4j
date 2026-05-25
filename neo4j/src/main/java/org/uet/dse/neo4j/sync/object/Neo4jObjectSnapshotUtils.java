package org.uet.dse.neo4j.sync.object;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;
import org.uet.dse.neo4j.sync.query.Neo4jSnapshotQuery;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class Neo4jObjectSnapshotUtils {
    public FullObjectSnapshot getNeo4jObjectSnapshot(String modelName) {
        FullObjectSnapshot snapshot = new FullObjectSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = Neo4jDriverManager.getInstance().getDriver()
                .session(SessionConfig.forDatabase(dbName))) {

            collectObjects(session, snapshot, modelName);
            collectBinaryLinks(session, snapshot, modelName);
            collectTernaryLinks(session, snapshot, modelName);
            collectLinkObjects(session, snapshot, modelName);
        }

        return snapshot;
    }

    private void collectTernaryLinks(Session session, FullObjectSnapshot snapshot, String modelName) {
        session.run(Neo4jSnapshotQuery.TERNARY_LINKS, Map.of("modelName", modelName)).forEachRemaining(rec -> {
            LinkState ls = toTernaryLink(rec);
            snapshot.links.put(ls.getIdentity(), ls);
        });
    }
    private LinkState toTernaryLink(Record rec) {
        LinkState ls = new LinkState();
        ls.assocName = rec.get("assocName").asString();
        ls.edgeLabel = "LinkAssociateWith";
        ls.isTernary = true;

        List<Map<String, Object>> parts = rec.get("parts").asList(v -> v.asMap());

        List<String> sortedParticipants = parts.stream()
            .sorted((m1, m2) -> {
                Object idx1 = m1.get("idx");
                Object idx2 = m2.get("idx");
                int i1 = (idx1 instanceof Number) ? ((Number) idx1).intValue() : 0;
                int i2 = (idx2 instanceof Number) ? ((Number) idx2).intValue() : 0;
                return Integer.compare(i1, i2);
            })
            .map(m -> (String) m.get("p"))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        ls.participants.addAll(sortedParticipants);
        return ls;
    }
    private void collectObjects(Session session, FullObjectSnapshot snapshot, String modelName) {
        session.run(Neo4jSnapshotQuery.OBJECTS_AND_ATTRIBUTES, Map.of("modelName", modelName)).forEachRemaining(row -> {
            ObjectState os = Neo4jObjectSnapshotMapper.toObjectState(row);
            snapshot.objects.put(os.name, os);
        });
    }

    private void collectBinaryLinks(Session session, FullObjectSnapshot snapshot, String modelName) {
        session.run(Neo4jSnapshotQuery.BINARY_LINKS, Map.of("modelName", modelName)).forEachRemaining(rec -> {
            LinkState ls = Neo4jObjectSnapshotMapper.toBinaryLink(rec);
            snapshot.links.put(ls.getIdentity(), ls);
        });
    }

    private void collectLinkObjects(Session session, FullObjectSnapshot snapshot, String modelName) {
        session.run(Neo4jSnapshotQuery.LINK_OBJECTS, Map.of("modelName", modelName)).forEachRemaining(rec -> {
            LinkState ls = Neo4jObjectSnapshotMapper.toLinkObject(rec);
            snapshot.links.put(ls.getIdentity(), ls);
        });
    }
}
