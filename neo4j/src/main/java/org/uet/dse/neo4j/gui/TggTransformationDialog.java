package org.uet.dse.neo4j.gui;

import org.neo4j.driver.Session;
import org.uet.dse.rtlplus.RTLLoader;
import org.uet.dse.rtlplus.mm.MTggRule;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class TggTransformationDialog extends JDialog {

    private JTextField txtSourceModel, txtTargetModel, txtTggFilePath;
    private JTextField txtSrcUsePath, txtTrgUsePath;
    private JList<String> lstRules;
    private JTextArea txtCypherPreview, txtLog;
    private JButton btnProcess;
    private org.tzi.use.main.Session useSession;

    // Lưu trữ query của từng luật để hiển thị khi click
    private Map<String, String> ruleQueriesMap = new HashMap<>();

    public TggTransformationDialog(Frame owner, org.tzi.use.main.Session session) {
        super(owner, "TGG Rule Processor & Multi-Target Generator", true);
        this.useSession = session;

        // BƯỚC 1: Phải khởi tạo các component trước (gọi hàm chứa các lệnh new)
        initComponents();

        // BƯỚC 2: Sau khi initComponents() chạy xong, txtSourceModel mới không còn null
        // Bây giờ bạn mới có thể gọi setText an toàn
        if (session.system().model() != null) {
            String modelName = session.system().model().name();
            txtSourceModel.setText(modelName);
        }
    }

    private void initComponents() {
        // --- QUAN TRỌNG: PHẢI NEW CÁC BIẾN Ở ĐÂY TRƯỚC ---
        // Tuyệt đối không viết thêm chữ "JTextField" ở trước tên biến ở đây
        this.txtSourceModel = new JTextField("SourceModel");
        this.txtTargetModel = new JTextField("TargetModel");
        this.txtTggFilePath = new JTextField();
        this.txtSrcUsePath = new JTextField();
        this.txtTrgUsePath = new JTextField();

        this.lstRules = new JList<>(new DefaultListModel<>());
        this.txtCypherPreview = new JTextArea();
        this.txtLog = new JTextArea(6, 0);
        this.btnProcess = new JButton("Process TGG");

        // --- Bắt đầu thiết lập Layout ---
        setSize(1000, 750);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(10, 10));

        // Vùng nhập liệu phía trên
        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new CompoundBorder(new EmptyBorder(10, 10, 10, 10), new TitledBorder("Configuration")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Row 0: Source Model Name
        gbc.gridy = 0; gbc.gridx = 0; gbc.weightx = 0;
        pnlInput.add(new JLabel("Source Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        pnlInput.add(txtSourceModel, gbc);

        // Row 1: Target Model Name
        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0;
        pnlInput.add(new JLabel("Target Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        pnlInput.add(txtTargetModel, gbc);

        // Row 2: Source File
        addFileRow(pnlInput, gbc, 2, "Source Model (.use):", txtSrcUsePath, e -> chooseFile(txtSrcUsePath));
        // Row 3: Target File
        addFileRow(pnlInput, gbc, 3, "Target Model (.use):", txtTrgUsePath, e -> chooseFile(txtTrgUsePath));
        // Row 4: TGG File
        addFileRow(pnlInput, gbc, 4, "TGG Rule File (.tgg):", txtTggFilePath, e -> chooseFile(txtTggFilePath));

        add(pnlInput, BorderLayout.NORTH);

        // Vùng giữa (SplitPane)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(300);
        splitPane.setLeftComponent(new JScrollPane(lstRules));
        splitPane.setRightComponent(new JScrollPane(txtCypherPreview));
        add(splitPane, BorderLayout.CENTER);

        // Vùng dưới (Log)
        add(new JScrollPane(txtLog), BorderLayout.SOUTH);
        // --- VÙNG DƯỚI (Log & Buttons) ---
        JPanel pnlBottom = new JPanel(new BorderLayout());

        // Thiết lập vùng Log
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(new Color(0, 255, 0));
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(new TitledBorder(null, "System Log", TitledBorder.LEADING, TitledBorder.TOP, null, Color.WHITE));
        pnlBottom.add(scrollLog, BorderLayout.CENTER);

        // Vùng chứa các nút bấm
        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // Nút Cancel
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> dispose());

        // Cấu hình nút Process (Biến btnProcess đã khai báo ở trên)
        btnProcess.setPreferredSize(new Dimension(200, 30));
        btnProcess.addActionListener(e -> startProcessing()); // Gắn sự kiện gọi hàm xử lý

        pnlButtons.add(btnCancel);
        pnlButtons.add(btnProcess);

        pnlBottom.add(pnlButtons, BorderLayout.SOUTH);
        add(pnlBottom, BorderLayout.SOUTH);

        // --- SỰ KIỆN KHI CLICK VÀO DANH SÁCH LUẬT ---
        lstRules.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedRule = lstRules.getSelectedValue();
                if (selectedRule != null) {
                    txtCypherPreview.setText(ruleQueriesMap.getOrDefault(selectedRule, "// No query generated"));
                    txtCypherPreview.setCaretPosition(0); // Cuộn lên đầu
                }
            }
        });
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


    private void startProcessing() {
        String tggPath = txtTggFilePath.getText();
        String srcPath = txtSrcUsePath.getText();
        String trgPath = txtTrgUsePath.getText();
        String srcModelName = txtSourceModel.getText().trim();
        String trgModelName = txtTargetModel.getText().trim();

        if (tggPath.isEmpty() || srcPath.isEmpty() || trgPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select all three files (.use, .use, .tgg)");
            return;
        }

        StringWriter sw = new StringWriter();
        PrintWriter logWriter = new PrintWriter(sw);

        RTLLoader loader = new RTLLoader(useSession, new File(srcPath), new File(trgPath), tggPath, logWriter);

        appendLog("Compiling TGG Rules...");
        if (loader.run()) {
            appendLog("Compilation successful.");
            DefaultListModel<String> listModel = (DefaultListModel<String>) lstRules.getModel();
            listModel.clear();
            ruleQueriesMap.clear();



            // 2. Hiển thị thông tin tổng quan
            txtCypherPreview.setText("// Select a rule on the left to see its Cypher queries.\n\n" +
                "// Correspondence Metamodel has been analyzed.");
            appendLog("Generated queries for " + listModel.size() + " rules.");
        } else {
            appendLog("Compilation failed:\n" + sw.toString());
        }
    }

    private void appendLog(String msg) {
        txtLog.append("> " + msg + "\n");
    }
}