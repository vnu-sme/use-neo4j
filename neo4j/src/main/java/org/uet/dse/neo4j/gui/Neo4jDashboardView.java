package org.uet.dse.neo4j.gui;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.phase2.SessionManager;
import org.uet.dse.neo4j.phase2.WorkLogManager;
import org.uet.dse.neo4j.sync.ChangeTracker;
import org.uet.dse.neo4j.sync.ModelSyncCoordinator;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

import javax.swing.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class Neo4jDashboardView extends JDialog {
    private static Neo4jDashboardView instance;
    private final Session session;

    // UI Components
    private JLabel lblUri, lblDb, lblUser, lblVersion, lblKey, lblDate;
    private JLabel lblModelStatus, lblObjectStatus, lblActiveStatus;
    private JButton btnToggleActive;

    public static Neo4jDashboardView getInstance(MainWindow parent, Session session) {
        if (instance == null) {
            instance = new Neo4jDashboardView(parent, session);
        }
        return instance;
    }

    private Neo4jDashboardView(MainWindow parent, Session session) {
        super(parent, "Neo4j Collaboration Dashboard", false);
        this.session = session;
        this.setSize(850, 600); // Tăng kích thước để xem bảng Log dễ hơn
        this.setLayout(new BorderLayout());

        // 1. Panel thông tin Metadata cố định ở trên đầu (North)
        add(createMetadataPanel(), BorderLayout.NORTH);

        // 2. TẠO HỆ THỐNG TAB Ở GIỮA
        JTabbedPane tabbedPane = new JTabbedPane();

        // --- TAB 1: QUẢN LÝ ĐỒNG BỘ ---
        JPanel pnlSyncManager = new JPanel(new BorderLayout(10, 10));
        pnlSyncManager.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlSyncManager.add(createStatusPanel(), BorderLayout.CENTER);
        pnlSyncManager.add(createSyncControlPanel(), BorderLayout.SOUTH);
        tabbedPane.addTab("Sync & Management", new ImageIcon(), pnlSyncManager, "Control data flow");

        // --- TAB 2: NHẬT KÝ THAO TÁC (Audit Log) ---
        tabbedPane.addTab("Collaborative Logs", new ImageIcon(), new Neo4jLogTab(), "Monitor team actions");

        add(tabbedPane, BorderLayout.CENTER);

        // Khởi tạo dữ liệu
        refreshData();

        // Start monitoring (truyền label vào ChangeTracker như cũ)
        ChangeTracker.startMonitoring(lblModelStatus);

        this.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        this.setLocationRelativeTo(parent);
    }
    private JPanel createMetadataPanel() {
        JPanel pnl = new JPanel(new GridLayout(3, 2, 10, 8));
        pnl.setBorder(BorderFactory.createTitledBorder("Database Information"));
        pnl.setBackground(new Color(240, 248, 255)); // AliceBlue

        lblUri = new JLabel("Server: -");
        lblDb = new JLabel("Database: -");
        lblUser = new JLabel("User: -");
        lblVersion = new JLabel("Neo4j Version: -");
        lblKey = new JLabel("Session Key: -");
        lblDate = new JLabel("Connected Date: -");

        pnl.add(lblUri); pnl.add(lblDb);
        pnl.add(lblUser); pnl.add(lblVersion);
        pnl.add(lblKey); pnl.add(lblDate);
        return pnl;
    }

    private JPanel createCenterPanel() {
        JPanel pnlMain = new JPanel(new BorderLayout(0, 10));

        // Trạng thái Sync
        JPanel pnlSync = new JPanel(new GridLayout(2, 1, 5, 5));
        pnlSync.setBorder(BorderFactory.createTitledBorder("Real-time Synchronization Status"));
        lblModelStatus = new JLabel("Model Status: Checking...");
        lblObjectStatus = new JLabel("Object Status: Checking...");
        lblModelStatus.setFont(new Font("SansSerif", Font.BOLD, 13));
        lblObjectStatus.setFont(new Font("SansSerif", Font.BOLD, 13));
        pnlSync.add(lblModelStatus);
        pnlSync.add(lblObjectStatus);

        // Trạng thái Collaboration
        JPanel pnlActive = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        pnlActive.setBorder(BorderFactory.createTitledBorder("Working Status"));
        lblActiveStatus = new JLabel("STATUS: UNKNOWN");
        lblActiveStatus.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnToggleActive = new JButton("Set Inactive");
        btnToggleActive.addActionListener(e -> toggleActiveStatus());
        pnlActive.add(lblActiveStatus);
        pnlActive.add(btnToggleActive);

        pnlMain.add(pnlSync, BorderLayout.CENTER);
        pnlMain.add(pnlActive, BorderLayout.SOUTH);
        return pnlMain;
    }

    private JPanel createSyncControlPanel() {
        JPanel pnlActions = new JPanel(new BorderLayout(10, 10));

        // 4 Nút bấm chính
        JPanel pnlButtons = new JPanel(new GridLayout(2, 2, 10, 10));
        pnlButtons.setBorder(BorderFactory.createTitledBorder("Manual Sync Controls"));

        JButton btnPushModel = new JButton("Push Model (Java -> DB)");
        JButton btnPullModel = new JButton("Pull Model (DB -> Java)");
        JButton btnPushObject = new JButton("Push Objects (Java -> DB)");
        JButton btnPullObject = new JButton("Pull Objects (DB -> Java)");

        // Liên kết các bộ điều phối
        btnPushModel.addActionListener(e -> new ModelSyncCoordinator(session).syncForward());
        btnPullModel.addActionListener(e -> new ModelSyncCoordinator(session).syncBackward());
        btnPushObject.addActionListener(e -> new ObjectSyncCoordinator(session.system()).syncObjectsForward(true));
        btnPullObject.addActionListener(e -> new ObjectSyncCoordinator(session.system()).syncObjectsBackward(true));

        pnlButtons.add(btnPushModel); pnlButtons.add(btnPullModel);
        pnlButtons.add(btnPushObject); pnlButtons.add(btnPullObject);

        // Nút Setting & Log
        JPanel pnlMisc = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnSettings = new JButton("Settings");
        btnSettings.addActionListener(e -> new SyncSettingsDialog(null).setVisible(true));
        pnlMisc.add(btnSettings);

        pnlActions.add(pnlButtons, BorderLayout.CENTER);
        pnlActions.add(pnlMisc, BorderLayout.SOUTH);
        return pnlActions;
    }

    public void refreshData() {
        Neo4jDriverManager manager = Neo4jDriverManager.getInstance();
        if (manager == null || !manager.isConnected()) return;

        Map<String, Object> stats = manager.getDatabaseStats();
        SessionManager sm = manager.getSessionManager();

        lblUri.setText("Server: " + manager.getUri());
        lblDb.setText("Database: " + manager.getActiveDatabase());
        lblUser.setText("User: " + manager.getUser());
        lblVersion.setText("Neo4j Version: " + stats.getOrDefault("version", "N/A"));
        lblKey.setText("Session Key: " + sm.getSessionKey());
        lblDate.setText("Connected Date: " + sm.getConnectionDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));

        updateStatusUI(sm.isActive());
    }

    private void toggleActiveStatus() {
        SessionManager sm = Neo4jDriverManager.getInstance().getSessionManager();
        boolean newStatus = !sm.isActive();
        sm.setActive(newStatus);
        updateStatusUI(newStatus);
        WorkLogManager.getInstance().log("STATUS_UPDATE", "User set to " + (newStatus ? "ACTIVE" : "INACTIVE"));
    }

    private void updateStatusUI(boolean isActive) {
        lblActiveStatus.setText("STATUS: " + (isActive ? "ACTIVE" : "INACTIVE"));
        lblActiveStatus.setForeground(isActive ? new Color(0, 150, 0) : Color.RED);
        btnToggleActive.setText(isActive ? "Go Inactive" : "Go Active");
    }

    private JPanel createStatusPanel() {
        JPanel pnlMain = new JPanel(new GridLayout(2, 1, 10, 10));
        pnlMain.setBorder(BorderFactory.createTitledBorder("Live Synchronization Status"));

        // --- Row 1: Model Status ---
        JPanel pnlModel = new JPanel(new BorderLayout());
        lblModelStatus = new JLabel("Model Status: Initializing...");
        lblModelStatus.setFont(new Font("SansSerif", Font.BOLD, 12));
        JButton btnModelInspect = new JButton("Inspect & Selective Sync");
        btnModelInspect.addActionListener(e -> new SyncDetailDialog("Model",
                new ModelSyncCoordinator(session),
                new ObjectSyncCoordinator(session.system())).setVisible(true));

        pnlModel.add(lblModelStatus, BorderLayout.CENTER);
        pnlModel.add(btnModelInspect, BorderLayout.EAST);

        // --- Row 2: Object Status ---
        JPanel pnlObject = new JPanel(new BorderLayout());
        lblObjectStatus = new JLabel("Object Status: Initializing...");
        lblObjectStatus.setFont(new Font("SansSerif", Font.BOLD, 12));
        JButton btnObjectInspect = new JButton("Inspect & Selective Sync");
        btnObjectInspect.addActionListener(e -> new SyncDetailDialog("Object",
                new ModelSyncCoordinator(session),
                new ObjectSyncCoordinator(session.system())).setVisible(true));

        pnlObject.add(lblObjectStatus, BorderLayout.CENTER);
        pnlObject.add(btnObjectInspect, BorderLayout.EAST);

        pnlMain.add(pnlModel);
        pnlMain.add(pnlObject);
        return pnlMain;
    }
}