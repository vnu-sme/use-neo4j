package org.uet.dse.neo4j.sync;

import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.LinkState;
import org.uet.dse.neo4j.model.ObjectState;

public class Neo4jObjectSnapshotUtils {
    public FullObjectSnapshot getNeo4jObjectSnapshot() {
        FullObjectSnapshot snapshot = new FullObjectSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = Neo4jDriverManager.getInstance().getDriver()
                .session(SessionConfig.forDatabase(dbName))) {

            collectObjects(session, snapshot);
            collectBinaryLinks(session, snapshot);
            collectLinkObjects(session, snapshot);
        }

        return snapshot;
    }

// ── Private session-level helpers ─────────────────────────────────────────────

    private void collectObjects(Session session, FullObjectSnapshot snapshot) {
        session.run(Neo4jSnapshotQueries.OBJECTS_AND_ATTRIBUTES).forEachRemaining(row -> {
            ObjectState os = Neo4jObjectSnapshotMapper.toObjectState(row);
            snapshot.objects.put(os.name, os);
        });
    }

    private void collectBinaryLinks(Session session, FullObjectSnapshot snapshot) {
        session.run(Neo4jSnapshotQueries.BINARY_LINKS).forEachRemaining(rec -> {
            LinkState ls = Neo4jObjectSnapshotMapper.toBinaryLink(rec);
            snapshot.links.put(ls.getIdentity(), ls);
        });
    }

    private void collectLinkObjects(Session session, FullObjectSnapshot snapshot) {
        session.run(Neo4jSnapshotQueries.LINK_OBJECTS).forEachRemaining(rec -> {
            LinkState ls = Neo4jObjectSnapshotMapper.toLinkObject(rec);
            snapshot.links.put(ls.getIdentity(), ls);
        });
    }
}
