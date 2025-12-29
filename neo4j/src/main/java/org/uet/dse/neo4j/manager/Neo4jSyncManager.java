package org.uet.dse.neo4j.manager;

import org.neo4j.driver.Session;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.sys.MSystemState;

import javax.swing.*;

public class Neo4jSyncManager {
    private final Session session;
    private final JTextArea logArea;

    public Neo4jSyncManager(Session session, JTextArea logArea) {
        this.session = session;
        this.logArea = logArea;
    }

    /**
     * Feature 1: Sync Metamodel (Đưa cấu trúc Class Diagram xuống Neo4j)
     */
    public void syncMetamodel(MModel model) {
        log(">>> Bắt đầu đồng bộ Metamodel (Class Diagram)...");
        MetamodelSynchronizer metaSync = new MetamodelSynchronizer(session, logArea);
        metaSync.sync(model);
        log("<<< Hoàn tất đồng bộ Metamodel.");
    }

    /**
     * Feature 2: Sync Data (Đưa Object Runtime xuống Neo4j)
     */
    public void syncData(MSystemState state) {
        log(">>> Bắt đầu đồng bộ Dữ liệu (Runtime Objects)...");
        DataSynchronizer dataSync = new DataSynchronizer(session, logArea);
        dataSync.sync(state);
        log("<<< Hoàn tất đồng bộ Dữ liệu.");
    }

    private void log(String msg) {
        if (logArea != null) SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
        System.out.println("[Neo4j Sync] " + msg);
    }
}