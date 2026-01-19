package org.uet.dse.neo4j.gui;

import org.tzi.use.main.Session;
import org.uet.dse.neo4j.sync.ModelSyncCoordinator;
import org.uet.dse.neo4j.sync.ObjectSyncCoordinator;

import javax.swing.*;
import java.awt.*;

public class SyncStatusDashboard extends JPanel {
    private JLabel lblModelStatus, lblObjectStatus;
    private JButton btnModelDetail, btnObjectDetail;
    private final ModelSyncCoordinator modelCoordinator;
    private final ObjectSyncCoordinator objectCoordinator;

    public SyncStatusDashboard(Session session) {
        // Khởi tạo các bộ điều phối
        // Bây giờ constructor 1 tham số đã tồn tại nên sẽ không lỗi
        this.modelCoordinator = new ModelSyncCoordinator(session);
        this.objectCoordinator = new ObjectSyncCoordinator(session.system());

        this.setLayout(new GridLayout(2, 1, 10, 10));

        // --- UI Model ---
        JPanel pnlModel = new JPanel(new BorderLayout());
        pnlModel.setBorder(BorderFactory.createTitledBorder("UML Model Sync"));
        lblModelStatus = new JLabel("Status: Initializing...");
        btnModelDetail = new JButton("Details");
        pnlModel.add(lblModelStatus, BorderLayout.CENTER);
        pnlModel.add(btnModelDetail, BorderLayout.EAST);

        // --- UI Object ---
        JPanel pnlObject = new JPanel(new BorderLayout());
        pnlObject.setBorder(BorderFactory.createTitledBorder("Object Sync"));
        lblObjectStatus = new JLabel("Status: Initializing...");
        btnObjectDetail = new JButton("Details");
        pnlObject.add(lblObjectStatus, BorderLayout.CENTER);
        pnlObject.add(btnObjectDetail, BorderLayout.EAST);

        this.add(pnlModel);
        this.add(pnlObject);

        // Bắt đầu quét thời gian thực
        startRealTimeMonitor();
    }

    private void startRealTimeMonitor() {
        // Check mỗi 5 giây
        Timer timer = new Timer(5000, e -> {
            new SwingWorker<Boolean[], Void>() {
                @Override
                protected Boolean[] doInBackground() {
                    return new Boolean[] {
                            modelCoordinator.isQuickSyncCheck(),
                            objectCoordinator.isQuickSyncCheck()
                    };
                }

                @Override
                protected void done() {
                    try {
                        Boolean[] results = get();
                        updateLabel(lblModelStatus, results[0]);
                        updateLabel(lblObjectStatus, results[1]);
                    } catch (Exception ex) {
                        lblModelStatus.setText("Error checking status");
                    }
                }
            }.execute();
        });
        timer.start();
    }

    private void updateLabel(JLabel label, boolean inSync) {
        if (inSync) {
            label.setText("<html><font color='green'>● Synchronized</font></html>");
        } else {
            label.setText("<html><font color='red'>● Out of Sync</font></html>");
        }
    }
}