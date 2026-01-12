package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import javax.swing.*;
import java.util.concurrent.*;

public class ChangeTracker {
    private static long lastKnownTimestamp = 0;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static boolean outOfSync = false;

    public static void startMonitoring(JLabel statusLabel) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (Neo4jDriverManager.getInstance().isConnected()) {
                    long dbTimestamp = getRemoteModelTimestamp();
                    if (lastKnownTimestamp != 0 && dbTimestamp > lastKnownTimestamp) {
                        outOfSync = true;
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("<html><font color='red'>⚠ DB Updated - Pull Required!</font></html>");
                        });
                    }
                    if (lastKnownTimestamp == 0) lastKnownTimestamp = dbTimestamp;
                }
            } catch (Exception e) {
                // Im lặng nếu mất mạng
            }
        }, 0, 5, TimeUnit.SECONDS); // 5 giây kiểm tra 1 lần
    }

    private static long getRemoteModelTimestamp() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {
            Result res = session.run("MATCH (v:ModelVersion {id: 'CURRENT'}) RETURN v.timestamp AS ts");
            if (res.hasNext()) return res.single().get("ts").asLong();
        }
        return 0;
    }

    public static void updateLocalTimestamp(long ts) {
        lastKnownTimestamp = ts;
        outOfSync = false;
    }

    // Trong lớp ChangeTracker.java
    public static void startMonitoring(JLabel modelLabel, JLabel objectLabel, org.tzi.use.main.Session session) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (Neo4jDriverManager.getInstance().isConnected()) {
                    SyncCoordinator mc = new SyncCoordinator(session);
                    SyncObjectCoordinator oc = new SyncObjectCoordinator(session.system());

                    // Lấy tóm tắt nhanh
                    String modelSum = mc.getQuickSummary();
                    String objectSum = oc.getQuickSummary();

                    SwingUtilities.invokeLater(() -> {
                        updateStatusLabel(modelLabel, "Model", modelSum);
                        updateStatusLabel(objectLabel, "Object", objectSum);
                    });
                }
            } catch (Exception e) { /* Silent ignore */ }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static void updateStatusLabel(JLabel label, String type, String summary) {
        if (summary.equals("All Synced")) {
            label.setText("<html>" + type + ": <font color='green'>● Fully Synchronized</font></html>");
        } else {
            label.setText("<html>" + type + ": <font color='red'>● Out of Sync (" + summary + ")</font></html>");
        }
    }
}