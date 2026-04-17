package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.model_trafo.gui.Neo4jCorrObjectRepository;
import org.uet.dse.neo4j.model_trafo.gui.Neo4jDirectObjectRepository;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;

public class TggObjectManagerDialog extends JDialog {
    private DefaultTableModel modelObj, modelLink, modelRegObj, modelRegLink;
    private JTable tblObj, tblLink, tblRegObj, tblRegLink;
    private Neo4jCorrObjectRepository repo;
    private Neo4jDirectObjectRepository directRepo;
    private String currentModelName;
    private JTextArea txtLog;

    public TggObjectManagerDialog(Frame owner, String modelName) {
        super(owner, "TGG Triple Graph Manager (Objects & Links)", false);
        this.currentModelName = modelName;
        this.repo = new Neo4jCorrObjectRepository();
        this.directRepo = new Neo4jDirectObjectRepository();

        initComponents();

        // Load dữ liệu ban đầu cho tất cả các tab
        refreshAllData();
    }

    private void initComponents() {
        setSize(1100, 850);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(5, 5));

        txtLog = new JTextArea(8, 0);
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(Color.GREEN);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("1. Correspondence Objects", createObjectPanel());
        mainTabs.addTab("2. AssociateWith Links", createLinkPanel());
        mainTabs.addTab("3. Regular Model Editor", createRegularModelPanel());

        JPanel pnlStatus = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pnlStatus.add(new JLabel("Active Model Context: " + currentModelName));

        JPanel pnlBottom = new JPanel(new BorderLayout());
        pnlBottom.add(pnlStatus, BorderLayout.NORTH);
        pnlBottom.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        pnlBottom.setBorder(new TitledBorder("System Log"));

