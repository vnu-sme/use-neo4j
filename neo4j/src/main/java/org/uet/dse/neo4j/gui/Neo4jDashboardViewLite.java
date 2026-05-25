package org.uet.dse.neo4j.gui;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.sync.model.ModelSyncCoordinator;
import org.uet.dse.neo4j.sync.object.ObjectSyncCoordinator;

import javax.swing.*;
import java.awt.*;

public class Neo4jDashboardViewLite extends JDialog {
    private static Neo4jDashboardViewLite instance;
    private final Session session;
    private final MainWindow mainWindow;

    // UI Components
    private JLabel lblUri, lblDb, lblUser, lblStatus;
    private JLabel lblModelStatus, lblObjectStatus;

    public static Neo4jDashboardViewLite getInstance(MainWindow parent, Session session) {
        if (instance == null) {
            instance = new Neo4jDashboardViewLite(parent, session);
        }
        return instance;
    }

    private Neo4jDashboardViewLite(MainWindow parent, Session session) {
        super(parent, "Neo4j Database Connection Status", false);
        this.session = session;
        this.mainWindow = parent;

        this.setSize(600, 180);
        this.setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // 1. Thông tin kết nối (Metadata)
        add(createConnectionInfoPanel(), BorderLayout.NORTH);

        this.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        this.setLocationRelativeTo(parent);
    }

    private JPanel createConnectionInfoPanel() {
        JPanel pnl = new JPanel(new GridLayout(2, 2, 10, 10));
        pnl.setBorder(BorderFactory.createTitledBorder("Database Metadata"));

        lblUri = new JLabel("Server: -");
        lblDb = new JLabel("Database: -");
        lblUser = new JLabel("User: -");
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("SansSerif", Font.BOLD, 12));

        pnl.add(lblUri);
        pnl.add(lblDb);
        pnl.add(lblUser);
        pnl.add(lblStatus);
        return pnl;
    }

    private JPanel createSyncStatusPanel() {
        JPanel pnl = new JPanel(new GridLayout(2, 1, 10, 10));
        pnl.setBorder(BorderFactory.createTitledBorder("Synchronization Status"));

        lblModelStatus = new JLabel("Model: Checking...");
        lblObjectStatus = new JLabel("Objects: Checking...");
        
        lblModelStatus.setFont(new Font("SansSerif", Font.PLAIN, 13));
        lblObjectStatus.setFont(new Font("SansSerif", Font.PLAIN, 13));

        pnl.add(lblModelStatus);
        pnl.add(lblObjectStatus);
        return pnl;
    }

    private JPanel createActionPanel() {
        JPanel pnl = new JPanel(new GridLayout(2, 2, 10, 10));
        pnl.setBorder(BorderFactory.createTitledBorder("Quick Actions"));

        JButton btnPushModel = new JButton("Push Model to Neo4j");
        JButton btnPullModel = new JButton("Pull Model from Neo4j");
        JButton btnPushObject = new JButton("Push Objects to Neo4j");
        JButton btnPullObject = new JButton("Pull Objects from Neo4j");

        // Gán sự kiện
        ModelSyncCoordinator modelCoord = new ModelSyncCoordinator(session, mainWindow);
        ObjectSyncCoordinator objectCoord = new ObjectSyncCoordinator(session.system());

        btnPushModel.addActionListener(e -> modelCoord.syncForward());
        btnPullModel.addActionListener(e -> modelCoord.syncBackward());
        btnPushObject.addActionListener(e -> objectCoord.syncObjectsForward(true));
        btnPullObject.addActionListener(e -> objectCoord.syncObjectsBackward(true));

        pnl.add(btnPushModel);
        pnl.add(btnPullModel);
        pnl.add(btnPushObject);
        pnl.add(btnPullObject);

        return pnl;
    }

    public void refreshData() {
        Neo4jDriverManager manager = Neo4jDriverManager.getInstance();
        if (manager == null || !manager.isConnected()) {
            lblStatus.setText("Status: Disconnected");
            lblStatus.setForeground(Color.RED);
            return;
        }

        lblUri.setText("Server: " + manager.getUri());
        lblDb.setText("Database: " + manager.getActiveDatabase());
        lblUser.setText("User: " + manager.getUser());
        
        lblStatus.setText("Status: Connected");
        lblStatus.setForeground(new Color(0, 120, 0));
    }
}