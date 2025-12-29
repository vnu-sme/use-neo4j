package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.Neo4jImporter;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.sys.MSystem;

import javax.swing.*;
import java.awt.*;

public class ImportOptionsDialog extends JDialog {

    private final JCheckBox chkMetamodel;
    private final JCheckBox chkData;
    private final JButton btnImport;
    private final MainWindow mainWindow;
    private final Session useSession;

    public ImportOptionsDialog(MainWindow parent, Session session) {
        super(parent, "Import from Neo4j");
        this.mainWindow = parent;
        this.useSession = session;

        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel pnlOptions = new JPanel(new GridLayout(2, 1, 5, 5));

        chkMetamodel = new JCheckBox("Import Metamodel (Overwrite current model)", false);
        chkMetamodel.setToolTipText("Reconstruct Class Diagram from Neo4j :MetaClass nodes.");

        chkData = new JCheckBox("Import Data (Objects & Links)", true);
        chkData.setToolTipText("Load objects and links from Neo4j into current system state.");

        pnlOptions.add(chkMetamodel);
        pnlOptions.add(chkData);
        pnlOptions.setBorder(BorderFactory.createTitledBorder("Import Actions"));

        JPanel pnlBtn = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnImport = new JButton("Start Import");
        btnImport.setBackground(new Color(255, 140, 0)); // Màu cam
        JButton btnCancel = new JButton("Cancel");

        pnlBtn.add(btnImport);
        pnlBtn.add(btnCancel);

        add(pnlOptions, BorderLayout.CENTER);
        add(pnlBtn, BorderLayout.SOUTH);

        btnCancel.addActionListener(e -> dispose());
        btnImport.addActionListener(e -> runImport());

        pack();
        setLocationRelativeTo(parent);
        setResizable(false);
    }

    private void runImport() {
        if (!chkMetamodel.isSelected() && !chkData.isSelected()) {
            JOptionPane.showMessageDialog(this, "Please select at least one option!");
            return;
        }

        if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
            JOptionPane.showMessageDialog(this, "Not connected to Neo4j!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Cảnh báo nếu ghi đè Model
        if (chkMetamodel.isSelected() && useSession.system() != null) {
            int cf = JOptionPane.showConfirmDialog(this,
                    "Warning: Importing Metamodel will replace the current loaded model.\nAre you sure?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
            if (cf != JOptionPane.YES_OPTION) return;
        }

        dispose();

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                try (org.neo4j.driver.Session neoSession = Neo4jDriverManager.getInstance().openSession()) {
                    Neo4jImporter importer = new Neo4jImporter(neoSession, useSession, null);

                    // 1. IMPORT METAMODEL
                    if (chkMetamodel.isSelected()) {
                        publish("Importing Metamodel...");
                        MModel newModel = importer.importMetamodel();

                        // QUAN TRỌNG: Cập nhật System mới cho USE Application
                        if (newModel != null) {
                            SwingUtilities.invokeAndWait(() -> {
                                // Reset USE Session với Model mới tái tạo
                                MSystem newSystem = new MSystem(newModel);
                                useSession.setSystem(newSystem);
                                mainWindow.logWriter().println("New Model loaded from Neo4j.");
                            });
                        }
                    }

                    // 2. IMPORT DATA
                    if (chkData.isSelected()) {
                        publish("Importing Data...");
                        importer.importData();
                    }
                }
                return null;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String msg : chunks) mainWindow.logWriter().println("[Neo4j Import] " + msg);
            }

            @Override
            protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(mainWindow, "Import Completed!", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(mainWindow, "Import Failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}