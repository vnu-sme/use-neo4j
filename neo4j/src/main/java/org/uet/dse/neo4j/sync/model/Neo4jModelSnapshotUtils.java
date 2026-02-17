package org.uet.dse.neo4j.sync.model;

import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model.AssociationState;
import org.uet.dse.neo4j.model.ClassState;
import org.uet.dse.neo4j.model.FullModelSnapshot;
import org.uet.dse.neo4j.sync.query.Neo4jSnapshotQuery;

import java.util.HashMap;
import java.util.Map;

public class Neo4jModelSnapshotUtils {
    public FullModelSnapshot getFullModelSnapshotFromNeo4j() {
        FullModelSnapshot fullSnapshot = new FullModelSnapshot();
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();

        try (Session session = Neo4jDriverManager.getInstance().getDriver()
                .session(SessionConfig.forDatabase(dbName))) {

            Map<String, ClassState> stateMap = loadClassStates(session);
            applyGeneralizations(session, stateMap);
            fullSnapshot.classes = stateMap;

            loadBinaryAssociations(session, fullSnapshot);
            loadTernaryAssociations(session, fullSnapshot);
        }

        return fullSnapshot;
    }

    private Map<String, ClassState> loadClassStates(Session session) {
        Map<String, ClassState> stateMap = new HashMap<>();
        session.run(Neo4jSnapshotQuery.CLASSES_AND_FEATURES).forEachRemaining(row -> {
            ClassState cls = Neo4jModelSnapshotMapper.toClassState(row);
            stateMap.put(cls.getName(), cls);
        });
        return stateMap;
    }

    private void applyGeneralizations(Session session, Map<String, ClassState> stateMap) {
        session.run(Neo4jSnapshotQuery.GENERALIZATIONS).forEachRemaining(row -> {
            String child = row.get("child").asString();
            if (stateMap.containsKey(child)) {
                stateMap.get(child).getParents().add(row.get("parent").asString());
            }
        });
    }

    private void loadBinaryAssociations(Session session, FullModelSnapshot snapshot) {
        session.run(Neo4jSnapshotQuery.BINARY_ASSOCIATIONS).forEachRemaining(rec -> {
            AssociationState as = Neo4jModelSnapshotMapper.toBinaryAssociation(rec);
            snapshot.associations.put(as.name, as);
        });
    }

    private void loadTernaryAssociations(Session session, FullModelSnapshot snapshot) {
        session.run(Neo4jSnapshotQuery.TERNARY_ASSOCIATIONS).forEachRemaining(rec ->
                snapshot.ternaryAssociations.add(Neo4jModelSnapshotMapper.toTernaryAssociation(rec))
        );
    }
}
