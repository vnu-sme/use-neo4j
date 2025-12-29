package org.uet.dse.neo4j.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;

/**
 * Neo4j Plugin Main Interface
 */
@SuppressWarnings("serial")
public class Neo4jMainDialog extends JDialog {
    
    // Các biến lưu trữ tham chiếu đến hệ thống USE (để dùng sau này)
    private MainWindow mainWindow;
    private Session session;

    // UI Components
    private JTextField txtUri;
    private JTextField txtUsername;
    private JPasswordField txtPassword;
    private JTextArea txtLog;
    private JButton btnConnect;
    private JButton btnClose;

    public Neo4jMainDialog(MainWindow parent, Session session) {
        super(parent, "Neo4j Graph Connection");
        this.mainWindow = parent;
        this.session = session;
        
        // Cấu hình Dialog
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setModal(false); // Để false để vẫn thao tác được với cửa sổ chính
        
        initComponents();
        layoutComponents();
        addListeners();
        
        // Tự động chỉnh kích thước và vị trí
        pack();
        setLocationRelativeTo(parent); // Hiện giữa cửa sổ chính
        setSize(500, 400); // Kích thước mặc định
    }

    private void initComponents() {
        txtUri = new JTextField("bolt://localhost:7687", 20);
        txtUsername = new JTextField("neo4j", 20);
        txtPassword = new JPasswordField("", 20);
        
        txtLog = new JTextArea(10, 40);
        txtLog.setEditable(false);
        txtLog.setBackground(new Color(240, 240, 240));
        txtLog.setText("Ready to connect...\n");

        btnConnect = new JButton("Test Connection");
        btnClose = new JButton("Close");
    }

    private void layoutComponents() {
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setBorder(BorderFactory.createTitledBorder("Connection Details"));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 0: URI
        gbc.gridx = 0; gbc.gridy = 0;
        inputPanel.add(new JLabel("URI:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        inputPanel.add(txtUri, gbc);

        // Row 1: Username
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        inputPanel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        inputPanel.add(txtUsername, gbc);

        // Row 2: Password
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        inputPanel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        inputPanel.add(txtPassword, gbc);

        // Button Panel
        JPanel btnPanel = new JPanel();
        btnPanel.add(btnConnect);
        btnPanel.add(btnClose);

        // Main Layout
        JPanel mainContent = new JPanel(new BorderLayout(10, 10));
        mainContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        mainContent.add(inputPanel, BorderLayout.NORTH);
        mainContent.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        mainContent.add(btnPanel, BorderLayout.SOUTH);

        setContentPane(mainContent);
    }

    private void addListeners() {
        // Sự kiện nút Close
        btnClose.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose(); // Đóng cửa sổ
            }
        });

        // Sự kiện nút Connect (Giả lập)
        btnConnect.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performDummyConnection();
            }
        });
    }

    private void performDummyConnection() {
        String uri = txtUri.getText();
        String user = txtUsername.getText();
        
        appendToLog("----------------------------");
        appendToLog("Attempting to connect to: " + uri);
        appendToLog("User: " + user);
        
        // Giả lập loading (nếu làm thật thì dùng SwingWorker để ko đơ giao diện)
        btnConnect.setEnabled(false);
        
        SwingUtilities.invokeLater(() -> {
            try {
                // Code kết nối DB thật sẽ viết ở đây
                // Thread.sleep(500); // Giả vờ đợi
                appendToLog("Connection SUCCESSFUL! (Simulated)");
                appendToLog("Found system: " + "uk"); // Lấy tên system từ USE
            } catch (Exception ex) {
                appendToLog("Error: " + ex.getMessage());
            } finally {
                btnConnect.setEnabled(true);
            }
        });
    }

    private void appendToLog(String msg) {
        txtLog.append(msg + "\n");
        txtLog.setCaretPosition(txtLog.getDocument().getLength()); // Auto scroll xuống dưới
    }
}