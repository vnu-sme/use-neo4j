package org.uet.dse.neo4j.sync;

import org.neo4j.driver.Values;
import org.tzi.use.api.UseModelApi;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.uml.mm.MModel;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.sync.lock.LockManager;
import org.uet.dse.neo4j.sync.model.ModelDiff;
import org.uet.dse.neo4j.sync.model.ModelPushService;
import org.uet.dse.neo4j.sync.model.ModelSyncCoordinator;

public class RealtimeModelSyncCoordinator extends ModelSyncCoordinator {
    public RealtimeModelSyncCoordinator(Session session, MainWindow mainWindow, MModel useModel, UseModelApi modelApi) {
        super(session, mainWindow, useModel, modelApi);
    }

    public RealtimeModelSyncCoordinator(Session session, MainWindow mainWindow) {
        super(session, mainWindow);
    }

    public RealtimeModelSyncCoordinator(Session session) {
        super(session);
    }

    /**
     * for real-time utils
     */
    public void syncForwardRealtime() {
        String userIdentifier = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        // if the lock is not obtained, currently skip, not retrying yet
        LockManager.LockResult lock = LockManager.acquireLock(userIdentifier);
        if (!lock.success) {
            return;
        }

        try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
            ModelDiff diff = getModelSnapshotCompare().compareWithNeo4j();

            if (diff.hasDifference()) {

                //clear assoc
                if (!diff.neo4jOnlyAssociations.isEmpty()) {
                    for (String assocName : diff.neo4jOnlyAssociations) {
                        session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                                Values.parameters("name", assocName));
                    }
                }

                //clear class & enum
                if (!diff.neo4jOnlyClasses.isEmpty()) {
                    for (String label : diff.neo4jOnlyClasses) {
                        // Xóa tất cả các node có nhãn :Person
                        session.run("MATCH (n:`" + label + "`) DETACH DELETE n");

                        // ascade Delete
                        // Tìm Class và tất cả các node Attribute, Operation, Invariant, Param nối với nó
                        String deepDeleteCypher =
                                "MATCH (meta:MetaNode) WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass', 'NodeEnumeration'] " +
                                        "MATCH (c {name: $name})-[:InstanceOf]->(meta) " +

                                        "OPTIONAL MATCH (c)-[:HasAttribute]->(a) " +
                                        "OPTIONAL MATCH (c)-[:HasInvariant]->(inv) " +
                                        "OPTIONAL MATCH (c)-[:HasOperation]->(o) " +

                                        "OPTIONAL MATCH (o)-[:HasParam]->(p) " +
                                        "OPTIONAL MATCH (o)-[:HasPreCondition]->(pre) " +
                                        "OPTIONAL MATCH (o)-[:HasPostCondition]->(post) " +

                                        "WITH c, " +
                                        "collect(DISTINCT a) as attrs, " +
                                        "collect(DISTINCT inv) as invs, " +
                                        "collect(DISTINCT o) as ops, " +
                                        "collect(DISTINCT p) as params, " +
                                        "collect(DISTINCT pre) as pres, " +
                                        "collect(DISTINCT post) as posts " +

                                        "FOREACH (x IN attrs | DETACH DELETE x) " +
                                        "FOREACH (x IN invs | DETACH DELETE x) " +
                                        "FOREACH (x IN params | DETACH DELETE x) " +
                                        "FOREACH (x IN pres | DETACH DELETE x) " +
                                        "FOREACH (x IN posts | DETACH DELETE x) " +
                                        "FOREACH (x IN ops | DETACH DELETE x) " +
                                        "DETACH DELETE c";

                        session.run(deepDeleteCypher, Values.parameters("name", label));

                        session.run("MATCH ()-[r {associationName: $name}]-() DELETE r",
                                Values.parameters("name", label));

                        WorkLogManager.getInstance().log("CLEANUP", "Deep deleted class and all its components: " + label);
                    }
                }

                WorkLogManager.getInstance().log("AUTO_CLEANUP", "Removed obsolete elements: " + diff.neo4jOnlyClasses);
            }

            ModelPushService modelPushService = new ModelPushService(getModelApi());
            modelPushService.pushModelToNeo4j();
            //might be obsoleted
            modelPushService.updateModelVersion(session, userIdentifier);

            System.out.println("Real-time Sync: Database matches USE model.");

        } catch (Exception e) {
            System.err.println("Real-time Sync Error: " + e.getMessage());
            WorkLogManager.getInstance().log("REALTIME_ERROR", e.getMessage());
        } finally {
            LockManager.releaseLock();
        }
    }
}