        add(mainTabs, BorderLayout.CENTER);
        add(pnlBottom, BorderLayout.SOUTH);
    }

    private void appendLog(String msg) {
        txtLog.append("> " + msg + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }

    private void refreshAllData() {
        refreshObjects();
        refreshLinks();
        refreshRegularData();
    }

    // --- TAB 1: CORR OBJECTS ---
    private JPanel createObjectPanel() {
        JPanel main = new JPanel(new BorderLayout(5, 5));
        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new TitledBorder("Create Correspondence Object"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(5, 10, 5, 10);

        JTextField tSrc = new JTextField("Families"), tTrg = new JTextField("Persons");
        JTextField tCls = new JTextField("FR2PR"), tOID = new JTextField("c1");
        JButton btnAdd = new JButton("Create Corr Object");
        JButton btnRefresh = new JButton("Refresh");

        gbc.gridy=0; gbc.gridx=0; pnlInput.add(new JLabel("Src Model:"), gbc); gbc.gridx=1; pnlInput.add(tSrc, gbc);
        gbc.gridy=1; gbc.gridx=0; pnlInput.add(new JLabel("Trg Model:"), gbc); gbc.gridx=1; pnlInput.add(tTrg, gbc);
        gbc.gridy=2; gbc.gridx=0; pnlInput.add(new JLabel("Class:"), gbc); gbc.gridx=1; pnlInput.add(tCls, gbc);
        gbc.gridy=3; gbc.gridx=0; pnlInput.add(new JLabel("OID:"), gbc); gbc.gridx=1; pnlInput.add(tOID, gbc);
        gbc.gridy=4; gbc.gridx=0; pnlInput.add(btnRefresh, gbc); gbc.gridx=1; pnlInput.add(btnAdd, gbc);

        modelObj = new DefaultTableModel(new Object[]{"OID (use_id)", "Class Type"}, 0);
        tblObj = new JTable(modelObj);
        JPanel pnlList = new JPanel(new BorderLayout());
        pnlList.add(new JScrollPane(tblObj), BorderLayout.CENTER);
        JButton btnDel = new JButton("Delete Selected Object");
        btnDel.setForeground(Color.RED);
        pnlList.add(btnDel, BorderLayout.SOUTH);

        main.add(pnlInput, BorderLayout.NORTH); main.add(pnlList, BorderLayout.CENTER);

        btnAdd.addActionListener(e -> {
            try {
                repo.createCorrObject(tCls.getText(), tOID.getText(), tSrc.getText(), tTrg.getText());
                appendLog("SUCCESS: Created Corr Object [" + tOID.getText() + "]");
                refreshObjects();
            } catch (Exception ex) { appendLog("ERROR: " + ex.getMessage()); }
        });
        btnRefresh.addActionListener(e -> refreshObjects());
        btnDel.addActionListener(e -> deleteSelectedObject());
        return main;
    }

    // --- TAB 2: CORR LINKS ---
    private JPanel createLinkPanel() {
        JPanel main = new JPanel(new BorderLayout(5, 5));
        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new TitledBorder("Create AssociateWith (Corr Link)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(2, 10, 2, 10);

        JTextField tS = new JTextField(), tT = new JTextField(), tN = new JTextField("FR2PR_FamilyRegister");
        JTextField tSC = new JTextField("FR2PR"), tTC = new JTextField("FamilyRegister");
        JButton btnAdd = new JButton("Connect Objects");
        JButton btnRefresh = new JButton("Refresh");

        gbc.gridy=0; gbc.gridx=0; pnlInput.add(new JLabel("From OID:"), gbc); gbc.gridx=1; pnlInput.add(tS, gbc);
        gbc.gridy=1; gbc.gridx=0; pnlInput.add(new JLabel("To OID:"), gbc); gbc.gridx=1; pnlInput.add(tT, gbc);
        gbc.gridy=2; gbc.gridx=0; pnlInput.add(new JLabel("Assoc Name:"), gbc); gbc.gridx=1; pnlInput.add(tN, gbc);
        gbc.gridy=3; gbc.gridx=0; pnlInput.add(new JLabel("Src Class:"), gbc); gbc.gridx=1; pnlInput.add(tSC, gbc);
        gbc.gridy=4; gbc.gridx=0; pnlInput.add(new JLabel("Tgt Class:"), gbc); gbc.gridx=1; pnlInput.add(tTC, gbc);
        gbc.gridy=5; gbc.gridx=0; pnlInput.add(btnRefresh, gbc); gbc.gridx=1; pnlInput.add(btnAdd, gbc);

        modelLink = new DefaultTableModel(new Object[]{"Association", "Source", "Target"}, 0);
        tblLink = new JTable(modelLink);
        JPanel pnlList = new JPanel(new BorderLayout());
        pnlList.add(new JScrollPane(tblLink), BorderLayout.CENTER);
        JButton btnDel = new JButton("Delete Selected Link");
        pnlList.add(btnDel, BorderLayout.SOUTH);

        main.add(pnlInput, BorderLayout.NORTH); main.add(pnlList, BorderLayout.CENTER);

        btnAdd.addActionListener(e -> {
            try {
                //repo.createCorrLink(tS.getText(), tT.getText(), tN.getText(), tSC.getText(), tTC.getText());
                repo.createCorrLink(tS.getText(), tT.getText(), tN.getText());
                appendLog("SUCCESS: Linked [" + tS.getText() + "] -> [" + tT.getText() + "]");
                refreshLinks();
            } catch (Exception ex) { appendLog("ERROR: " + ex.getMessage()); }
        });
        btnRefresh.addActionListener(e -> refreshLinks());
        btnDel.addActionListener(e -> deleteSelectedLink());
        return main;
    }

    // --- TAB 3: REGULAR EDITOR ---
    private JPanel createRegularModelPanel() {
        JPanel mainPnl = new JPanel(new BorderLayout(10, 10));
        JPanel pnlInputs = new JPanel(new GridLayout(1, 2, 20, 0));

        // Object Panel
        JPanel pnlObj = new JPanel(new GridBagLayout());
        pnlObj.setBorder(new TitledBorder("Create Normal Object"));
        GridBagConstraints gbcO = new GridBagConstraints();
        gbcO.fill = GridBagConstraints.HORIZONTAL; gbcO.insets = new Insets(5, 10, 5, 10);
        JTextField txtModel = new JTextField("Families"), txtClass = new JTextField("Family"), txtOID = new JTextField("fm1");
        JButton btnCreateObj = new JButton("Create Object");
        gbcO.gridy=0; gbcO.gridx=0; pnlObj.add(new JLabel("Model:"), gbcO); gbcO.gridx=1; pnlObj.add(txtModel, gbcO);
        gbcO.gridy=1; gbcO.gridx=0; pnlObj.add(new JLabel("Class:"), gbcO); gbcO.gridx=1; pnlObj.add(txtClass, gbcO);
        gbcO.gridy=2; gbcO.gridx=0; pnlObj.add(new JLabel("OID:"), gbcO); gbcO.gridx=1; pnlObj.add(txtOID, gbcO);
        gbcO.gridy=3; gbcO.gridx=0; gbcO.gridwidth=2; pnlObj.add(btnCreateObj, gbcO);

        // Link Panel
        JPanel pnlLink = new JPanel(new GridBagLayout());
        pnlLink.setBorder(new TitledBorder("Create Normal Link (Auto-detect)"));
        GridBagConstraints gbcL = new GridBagConstraints();
        gbcL.fill = GridBagConstraints.HORIZONTAL; gbcL.insets = new Insets(5, 10, 5, 10);
        JTextField txtSrc = new JTextField(), txtTgt = new JTextField(), txtAssoc = new JTextField("FamilyRegistration");
        JButton btnCreateLink = new JButton("Connect Objects");
        gbcL.gridy=0; gbcL.gridx=0; pnlLink.add(new JLabel("Source OID:"), gbcL); gbcL.gridx=1; pnlLink.add(txtSrc, gbcL);
        gbcL.gridy=1; gbcL.gridx=0; pnlLink.add(new JLabel("Target OID:"), gbcL); gbcL.gridx=1; pnlLink.add(txtTgt, gbcL);
        gbcL.gridy=2; gbcL.gridx=0; pnlLink.add(new JLabel("Assoc:"), gbcL); gbcL.gridx=1; pnlLink.add(txtAssoc, gbcL);
        gbcL.gridy=3; gbcL.gridx=0; gbcL.gridwidth=2; pnlLink.add(btnCreateLink, gbcL);

        pnlInputs.add(pnlObj); pnlInputs.add(pnlLink);
        mainPnl.add(pnlInputs, BorderLayout.NORTH);

        // List Panel
        JPanel pnlLists = new JPanel(new GridLayout(1, 2, 20, 0));
        JPanel pnlLObj = new JPanel(new BorderLayout());
        modelRegObj = new DefaultTableModel(new Object[]{"OID", "Class"}, 0);
        tblRegObj = new JTable(modelRegObj);
        pnlLObj.add(new JScrollPane(tblRegObj), BorderLayout.CENTER);
        JButton btnDelObj = new JButton("Delete Selected Object");
        btnDelObj.setForeground(Color.RED);
        pnlLObj.add(btnDelObj, BorderLayout.SOUTH);

        JPanel pnlLLink = new JPanel(new BorderLayout());
        modelRegLink = new DefaultTableModel(new Object[]{"Name", "Type", "Source", "Target"}, 0);
        tblRegLink = new JTable(modelRegLink);
        pnlLLink.add(new JScrollPane(tblRegLink), BorderLayout.CENTER);
        JButton btnRefReg = new JButton("Refresh List");
        pnlLLink.add(btnRefReg, BorderLayout.SOUTH);

        pnlLists.add(pnlLObj); pnlLists.add(pnlLLink);
        mainPnl.add(pnlLists, BorderLayout.CENTER);

        btnCreateObj.addActionListener(e -> {
            try {
                directRepo.createRegularObject(txtModel.getText(), txtClass.getText(), txtOID.getText());
                appendLog("SUCCESS: Created Object [" + txtOID.getText() + "]");
                refreshRegularData();
            } catch (Exception ex) { appendLog("ERROR: " + ex.getMessage()); }
        });
        btnCreateLink.addActionListener(e -> {
            try {
                directRepo.createRegularLink(txtSrc.getText(), txtTgt.getText(), txtAssoc.getText(), currentModelName);
                appendLog("SUCCESS: Linked [" + txtSrc.getText() + "] -> [" + txtTgt.getText() + "]");
                refreshRegularData();
            } catch (Exception ex) { appendLog("ERROR: " + ex.getMessage()); }
        });
        btnDelObj.addActionListener(e -> deleteSelectedRegularObject());
        btnRefReg.addActionListener(e -> refreshRegularData());

        return mainPnl;
    }

    // --- LOGIC FUNCTIONS ---
    private void refreshObjects() {
        try {
            modelObj.setRowCount(0);
            repo.listAllCorrObjects().forEach(m -> modelObj.addRow(new Object[]{m.get("name"), m.get("type")}));
        } catch (Exception e) { appendLog("REFRESH ERROR (Obj): " + e.getMessage()); }
    }

    private void refreshLinks() {
        try {
            modelLink.setRowCount(0);
            repo.listAllLinks().forEach(m -> modelLink.addRow(new Object[]{m.get("name"), m.get("src"), m.get("tgt")}));
        } catch (Exception e) { appendLog("REFRESH ERROR (Link): " + e.getMessage()); }
    }

    private void refreshRegularData() {
        try {
            modelRegObj.setRowCount(0);
            directRepo.listRegularObjects().forEach(m -> modelRegObj.addRow(new Object[]{m.get("name"), m.get("type")}));
            modelRegLink.setRowCount(0);
            directRepo.listRegularLinks().forEach(m -> modelRegLink.addRow(new Object[]{m.get("name"), m.get("type"), m.get("src"), m.get("tgt")}));
        } catch (Exception e) { appendLog("REFRESH ERROR (Reg): " + e.getMessage()); }
    }

    private void deleteSelectedObject() {
        int r = tblObj.getSelectedRow();
        if (r == -1) return;
        String id = modelObj.getValueAt(r, 0).toString();
        if (JOptionPane.showConfirmDialog(this, "Xóa Object này?") == 0) {
            repo.deleteObject(id);
            appendLog("DELETED: " + id);
            refreshAllData();
        }
    }

    private void deleteSelectedLink() {
        int r = tblLink.getSelectedRow();
        if (r == -1) return;
        String n = modelLink.getValueAt(r, 0).toString();
        String s = modelLink.getValueAt(r, 1).toString();
        String t = modelLink.getValueAt(r, 2).toString();
        if (JOptionPane.showConfirmDialog(this, "Xóa link này?") == 0) {
            repo.deleteLink(n, s, t);
            appendLog("DELETED LINK: " + n);
            refreshLinks();
        }
    }

    private void deleteSelectedRegularObject() {
        int r = tblRegObj.getSelectedRow();
        if (r == -1) return;
        String id = modelRegObj.getValueAt(r, 0).toString();
        if (JOptionPane.showConfirmDialog(this, "Xóa Object này?") == 0) {
            directRepo.deleteRegularObject(id);
            appendLog("DELETED REGULAR: " + id);
            refreshAllData();
        }
    }
}