package org.uet.dse.neo4j.gui;

import org.tzi.use.main.Session;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;

public class TggTransformationDialog extends JDialog {

    private JTextField txtSourceModel;
    private JTextField txtTargetModel;
    private JTextField txtTggFilePath;
    private JList<String> lstRules;
    private JTextArea txtCypherPreview;
    private JTextArea txtLog;
    private JButton btnProcess;
    private Session session;

    public TggTransformationDialog(Frame owner, org.tzi.use.main.Session session) {
        super(owner, "TGG Rule Processor & Multi-Target Generator", true);
        this.session = session;
        initComponents();

        // Tự động điền tên model hiện tại từ USE làm mặc định cho Source
        if (session.system().model() != null) {
            txtSourceModel.setText(session.system().model().name());
        }
    }

    private void initComponents() {
        setSize(900, 700);
        setLocationRelativeTo(getOwner());
        setLayout(new BorderLayout(10, 10));


        JPanel pnlInput = new JPanel(new GridBagLayout());
        pnlInput.setBorder(new CompoundBorder(new EmptyBorder(10, 10, 10, 10), new TitledBorder("Configuration")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        pnlInput.add(new JLabel("Source Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        txtSourceModel = new JTextField("ModelA");
        pnlInput.add(txtSourceModel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        pnlInput.add(new JLabel("Target Model Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        txtTargetModel = new JTextField("ModelB");
        pnlInput.add(txtTargetModel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        pnlInput.add(new JLabel("TGG Rule File (.tgg):"), gbc);
        gbc.gridx = 1;
        JPanel pnlFile = new JPanel(new BorderLayout(5, 0));
        txtTggFilePath = new JTextField();
        pnlFile.add(txtTggFilePath, BorderLayout.CENTER);
        JButton btnBrowse = new JButton("Browse...");
        btnBrowse.addActionListener(e -> chooseFile());
        pnlFile.add(btnBrowse, BorderLayout.EAST);
        pnlInput.add(pnlFile, gbc);

        add(pnlInput, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(250);

        lstRules = new JList<>(new DefaultListModel<>());
        lstRules.setBorder(new TitledBorder("Detected Rules"));
        splitPane.setLeftComponent(new JScrollPane(lstRules));

        txtCypherPreview = new JTextArea();
        txtCypherPreview.setEditable(false);
        txtCypherPreview.setBackground(new Color(245, 245, 245));
        txtCypherPreview.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPreview = new JScrollPane(txtCypherPreview);
        scrollPreview.setBorder(new TitledBorder("Generated Cypher Queries (Matching & Execution)"));
        splitPane.setRightComponent(scrollPreview);

        add(splitPane, BorderLayout.CENTER);

        JPanel pnlBottom = new JPanel(new BorderLayout());
        
        txtLog = new JTextArea(5, 0);
        txtLog.setEditable(false);
        txtLog.setBackground(Color.BLACK);
        txtLog.setForeground(new Color(0, 255, 0));
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(new TitledBorder("System Log"));
        pnlBottom.add(scrollLog, BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> dispose());
        btnProcess = new JButton("Process & Generate Model");
        btnProcess.setPreferredSize(new Dimension(200, 30));
        btnProcess.addActionListener(e -> startProcessing());
        
        pnlButtons.add(btnCancel);
        pnlButtons.add(btnProcess);
        pnlBottom.add(pnlButtons, BorderLayout.SOUTH);

        add(pnlBottom, BorderLayout.SOUTH);
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            txtTggFilePath.setText(chooser.getSelectedFile().getAbsolutePath());
            appendLog("Selected TGG file: " + chooser.getSelectedFile().getName());
        }
    }

    private void startProcessing() {
        String source = txtSourceModel.getText();
        String target = txtTargetModel.getText();
        String path = txtTggFilePath.getText();

        if (source.isEmpty() || target.isEmpty() || path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please fill all fields", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // TODO
        appendLog("Starting TGG transformation: " + source + " -> " + target);

        DefaultListModel<String> model = (DefaultListModel<String>) lstRules.getModel();
        model.addElement("Rule 1: MapClassToNode");
        model.addElement("Rule 2: MapAttributeToProp");
        
        appendLog("Success: Generated 2 rules and their Cypher queries.");
    }

    private void appendLog(String msg) {
        txtLog.append("> " + msg + "\n");
    }

}