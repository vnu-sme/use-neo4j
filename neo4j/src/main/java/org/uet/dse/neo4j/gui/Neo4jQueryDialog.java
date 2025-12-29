package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.summary.ResultSummary;
import org.tzi.use.gui.main.MainWindow;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Vector;

public class Neo4jQueryDialog extends JDialog {

    private final JTextArea txtQuery;
    private final JTable tableResult;
    private final DefaultTableModel tableModel;
    private final JTextArea txtLog;
    private final JButton btnRun;

    public Neo4jQueryDialog(MainWindow parent) {
        super(parent, "Neo4j Query Console");
        setLayout(new BorderLayout());

        // --- PHẦN TRÊN: NHẬP QUERY ---
        JPanel pnlTop = new JPanel(new BorderLayout());
        pnlTop.setBorder(BorderFactory.createTitledBorder("Cypher Query"));
        
        txtQuery = new JTextArea(5, 50);
        txtQuery.setFont(new Font("Monospaced", Font.PLAIN, 14));
        txtQuery.setText("MATCH (n) RETURN n LIMIT 25"); // Query mặc định
        pnlTop.add(new JScrollPane(txtQuery), BorderLayout.CENTER);

        btnRun = new JButton("Run Query (Execute)");
        btnRun.setBackground(new Color(60, 179, 113));
        btnRun.setForeground(Color.BLACK);
        pnlTop.add(btnRun, BorderLayout.SOUTH);

        // --- PHẦN GIỮA: KẾT QUẢ BẢNG ---
        tableModel = new DefaultTableModel();
        tableResult = new JTable(tableModel);
        tableResult.setFillsViewportHeight(true);
        JScrollPane scrollTable = new JScrollPane(tableResult);
        scrollTable.setBorder(BorderFactory.createTitledBorder("Result Table"));

        // --- PHẦN DƯỚI: LOG ---
        txtLog = new JTextArea(6, 50);
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txtLog.setForeground(Color.BLUE);
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Execution Log"));

        // Sử dụng SplitPane để chia đôi màn hình kết quả và log
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollTable, scrollLog);
        splitPane.setResizeWeight(0.7); // Bảng chiếm 70%, log chiếm 30%

        // Thêm vào Dialog
        add(pnlTop, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        // --- SỰ KIỆN ---
        btnRun.addActionListener(e -> runQuery());

        // Cấu hình Dialog
        setSize(800, 600);
        setLocationRelativeTo(parent);
        setModal(false); // Để false để vừa xem query vừa thao tác bên USE được
    }

    private void runQuery() {
        String cypher = txtQuery.getText().trim();
        if (cypher.isEmpty()) return;

        // Reset giao diện
        tableModel.setRowCount(0);
        tableModel.setColumnCount(0);
        txtLog.setText("Executing...\n");
        btnRun.setEnabled(false);

        // Chạy trong Thread khác để không làm đơ giao diện USE
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                // Kiểm tra kết nối lại lần nữa cho chắc
                if (Neo4jDriverManager.getInstance() == null || !Neo4jDriverManager.getInstance().isConnected()) {
                    publish("ERROR: Mất kết nối tới Neo4j. Vui lòng kết nối lại.");
                    return null;
                }

                // Sử dụng hàm openSession để tự động trỏ vào DB đã cấu hình
                try (Session session = Neo4jDriverManager.getInstance().openSession()) {
                    session.executeWrite(tx -> {
                        long startTime = System.currentTimeMillis();
                        
                        // Thực thi Query
                        Result result = tx.run(cypher);
                        
                        // 1. Xử lý Header (Cột bảng)
                        List<String> keys = result.keys();
                        Vector<String> columns = new Vector<>(keys);
                        
                        // Cập nhật giao diện bảng (phải dùng invokeLater vì đang ở thread khác)
                        SwingUtilities.invokeLater(() -> tableModel.setColumnIdentifiers(columns));

                        // 2. Xử lý Dữ liệu (Hàng)
                        int count = 0;
                        while (result.hasNext()) {
                            org.neo4j.driver.Record record = result.next();
                            Vector<Object> rowData = new Vector<>();
                            
                            for (String key : keys) {
                                // Convert mọi thứ thành String để hiển thị cho an toàn
                                rowData.add(record.get(key).toString());
                            }
                            
                            SwingUtilities.invokeLater(() -> tableModel.addRow(rowData));
                            count++;
                        }

                        // 3. Lấy thông tin thống kê (Summary)
                        ResultSummary summary = result.consume();
                        long timeTaken = System.currentTimeMillis() - startTime;
                        
                        StringBuilder logMsg = new StringBuilder();
                        logMsg.append("--- SUCCESS ---\n");
                        logMsg.append("Time: ").append(timeTaken).append(" ms\n");
                        logMsg.append("Records returned: ").append(count).append("\n");
                        
                        // Thông báo nếu là lệnh Ghi (Create/Delete/Update)
                        if (summary.counters().containsUpdates()) {
                            logMsg.append("Nodes created: ").append(summary.counters().nodesCreated()).append("\n");
                            logMsg.append("Nodes deleted: ").append(summary.counters().nodesDeleted()).append("\n");
                            logMsg.append("Relationships created: ").append(summary.counters().relationshipsCreated()).append("\n");
                            logMsg.append("Properties set: ").append(summary.counters().propertiesSet()).append("\n");
                        }
                        
                        publish(logMsg.toString());
                        return null;
                    });
                } catch (Exception e) {
                    publish("ERROR: " + e.getMessage());
                    e.printStackTrace(); // In ra console của IDE để debug thêm nếu cần
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    txtLog.append(msg + "\n");
                    // Tự động cuộn xuống cuối log
                    txtLog.setCaretPosition(txtLog.getDocument().getLength());
                }
            }

            @Override
            protected void done() {
                btnRun.setEnabled(true);
            }
        }.execute();
    }
}