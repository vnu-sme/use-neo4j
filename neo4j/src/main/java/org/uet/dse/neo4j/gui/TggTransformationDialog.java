package org.uet.dse.neo4j.gui;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.uet.dse.neo4j.TGGLexer;
import org.uet.dse.neo4j.TGGParser;
import org.uet.dse.neo4j.model_trafo.*;

import org.uet.dse.neo4j.model_trafo.parser.TGGBuildVisitor;
import org.uet.dse.neo4j.model_trafo.parser.TGGRule;
import org.uet.dse.neo4j.model_trafo.parser.TGGTransformation;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class TggTransformationDialog extends JDialog {

    private JTextField txtSourceModel, txtTargetModel, txtTggFilePath;
    private JTextField txtSrcUsePath, txtTrgUsePath;
    private JList<String> lstRules;

    // Tách riêng các vùng hiển thị query
    private JTextArea txtMetaQueryPreview;
    private JTextArea txtMatchQueryPreview;
    private JTextArea txtExecQueryPreview;

    private JTextArea txtLog;
    private JButton btnProcess;
    private JTabbedPane tabbedPreview;

    private org.tzi.use.main.Session useSession;

    // Lưu trữ các bộ query
    private String metaModelQuery = "";
    private Map<String, String> matchingQueries = new HashMap<>();
    private Map<String, String> executingQueries = new HashMap<>();

    public TggTransformationDialog(Frame owner, org.tzi.use.main.Session session) {
        super(owner, "TGG Rule Processor & Multi-Target Generator", true);
        this.useSession = session;
        initComponents();

        if (session.system().model() != null) {
            txtSourceModel.setText(session.system().model().name());
        }
    }

    private void initComponents() {
        this.txtSourceModel = new JTextField("SourceModel");
        this.txtTargetModel = new JTextField("TargetModel");
        this.txtTggFilePath = new JTextField();
        this.txtSrcUsePath = new JTextField();
        this.txtTrgUsePath = new JTextField();

        this.lstRules = new JList<>(new DefaultListModel<>());

        // Cấu hình các vùng text preview
        txtMetaQueryPreview = createReadOnlyTextArea();
        txtMatchQueryPreview = createReadOnlyTextArea();
        txtExecQueryPreview = createReadOnlyTextArea();

        this.txtLog = new JTextArea(6, 0);
        this.btnProcess = new JButton("Process TGG");

        setSize(1100, 800);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(10, 10));

        // --- NORTH: Configuration ---
        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new CompoundBorder(new EmptyBorder(10, 10, 10, 10), new TitledBorder("Configuration")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridy = 0; gbc.gridx = 0; pnlInput.add(new JLabel("Source Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; pnlInput.add(txtSourceModel, gbc);

        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0; pnlInput.add(new JLabel("Target Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; pnlInput.add(txtTargetModel, gbc);

        addFileRow(pnlInput, gbc, 2, "Source Model (.use):", txtSrcUsePath, e -> chooseFile(txtSrcUsePath));
        addFileRow(pnlInput, gbc, 3, "Target Model (.use):", txtTrgUsePath, e -> chooseFile(txtTrgUsePath));
        addFileRow(pnlInput, gbc, 4, "TGG Rule File (.tgg):", txtTggFilePath, e -> chooseFile(txtTggFilePath));

        add(pnlInput, BorderLayout.NORTH);

        // --- CENTER: Split Pane (Rules List | Tabs) ---
        tabbedPreview = new JTabbedPane();
        tabbedPreview.addTab("Corr Metamodel", new JScrollPane(txtMetaQueryPreview));
        tabbedPreview.addTab("Rule Matching", new JScrollPane(txtMatchQueryPreview));
        tabbedPreview.addTab("Rule Execution", new JScrollPane(txtExecQueryPreview));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(250);

        JPanel pnlLeft = new JPanel(new BorderLayout());
        pnlLeft.setBorder(new TitledBorder("Rules & Global"));
        DefaultListModel<String> listModel = (DefaultListModel<String>) lstRules.getModel();
        listModel.addElement(">> GLOBAL: Corr Metamodel"); // Mục đặc biệt
        pnlLeft.add(new JScrollPane(lstRules), BorderLayout.CENTER);

        splitPane.setLeftComponent(pnlLeft);
        splitPane.setRightComponent(tabbedPreview);
        add(splitPane, BorderLayout.CENTER);

        // --- SOUTH: Log & Buttons ---
        JPanel pnlBottom = new JPanel(new BorderLayout());
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(Color.GREEN);
        pnlBottom.add(new JScrollPane(txtLog), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> dispose());
        btnProcess.addActionListener(e -> startProcessing());
        pnlButtons.add(btnCancel);
        pnlButtons.add(btnProcess);
        pnlBottom.add(pnlButtons, BorderLayout.SOUTH);
        add(pnlBottom, BorderLayout.SOUTH);

        lstRules.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreview();
            }
        });
    }

    private JTextArea createReadOnlyTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font("Monospaced", Font.PLAIN, 12));
        area.setBackground(new Color(245, 245, 245));
        return area;
    }

    private void updatePreview() {
        String selected = lstRules.getSelectedValue();
        if (selected == null) return;

        if (selected.equals(">> GLOBAL: Corr Metamodel")) {
            tabbedPreview.setSelectedIndex(0);
            txtMetaQueryPreview.setText(metaModelQuery);
            txtMatchQueryPreview.setText("// Not applicable for Metamodel");
            txtExecQueryPreview.setText("// Not applicable for Metamodel");
        } else {
            tabbedPreview.setSelectedIndex(1); // Chuyển sang tab Matching
            txtMatchQueryPreview.setText(matchingQueries.getOrDefault(selected, "// Query not generated"));
            txtExecQueryPreview.setText(executingQueries.getOrDefault(selected, "// Query not generated"));
        }
    }

    private void startProcessing() {
        String tggPath = txtTggFilePath.getText();
        String srcModelName = txtSourceModel.getText().trim();
        String trgModelName = txtTargetModel.getText().trim();

        if (tggPath.isEmpty() || srcModelName.isEmpty() || trgModelName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên Model và chọn file .tgg");
            return;
        }

        // Xóa trắng dữ liệu cũ trên giao diện
        DefaultListModel<String> listModel = (DefaultListModel<String>) lstRules.getModel();
        listModel.clear();
        matchingQueries.clear();
        executingQueries.clear();

        try {
            appendLog("Đang đọc và phân tích cú pháp file TGG: " + tggPath);
            TGGTransformation trans = parseTGG(tggPath);
            appendLog("Phân tích thành công Transformation: " + trans.name);

            // BƯỚC 2: Sinh Query cho Correspondence Metamodel (Graph trung gian)
            appendLog("Đang sinh bộ truy vấn Metamodel cho Correspondence Graph...");
            CorrMetamodelGenerator metaGen = new CorrMetamodelGenerator();
            // 1. Cho tab Metamodel
            this.metaModelQuery = metaGen.generateMetaModelQuery(trans, srcModelName, trgModelName);

            // 2. Cho từng Rule (Matching & Executing)
            for (TGGRule rule : trans.rules) {
                // Matching query: Sẽ tìm các node thỏa mãn lhs mà chưa có corr
                matchingQueries.put(rule.name, metaGen.generateMatchingQuery(rule, srcModelName, trgModelName));

                // Executing query: Sẽ tạo rhs và các link corr sang thực thể thực sự
                executingQueries.put(rule.name, metaGen.generateExecutionQuery(rule, srcModelName, trgModelName));
            }

            // BƯỚC 3: Cập nhật danh sách Rule lên GUI
            listModel.addElement(">> GLOBAL: Corr Metamodel");
            for (TGGRule rule : trans.rules) {
                listModel.addElement(rule.name);

                // TẠM THỜI: Sinh placeholder cho Matching và Executing
                // (Sau này bạn sẽ thay bằng các bộ Generator thực tế cho tầng Object)
                matchingQueries.put(rule.name, generatePlaceholderMatchQuery(rule, srcModelName, trgModelName));
                executingQueries.put(rule.name, generatePlaceholderExecQuery(rule));
            }

            // BƯỚC 4: Hiển thị kết quả đầu tiên
            lstRules.setSelectedIndex(0);
            appendLog("Hoàn tất! Đã xử lý " + trans.rules.size() + " quy tắc chuyển đổi.");

        } catch (Exception ex) {
            appendLog("LỖI HỆ THỐNG: " + ex.getMessage());
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            txtLog.append(sw.toString()); // Ghi chi tiết stack trace vào log để debug
        }
    }

    // Các hàm bổ trợ để tạo nội dung giả lập (sau này sẽ thay thế bằng logic thật)
    private String generatePlaceholderMatchQuery(TGGRule rule, String src, String trg) {
        return String.format("""
        // Matching Query for Rule: %s
        // Models: %s (Source), %s (Target)
        MATCH (sCls {model: '%s'})
        MATCH (tCls {model: '%s'})
        WHERE ... 
        RETURN sCls, tCls
        """, rule.name, src, trg, src, trg);
    }

    private String generatePlaceholderExecQuery(TGGRule rule) {
        return String.format("""
        // Execution Query for Rule: %s
        CREATE (corr:Correspondence {rule: '%s'})
        SET corr.timestamp = timestamp()
        """, rule.name, rule.name);
    }

    private TGGTransformation parseTGG(String path) throws Exception {
        // 1. Đọc file từ đường dẫn path
        org.antlr.v4.runtime.CharStream input = CharStreams.fromPath(Paths.get(path));

        // 2. Khởi tạo Lexer và Parser
        TGGLexer lexer = new TGGLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        TGGParser parser = new TGGParser(tokens);

        // 3. Xây dựng cây Parse Tree từ rule cao nhất (tggFile)
        ParseTree tree = parser.tggFile();

        // 4. Sử dụng Visitor để chuyển đổi cây thành Object TGGTransformation
        TGGBuildVisitor builder = new TGGBuildVisitor();
        return (TGGTransformation) builder.visit(tree);
    }

    // Các hàm helper giữ nguyên như cũ...
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