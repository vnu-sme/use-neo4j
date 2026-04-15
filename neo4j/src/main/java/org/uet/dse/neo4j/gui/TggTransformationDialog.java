package org.uet.dse.neo4j.gui;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.uet.dse.neo4j.TGGLexer;
import org.uet.dse.neo4j.TGGParser;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.model_trafo.*;

import org.uet.dse.neo4j.model_trafo.parser.TGGBuildVisitor;
import org.uet.dse.neo4j.model_trafo.parser.TGGRule;
import org.uet.dse.neo4j.model_trafo.parser.TGGTransformation;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class TggTransformationDialog extends JDialog {

    private JTextField txtSourceModel, txtTargetModel, txtTggFilePath;
    private JTextField txtSrcUsePath, txtTrgUsePath;
    private JList<String> lstRules;

    private JTextArea txtMetaQueryPreview;
    private JComboBox<String> cbActiveMode; // Chế độ thực thi chính

    private JTextArea txtLog;
    private JButton btnProcess;
    private JTabbedPane tabbedPreview;

    private org.tzi.use.main.Session useSession;
    private JComboBox<String> cbMode; // Thêm bộ chọn chế độ
    // Lưu trữ các bộ query
    private String metaModelQuery = "";
    // Cấu trúc dữ liệu mới: Map<TênLuật, Map<Hướng, Query>>
    // Hướng: 0-Forward, 1-Backward, 2-Integration
    private Map<String, Map<Integer, String>> matchStore = new HashMap<>();
    private Map<String, Map<Integer, String>> execStore = new HashMap<>();
    // THÊM DÒNG NÀY ĐỂ LƯU TRỮ CẤU TRÚC LUẬT SAU KHI PARSE
    private TGGTransformation currentTransformation;

    // TextAreas cho Tab Forward
    private JTextArea txtForwMatch, txtForwExec;
    // TextAreas cho Tab Backward
    private JTextArea txtBackMatch, txtBackExec;
    // TextAreas cho Tab Integration
    private JTextArea txtIntegMatch, txtIntegExec;
    public TggTransformationDialog(Frame owner, org.tzi.use.main.Session session) {
        super(owner, "TGG Rule Processor & Multi-Target Generator", true);
        this.useSession = session;
        initComponents();

        if (session.system().model() != null) {
            txtSourceModel.setText(session.system().model().name());
        }
    }

    private void initComponents() {
        this.txtSourceModel = new JTextField("Families");
        this.txtTargetModel = new JTextField("Persons");
        this.txtTggFilePath = new JTextField();
        this.txtSrcUsePath = new JTextField();
        this.txtTrgUsePath = new JTextField();

        // 1. Khởi tạo các vùng text hiển thị (Dùng Font Monospaced cho dễ nhìn code)
        txtForwMatch = createReadOnlyTextArea(); txtForwExec = createReadOnlyTextArea();
        txtBackMatch = createReadOnlyTextArea(); txtBackExec = createReadOnlyTextArea();
        txtIntegMatch = createReadOnlyTextArea(); txtIntegExec = createReadOnlyTextArea();
        txtMetaQueryPreview = createReadOnlyTextArea();
        txtLog = new JTextArea(6, 0);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(Color.GREEN);

        // 2. Cấu hình TabbedPane với 4 Tab chuyên biệt
        tabbedPreview = new JTabbedPane();
        tabbedPreview.addTab("0. Corr Metamodel", new JScrollPane(txtMetaQueryPreview));
        tabbedPreview.addTab("1. Forward (S->T)", createTrafoPanel(txtForwMatch, txtForwExec));
        tabbedPreview.addTab("2. Backward (T->S)", createTrafoPanel(txtBackMatch, txtBackExec));
        tabbedPreview.addTab("3. Integration", createTrafoPanel(txtIntegMatch, txtIntegExec));

        this.lstRules = new JList<>(new DefaultListModel<>());
        this.btnProcess = new JButton("Process TGG File");

        cbActiveMode = new JComboBox<>(new String[]{"Forward", "Backward", "Integration"});

        setSize(1200, 850);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(10, 10));

        // --- Cấu trúc Layout ---
        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new CompoundBorder(new EmptyBorder(10, 10, 10, 10), new TitledBorder("Configuration")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(3, 5, 3, 5);

        gbc.gridy = 0; gbc.gridx = 0; pnlInput.add(new JLabel("Source Model:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; pnlInput.add(txtSourceModel, gbc);

        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0; pnlInput.add(new JLabel("Target Model:"), gbc);
        gbc.gridx = 1; pnlInput.add(txtTargetModel, gbc);

        addFileRow(pnlInput, gbc, 2, "Source (.use):", txtSrcUsePath, e -> chooseFile(txtSrcUsePath));
        addFileRow(pnlInput, gbc, 3, "Target (.use):", txtTrgUsePath, e -> chooseFile(txtTrgUsePath));
        addFileRow(pnlInput, gbc, 4, "TGG Rule (.tgg):", txtTggFilePath, e -> chooseFile(txtTggFilePath));

        gbc.gridy = 5; gbc.gridx = 0; pnlInput.add(new JLabel("Execution Mode:"), gbc);
        gbc.gridx = 1; pnlInput.add(cbActiveMode, gbc);

        add(pnlInput, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(280);

        JPanel pnlLeft = new JPanel(new BorderLayout());
        pnlLeft.setBorder(new TitledBorder("Parsed Rules"));
        pnlLeft.add(new JScrollPane(lstRules), BorderLayout.CENTER);

        splitPane.setLeftComponent(pnlLeft);
        splitPane.setRightComponent(tabbedPreview);
        add(splitPane, BorderLayout.CENTER);

        JPanel pnlBottom = new JPanel(new BorderLayout());
        pnlBottom.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        JPanel pnlAction = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Close");
        btnCancel.addActionListener(e -> dispose());
        btnProcess.addActionListener(e -> startProcessing());
        pnlAction.add(btnCancel); pnlAction.add(btnProcess);
        pnlBottom.add(pnlAction, BorderLayout.SOUTH);
        add(pnlBottom, BorderLayout.SOUTH);

        // --- Event Listener ---
        lstRules.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview();
        });
    }
    private JPanel createTrafoPanel(JTextArea matchArea, JTextArea execArea) {
        JPanel p = new JPanel(new GridLayout(2, 1, 5, 5));

        JPanel pMatch = new JPanel(new BorderLayout());
        pMatch.setBorder(new TitledBorder("MATCHING LOGIC (Boolean Check)"));
        pMatch.add(new JScrollPane(matchArea));

        JPanel pExec = new JPanel(new BorderLayout());
        pExec.setBorder(new TitledBorder("EXECUTION LOGIC (Create Delta)"));
        pExec.add(new JScrollPane(execArea));

        p.add(pMatch);
        p.add(pExec);
        return p;
    }
    private JTextArea createReadOnlyTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setBackground(new Color(245, 245, 245));
        return area;
    }


    private void startProcessing() {
        String tggPath = txtTggFilePath.getText();
        if (tggPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn file .tgg");
            return;
        }

        try {
            appendLog("Đang đọc và phân tích file TGG...");
            this.currentTransformation = parseTGG(tggPath);
            appendLog("Phân tích thành công Transformation: " + currentTransformation.name);

            regenerateAllQueries();

            appendLog("Hoàn tất xử lý " + currentTransformation.rules.size() + " quy tắc.");
        } catch (Exception ex) {
            appendLog("LỖI: " + ex.getMessage());
        }
    }

    private void regenerateAllQueries() {
        String src = txtSourceModel.getText().trim();
        String trg = txtTargetModel.getText().trim();
        CorrMetamodelGenerator gen = new CorrMetamodelGenerator();

        this.metaModelQuery = gen.generateMetaModelQuery(currentTransformation, src, trg);

        DefaultListModel<String> listModel = (DefaultListModel<String>) lstRules.getModel();
        listModel.clear();
        listModel.addElement(">> GLOBAL: Corr Metamodel");

        for (TGGRule rule : currentTransformation.rules) {
            listModel.addElement(rule.name);

            // Với MỖI LUẬT, sinh LUÔN cả 3 hướng
            Map<Integer, String> mQueries = new HashMap<>();
            Map<Integer, String> eQueries = new HashMap<>();

            for (int mode = 0; mode < 3; mode++) {
                mQueries.put(mode, gen.generateMatchingQuery(rule, src, trg, mode));
                eQueries.put(mode, gen.generateExecutionQuery(rule, src, trg));
            }

            matchStore.put(rule.name, mQueries);
            execStore.put(rule.name, eQueries);
        }
        lstRules.setSelectedIndex(0);
        updatePreview();
    }

    private void updatePreview() {
        String sel = lstRules.getSelectedValue();
        if (sel == null) return;

        if (sel.equals(">> GLOBAL: Corr Metamodel")) {
            txtMetaQueryPreview.setText(metaModelQuery);
            tabbedPreview.setSelectedIndex(0);
        } else {
            // Đổ data vào cả 3 Tab hướng đồng thời
            Map<Integer, String> mq = matchStore.get(sel);
            Map<Integer, String> eq = execStore.get(sel);

            txtForwMatch.setText(mq.get(0)); txtForwExec.setText(eq.get(0));
            txtBackMatch.setText(mq.get(1)); txtBackExec.setText(eq.get(1));
            txtIntegMatch.setText(mq.get(2)); txtIntegExec.setText(eq.get(2));

            // Nếu đang ở tab Meta thì nhảy sang Forward cho dễ nhìn
            if(tabbedPreview.getSelectedIndex() == 0) tabbedPreview.setSelectedIndex(1);
        }
    }


    private TGGTransformation parseTGG(String path) throws Exception {
        org.antlr.v4.runtime.CharStream input = CharStreams.fromPath(Paths.get(path));

        TGGLexer lexer = new TGGLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TGGParser parser = new TGGParser(tokens);

        ParseTree tree = parser.tggFile();

        TGGBuildVisitor builder = new TGGBuildVisitor();
        return (TGGTransformation) builder.visit(tree);
    }

    private void addFileRow(JPanel p, GridBagConstraints gbc, int y, String label, JTextField txt, java.awt.event.ActionListener action) {
        gbc.gridy = y; gbc.gridx = 0; gbc.weightx = 0; p.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; p.add(txt, gbc);
        gbc.gridx = 2; gbc.weightx = 0; JButton btn = new JButton("..."); btn.addActionListener(action); p.add(btn, gbc);
    }

    private void chooseFile(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void appendLog(String msg) {
        txtLog.append("> " + msg + "\n");
    }

}