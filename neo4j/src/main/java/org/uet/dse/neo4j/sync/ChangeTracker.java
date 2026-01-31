package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.tzi.use.gui.main.MainWindow;
import org.uet.dse.neo4j.collaboration.ConsensusService;
import org.uet.dse.neo4j.gui.ProposalVotingDialog;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import javax.swing.*;
import java.util.concurrent.*;

public class ChangeTracker {
    private static long lastKnownTimestamp = 0;
    private static String lastProposalId = "";
    private static boolean isApprovalDialogShown = false;
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

    /**
     * Kích hoạt bộ giám sát tổng thể
     */
    public static void startMonitoring(MainWindow mainWindow, org.tzi.use.main.Session session, JLabel lblModel, JLabel lblObj) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (Neo4jDriverManager.getInstance().isConnected()) {
                    // 1. HEARTBEAT: Giữ trạng thái ONLINE
                    ConsensusService.getInstance().heartbeat();

                    // 2. DASHBOARD STATUS: Cập nhật đèn báo Xanh/Đỏ
                    updateUIStatus(session, lblModel, lblObj);

                    // 3. COLLABORATION: Kiểm tra Bỏ phiếu/Đề xuất
                    checkForConsensusEvents(mainWindow, session);
                }
            } catch (Exception e) {
                // Log im lặng để không làm phiền người dùng khi mất mạng
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static void updateUIStatus(org.tzi.use.main.Session session, JLabel lblModel, JLabel lblObj) {
        ModelSyncCoordinator mc = new ModelSyncCoordinator(session);
        ObjectSyncCoordinator oc = new ObjectSyncCoordinator(session.system());

        String modelSum = mc.getQuickSummary();
        String objectSum = oc.getQuickSummary();

        SwingUtilities.invokeLater(() -> {
            updateStatusLabel(lblModel, "Model", modelSum);
            updateStatusLabel(lblObj, "Object", objectSum);
        });
    }

    private static void checkForConsensusEvents(MainWindow mainWindow, org.tzi.use.main.Session session) {
        ConsensusService service = ConsensusService.getInstance();

        // TÌNH HUỐNG 1: Có người khác gửi đề xuất -> Hiện Popup bỏ phiếu
        var pending = service.getLatestPendingProposal();
        if (pending != null) {
            String pId = pending.get("id").asString();
            if (!pId.equals(lastProposalId)) {
                lastProposalId = pId;
                SwingUtilities.invokeLater(() -> {
                    new ProposalVotingDialog(mainWindow, session, pId,
                            pending.get("proposer").asString(),
                            pending.get("data").asString()).setVisible(true);
                });
            }
        } else {
            lastProposalId = ""; // Reset khi không còn proposal
        }

        // TÌNH HUỐNG 2: Đề xuất của mình đã được duyệt -> Thông báo để thực thi Push
        if (service.checkMyProposalApproval()) {
            if (!isApprovalDialogShown) {
                isApprovalDialogShown = true;
                SwingUtilities.invokeLater(() -> notifyUserToExecuteFinalSync(mainWindow, session));
            }
        } else {
            isApprovalDialogShown = false;
        }
    }

    private static void notifyUserToExecuteFinalSync(MainWindow mainWindow, org.tzi.use.main.Session session) {
        int choice = JOptionPane.showConfirmDialog(mainWindow,
                "<html><b>Consensus Reached!</b><br/>Your Model Proposal has been APPROVED by all users.<br/>Execute the final Synchronization now?</html>",
                "Proposal Approved", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            ConsensusService.getInstance().finalizeProposal();
            new ModelSyncCoordinator(session, mainWindow).syncForward(); // Thực hiện PUSH thực sự
        }
    }

    // Trong ChangeTracker.java
    private static void updateStatusLabel(JLabel label, String type, String summary) {
        // SỬA: Đưa chuỗi hằng số lên trước để tránh NullPointerException
        if ("All Synced".equals(summary)) {
            label.setText("<html>" + type + ": <font color='green'>● Fully Synchronized</font></html>");
        } else if (summary == null || "Check Failed".equals(summary)) {
            label.setText("<html>" + type + ": <font color='gray'>● Connection Error / Empty DB</font></html>");
        } else {
            label.setText("<html>" + type + ": <font color='red'>● Out of Sync (" + summary + ")</font></html>");
        }
    }
}