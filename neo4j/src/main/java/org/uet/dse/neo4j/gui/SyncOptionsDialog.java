package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.Neo4jSyncManager;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.sys.MSystemState;

import javax.swing.*;
import java.awt.*;

public class SyncOptionsDialog extends JDialog {

    private final JCheckBox chkMetamodel; // Đổi tên từ chkSchema cho đúng ngữ nghĩa
    private final JCheckBox chkData;
    private final JButton btnSync;
    private final Session session;
    private final MainWindow mainWindow;

    public SyncOptionsDialog(MainWindow parent, Session session) {
        super(parent, "Synchronization Manager");
        this.mainWindow = parent;
        this.session = session;

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Panel Options ---
        JPanel pnlOptions = new JPanel(new GridLayout(2, 1, 5, 5));

        // Option 1: Metamodel (Class Diagram structure)
        chkMetamodel = new JCheckBox("Sync Metamodel (Class Diagram structure)", true);
        chkMetamodel.setToolTipText("Exports Classes, Attributes, Inheritance, and Associations as graph metadata.");

        // Option 2: Data (Runtime Objects)
        chkData = new JCheckBox("Sync Data (Runtime Objects & Links)", true);
        chkData.setToolTipText("Exports current MSystemState (Objects and Links) to Neo4j.");

        pnlOptions.add(chkMetamodel);
        pnlOptions.add(chkData);
        pnlOptions.setBorder(BorderFactory.createTitledBorder("Select Scope"));

        // --- Buttons ---
        JPanel pnlBtn = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnSync = new JButton("Start Sync");
        btnSync.setBackground(new Color(60, 179, 113)); // Xanh Neo4j
        btnSync.setForeground(Color.BLACK);

        JButton btnCancel = new JButton("Cancel");

        pnlBtn.add(btnSync);
        pnlBtn.add(btnCancel);

        add(pnlOptions, BorderLayout.CENTER);
        add(pnlBtn, BorderLayout.SOUTH);

        // --- Events ---
        btnCancel.addActionListener(e -> dispose());
        btnSync.addActionListener(e -> runSync());

        pack();
        setLocationRelativeTo(parent);
        setResizable(false);
    }

    private void runSync() {
        // 1. Validate Selection
        if (!chkMetamodel.isSelected() && !chkData.isSelected()) {
            JOptionPane.showMessageDialog(this, "Please select at least one option!", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 2. Validate Connection
        if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(this,
                    "Not connected to Neo4j! Please connect first via the Plugin Menu.",
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 3. Validate USE State
        if (session.system() == null) {
            JOptionPane.showMessageDialog(this,
                    "No USE Model loaded! Please open a .use file first.",
                    "System Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        dispose(); // Đóng dialog

        // 4. Run Background Worker
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (org.neo4j.driver.Session neoSession = Neo4jDriverManager.getInstance().openSession()) {

                    // Khởi tạo Manager mới (chúng ta truyền null cho JTextArea vì SwingWorker sẽ handle log qua publish)
                    Neo4jSyncManager manager = new Neo4jSyncManager(neoSession, null);

                    // --- TASK A: SYNC METAMODEL ---
                    if (chkMetamodel.isSelected()) {
                        publish("--- Starting Metamodel Sync ---");
                        MModel model = session.system().model();
                        if (model != null) {
                            manager.syncMetamodel(model);
                            publish("Metamodel synced successfully.");
                        }
                    }

                    // --- TASK B: SYNC DATA ---
                    if (chkData.isSelected()) {
                        publish("--- Starting Data Sync ---");
                        MSystemState state = session.system().state();
                        if (state != null) {
                            manager.syncData(state);
                            publish("Data synced successfully.");
                        } else {
                            publish("Warning: System state is null.");
                        }
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                // Đẩy log ra cửa sổ Log chính của USE
                for (String msg : chunks) {
                    mainWindow.logWriter().println("[Neo4j Sync] " + msg);
                }
            }

            @Override
            protected void done() {
                try {
                    get(); // Kiểm tra xem có Exception không
                    JOptionPane.showMessageDialog(mainWindow,
                            "Synchronization Completed Successfully!",
                            "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    e.printStackTrace();
                    mainWindow.logWriter().println("[Neo4j Sync Error] " + e.getMessage());
                    JOptionPane.showMessageDialog(mainWindow,
                            "Sync Failed:\n" + e.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}