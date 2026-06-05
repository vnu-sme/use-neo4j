package org.uet.dse.neo4j.gui;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.uet.dse.neo4j.TGGLexer;
import org.uet.dse.neo4j.TGGParser;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.TggTransformationManager;
import org.uet.dse.neo4j.model_trafo.matching.CorrMetamodelGenerator;
import org.uet.dse.neo4j.model_trafo.parser.*;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

public class TggTransformationConsole extends JDialog {
    private JTextField txtSrcModelName, txtTrgModelName, txtTggPath;
    private JTextArea txtTggEditor, txtExecPreview, txtLog;
    private JTable tblRules, tblCandidates;
    private DefaultTableModel modelRules, modelCandidates;
    private TggTransformationManager manager = TggTransformationManager.getInstance();


    private TGGTransformation currentTransformation;
    private CorrMetamodelGenerator queryGen = new CorrMetamodelGenerator();
    private List<Map<String, String>> currentCandidates = new ArrayList<>();

    public TggTransformationConsole(Frame owner, org.tzi.use.main.Session useSession) {
        super(owner, "TGG Transformation Studio - Integrated Debugger", false);
        initComponents();
        if (useSession != null && useSession.system().model() != null) {
            txtSrcModelName.setText(useSession.system().model().name());
        }
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        setSize(1450, 900);
        setLayout(new BorderLayout(10, 10));

        // --- TOP: CONFIGURATION ---
        JPanel pnlNorth = new JPanel(new GridBagLayout());
        pnlNorth.setBorder(new TitledBorder("Environment"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridy = 0; gbc.gridx = 0; pnlNorth.add(new JLabel("Source Model:"), gbc);
        gbc.gridx = 1; txtSrcModelName = new JTextField("Families"); pnlNorth.add(txtSrcModelName, gbc);
        gbc.gridx = 2; pnlNorth.add(new JLabel("Target Model:"), gbc);
        gbc.gridx = 3; txtTrgModelName = new JTextField("Persons"); pnlNorth.add(txtTrgModelName, gbc);

        gbc.gridy = 1; gbc.gridx = 0; pnlNorth.add(new JLabel("TGG Path:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; txtTggPath = new JTextField(); pnlNorth.add(txtTggPath, gbc);
        gbc.gridx = 3; gbc.gridwidth = 1;
        JButton btnLoad = new JButton("Load TGG");
        btnLoad.addActionListener(e -> browseAndLoadTgg()); pnlNorth.add(btnLoad, gbc);
        add(pnlNorth, BorderLayout.NORTH);

        // --- CENTER: EDITOR | RULES | CANDIDATES ---
        txtTggEditor = new JTextArea();
        txtTggEditor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JPanel pnlEditor = new JPanel(new BorderLayout());
        pnlEditor.setBorder(new TitledBorder("1. TGG Rule Editor"));
        pnlEditor.add(new JScrollPane(txtTggEditor), BorderLayout.CENTER);
        JButton btnApply = new JButton("Apply & Parse Code");
        btnApply.addActionListener(e -> applyTggChanges());
        pnlEditor.add(btnApply, BorderLayout.SOUTH);

        // Bảng danh sách luật
        modelRules = new DefaultTableModel(new Object[]{"Rule Name", "Forward", "Backward", "Integration"}, 0);
        tblRules = new JTable(modelRules);
        tblRules.setCellSelectionEnabled(true);
        JPanel pnlRules = new JPanel(new BorderLayout());
        pnlRules.setBorder(new TitledBorder("2. Rule Status (Click FOUND cell)"));
        pnlRules.add(new JScrollPane(tblRules), BorderLayout.CENTER);
        JButton btnScan = new JButton("Scan All Directions");
        btnScan.addActionListener(e -> scanAllRules());
        pnlRules.add(btnScan, BorderLayout.SOUTH);

        // Bảng Candidates và Preview thực thi
        JPanel pnlRight = new JPanel(new GridLayout(2, 1, 5, 5));
        modelCandidates = new DefaultTableModel();
        tblCandidates = new JTable(modelCandidates);
        JPanel pnlCand = new JPanel(new BorderLayout());
        pnlCand.setBorder(BorderFactory.createTitledBorder("3. Matched Tuples (Nodes & Links)"));
        pnlCand.add(new JScrollPane(tblCandidates), BorderLayout.CENTER);

        txtExecPreview = new JTextArea();
        txtExecPreview.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JPanel pnlExec = new JPanel(new BorderLayout());
        pnlExec.setBorder(BorderFactory.createTitledBorder("4. Execution Preview"));
        pnlExec.add(new JScrollPane(txtExecPreview), BorderLayout.CENTER);

        JButton btnExecute = new JButton("EXECUTE SELECTED CANDIDATE");
        btnExecute.setBackground(new Color(0, 120, 0)); btnExecute.setForeground(Color.WHITE);
        btnExecute.setFont(new Font("Arial", Font.BOLD, 13));
        btnExecute.addActionListener(e -> performExecution());
        pnlExec.add(btnExecute, BorderLayout.SOUTH);
        pnlRight.add(pnlCand); pnlRight.add(pnlExec);

        JSplitPane splitRight = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pnlRules, pnlRight);
        splitRight.setDividerLocation(350);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pnlEditor, splitRight);
        mainSplit.setDividerLocation(400);
        add(mainSplit, BorderLayout.CENTER);

        // --- BOTTOM: LOG ---
        txtLog = new JTextArea(6, 0);
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK); txtLog.setForeground(Color.GREEN);
        add(new JScrollPane(txtLog), BorderLayout.SOUTH);

        // --- EVENT LISTENERS ---
        tblRules.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                loadCandidatesForSelectedCell();
            }
        });

        tblCandidates.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) prepareExecutionScript();
        });
    }

    private void loadCandidatesForSelectedCell() {
        // 1. RESET UI NGAY LẬP TỨC (Tránh Desync)
        txtExecPreview.setText("");
        modelCandidates.setRowCount(0);
        modelCandidates.setColumnCount(0);
        currentCandidates.clear();

        int row = tblRules.getSelectedRow();
        int col = tblRules.getSelectedColumn();
        if (row == -1 || col < 1) return;

        String ruleName = (String) modelRules.getValueAt(row, 0);
        int mode = col - 1;
        appendLog("Fetching candidates for [" + ruleName + "]...");

        // 2. CHẠY TRONG THREAD RIÊNG
        new Thread(() -> {
            try {
                String query = queryGen.generateFindMatchQueryData(currentTransformation.getRule(ruleName),
                    txtSrcModelName.getText().trim(), txtTrgModelName.getText().trim(), mode);

                try (Session s = Neo4jDriverManager.getInstance().openSession()) {
                    List<Record> records = s.run(query).list();

                    SwingUtilities.invokeLater(() -> {
                        if (records.isEmpty()) {
                            appendLog("No tuples found for this direction.");
                            return;
                        }
                        // Reset tiêu đề cột
                        List<String> keys = records.get(0).keys();
                        for (String k : keys) modelCandidates.addColumn(k);

                        for (Record rec : records) {
                            Map<String, String> rowData = new LinkedHashMap<>();
                            Object[] rowArr = new Object[keys.size()];
                            for (int i = 0; i < keys.size(); i++) {
                                String val = String.valueOf(rec.get(i).asObject());
                                rowData.put(keys.get(i), val);
                                rowArr[i] = val;
                            }
                            currentCandidates.add(rowData);
                            modelCandidates.addRow(rowArr);
                        }
                        appendLog("Found " + records.size() + " candidates.");
                    });
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendLog("Error: " + ex.getMessage()));
            }
        }).start();
    }

    private void prepareExecutionScript() {
        int row = tblCandidates.getSelectedRow();
        if (row == -1) {
            txtExecPreview.setText("");
            return;
        }

        int ruleRow = tblRules.getSelectedRow();
        int mode = tblRules.getSelectedColumn() - 1;
        if (ruleRow == -1) return;

        TGGRule rule = currentTransformation.getRule((String) modelRules.getValueAt(ruleRow, 0));
        Map<String, String> tuple = currentCandidates.get(row);

        // Sinh script thực thi khóa ID
        String execQ = queryGen.generateExecuteMatchQuery(rule,
            txtSrcModelName.getText().trim(), txtTrgModelName.getText().trim(), mode, tuple);

        txtExecPreview.setText(execQ);
        txtExecPreview.setCaretPosition(0);
    }

    private void scanAllRules() {
        if (currentTransformation == null) {
            JOptionPane.showMessageDialog(this, "Please load TGG Rules first!");
            return;
        }
        appendLog("Scanning all rules...");
        new Thread(() -> {
            for (int i = 0; i < modelRules.getRowCount(); i++) {
                String name = (String) modelRules.getValueAt(i, 0);
                TGGRule rule = currentTransformation.getRule(name);
                for (int mode = 0; mode < 3; mode++) {
                    String q = queryGen.generateMatchingQuery(rule, txtSrcModelName.getText().trim(), txtTrgModelName.getText().trim(), mode);
                    boolean match = false;
                    try (Session s = Neo4jDriverManager.getInstance().openSession()) {
                        match = s.run(q).single().get(0).asBoolean();
                    } catch (Exception ex) {}
                    final int r = i, c = mode + 1;
                    final String status = match ? "FOUND" : "None";
                    SwingUtilities.invokeLater(() -> modelRules.setValueAt(status, r, c));
                }
            }
            appendLog("Scan complete.");
        }).start();
    }

    private void performExecution() {
        String q = txtExecPreview.getText();
        if (q.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a candidate tuple first.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Execute transformation?") != JOptionPane.YES_OPTION) return;

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(q);
            appendLog("SUCCESS: Transformation executed.");
            JOptionPane.showMessageDialog(this, "Done!");
            loadCandidatesForSelectedCell(); // Refresh candidates
            scanAllRules(); // Refresh status
        } catch (Exception ex) {
            appendLog("EXECUTION ERROR: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    private void browseAndLoadTgg() {
        JFileChooser jfc = new JFileChooser();
        if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File f = jfc.getSelectedFile();
                txtTggPath.setText(f.getAbsolutePath());
                txtTggEditor.setText(new String(Files.readAllBytes(f.toPath())));
                applyTggChanges();
            } catch (Exception e) { appendLog("File error: " + e.getMessage()); }
        }
    }

    private void applyTggChanges() {
        try {

            manager.parseTggContent(txtTggEditor.getText());
            this.currentTransformation = manager.getTransformation();
            appendLog("Rules updated in Global Manager.");
            refreshRuleTable();
        } catch (Exception e) {
            appendLog("Parse Error: " + e.getMessage());
        }
    }

    private void refreshRuleTable() {
        modelRules.setRowCount(0);
        if (manager.isLoaded()) {
            for (TGGRule r : manager.getTransformation().rules) {
                modelRules.addRow(new Object[]{r.name, "?", "?", "?"});
            }
        }
    }
//    private void singleSessionVerApplyTggChanges() {
//        try {
//            String content = txtTggEditor.getText();
//            TGGLexer lexer = new TGGLexer(CharStreams.fromString(content));
//            TGGParser parser = new TGGParser(new CommonTokenStream(lexer));
//            currentTransformation = (TGGTransformation) new TGGBuildVisitor().visit(parser.tggFile());
//
//            modelRules.setRowCount(0);
//            for (TGGRule r : currentTransformation.rules) {
//                modelRules.addRow(new Object[]{r.name, "?", "?", "?"});
//            }
//            appendLog("Parsed " + currentTransformation.rules.size() + " rules.");
//        } catch (Exception e) {
//            appendLog("Parse Error: " + e.getMessage());
//        }
//    }

    private void appendLog(String msg) {
        txtLog.append("> " + msg + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength());
    }
}
