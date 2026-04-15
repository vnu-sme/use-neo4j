package org.uet.dse.neo4j.gui;

import org.neo4j.driver.Session;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model_trafo.gui.Neo4jCorrObjectRepository;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class TggObjectManagerDialog extends JDialog {
    private DefaultTableModel modelObj, modelLink;
    private JTable tblObj, tblLink;
    private Neo4jCorrObjectRepository repo;
    private String currentModelName;

    public TggObjectManagerDialog(Frame owner, String modelName) {
        super(owner, "TGG Triple Graph Manager (Objects & Links)", false);
        this.currentModelName = modelName;
        this.repo = new Neo4jCorrObjectRepository();

        initComponents();

        refreshObjects();
        refreshLinks();
    }

    private void initComponents() {
        setSize(900, 650);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("1. Correspondence Objects", createObjectPanel());
        tabs.addTab("2. AssociateWith Links", createLinkPanel());

        add(tabs, BorderLayout.CENTER);

        JPanel pnlStatus = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pnlStatus.add(new JLabel("Active Model: " + currentModelName));
        add(pnlStatus, BorderLayout.SOUTH);
    }

    private JPanel createObjectPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));

        modelObj = new DefaultTableModel(new Object[]{"Object ID (use_id)", "Class Type (Label)"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        tblObj = new JTable(modelObj);
        p.add(new JScrollPane(tblObj), BorderLayout.CENTER);

        JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnAdd = new JButton("Create Object");
        JButton btnDel = new JButton("Delete Object");
        JButton btnRefresh = new JButton("Refresh");

        btnAdd.addActionListener(e -> {
            // Không truyền modelName cũ nữa, để Dialog tự handle 2 ô input
            TggObjectCreationDialog d = new TggObjectCreationDialog((Frame)getOwner(), repo);
            d.setVisible(true);
            refreshObjects();
        });

        btnDel.addActionListener(e -> deleteSelectedObject());
        btnRefresh.addActionListener(e -> refreshObjects());

        pnlBtns.add(btnAdd);
        pnlBtns.add(btnDel);
        pnlBtns.add(btnRefresh);
        p.add(pnlBtns, BorderLayout.NORTH);

        return p;
    }

    private JPanel createLinkPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));

        modelLink = new DefaultTableModel(new Object[]{"Association Name", "Source (From)", "Target (To)"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        tblLink = new JTable(modelLink);
        p.add(new JScrollPane(tblLink), BorderLayout.CENTER);

        JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnAdd = new JButton("Create Link");
        JButton btnDel = new JButton("Delete Link");
        JButton btnRefresh = new JButton("Refresh");

        btnAdd.addActionListener(e -> {
            TggLinkCreationDialog d = new TggLinkCreationDialog((Frame)getOwner(), repo);
            d.setVisible(true);
            refreshLinks();
        });

        btnDel.addActionListener(e -> deleteSelectedLink());
        btnRefresh.addActionListener(e -> refreshLinks());

        pnlBtns.add(btnAdd);
        pnlBtns.add(btnDel);
        pnlBtns.add(btnRefresh);
        p.add(pnlBtns, BorderLayout.NORTH);

        return p;
    }

    private void refreshObjects() {
        modelObj.setRowCount(0);
        String query = "MATCH (o {isCorrespondence: true})-[:ObjectInstanceOf]->(cls) " +
            "RETURN o.use_id AS name, cls.name AS type";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            List<Map<String, Object>> data = s.run(query).list(r -> r.asMap());
            for (Map<String, Object> m : data) {
                modelObj.addRow(new Object[]{m.get("name"), m.get("type")});
            }
        }
    }

    private void refreshLinks() {
        modelLink.setRowCount(0);
        try {
            List<Map<String, Object>> data = repo.listAllLinks();
            for (Map<String, Object> m : data) {
                modelLink.addRow(new Object[]{m.get("name"), m.get("src"), m.get("tgt")});
            }
        } catch (Exception e) {
            handleError("Lỗi khi load Links", e);
        }
    }

    private void deleteSelectedObject() {
        int row = tblObj.getSelectedRow();
        if (row == -1) return;

        String id = modelObj.getValueAt(row, 0).toString();
        int confirm = JOptionPane.showConfirmDialog(this, "Xóa Object '" + id + "' và các link liên quan?");
        if (confirm == JOptionPane.YES_OPTION) {
            repo.deleteObject(id);
            refreshObjects();
            refreshLinks();
        }
    }

    private void deleteSelectedLink() {
        int row = tblLink.getSelectedRow();
        if (row == -1) return;

        String name = modelLink.getValueAt(row, 0).toString();
        String src = modelLink.getValueAt(row, 1).toString();
        String tgt = modelLink.getValueAt(row, 2).toString();

        int confirm = JOptionPane.showConfirmDialog(this, "Xóa liên kết '" + name + "'?");
        if (confirm == JOptionPane.YES_OPTION) {
            repo.deleteLink(name, src, tgt);
            refreshLinks();
        }
    }

    private void handleError(String title, Exception e) {
        JOptionPane.showMessageDialog(this, title + ": " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
}