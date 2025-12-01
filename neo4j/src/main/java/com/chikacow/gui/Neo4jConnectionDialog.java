package com.chikacow.gui;

import com.chikacow.Neo4jConfig;
import com.chikacow.manager.Neo4jDriverManager;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class Neo4jConnectionDialog extends JDialog {

	private final JTextField txtUri;
	private final JTextField txtUser;
	private final JPasswordField txtPass;
	private final JTextField txtDatabase;
	private final MainWindow mainWindow;

	// Các thành phần UI mới cho tính năng tạo DB
	private final JRadioButton rbConnectExisting;
	private final JRadioButton rbCreateNew;
	private final JCheckBox chkDeleteOnExit;

	public Neo4jConnectionDialog(MainWindow parent, Session session) {
		super(parent, "Connect to Neo4j Database");
		this.mainWindow = parent;

		// --- Layout Setting ---
		Container content = getContentPane();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		((JComponent) content).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// --- 1. URI Field ---
		JPanel pnlUri = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel lblUri = new JLabel("URI Server:  ");
		lblUri.setPreferredSize(new Dimension(80, 20));
		pnlUri.add(lblUri);

		// Lấy giá trị default từ file config
		txtUri = new JTextField(Neo4jConfig.uri, 25);
		pnlUri.add(txtUri);
		content.add(pnlUri);

		// --- 2. Username Field ---
		JPanel pnlUser = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel lblUser = new JLabel("Username:   ");
		lblUser.setPreferredSize(new Dimension(80, 20));
		pnlUser.add(lblUser);

		txtUser = new JTextField(Neo4jConfig.user, 25);
		pnlUser.add(txtUser);
		content.add(pnlUser);

		// --- 3. Password Field ---
		JPanel pnlPass = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel lblPass = new JLabel("Password:   ");
		lblPass.setPreferredSize(new Dimension(80, 20));
		pnlPass.add(lblPass);

		txtPass = new JPasswordField(Neo4jConfig.password, 25);
		pnlPass.add(txtPass);
		content.add(pnlPass);

		content.add(new JSeparator());

		// --- 4. DATABASE MODE SELECTION (Mới) ---
		JPanel pnlMode = new JPanel(new FlowLayout(FlowLayout.LEFT));
		pnlMode.setBorder(BorderFactory.createTitledBorder("Database Option"));

		rbConnectExisting = new JRadioButton("Connect Existing DB", true);
		rbCreateNew = new JRadioButton("Create New DB");
		ButtonGroup group = new ButtonGroup();
		group.add(rbConnectExisting);
		group.add(rbCreateNew);

		pnlMode.add(rbConnectExisting);
		pnlMode.add(rbCreateNew);
		content.add(pnlMode);

		// --- 5. DATABASE NAME & OPTIONS ---
		JPanel pnlDb = new JPanel(new FlowLayout(FlowLayout.LEFT));
		pnlDb.add(new JLabel("DB Name:     "));
		txtDatabase = new JTextField(Neo4jConfig.database, 15);
		pnlDb.add(txtDatabase);

		chkDeleteOnExit = new JCheckBox("Delete DB on Exit (Cleanup)");
		chkDeleteOnExit.setForeground(Color.RED);
		chkDeleteOnExit.setEnabled(false); // Mặc định ẩn vì đang chọn Existing
		pnlDb.add(chkDeleteOnExit);
		content.add(pnlDb);

		// Logic ẩn hiện checkbox và gợi ý tên DB
		ActionListener radioListener = e -> {
			boolean isCreate = rbCreateNew.isSelected();
			chkDeleteOnExit.setEnabled(isCreate);

			// Nếu chuyển sang chế độ Connect Existing thì bỏ chọn xóa để an toàn
			if (!isCreate) {
				chkDeleteOnExit.setSelected(false);
			}

			// Gợi ý tên DB ngẫu nhiên nếu chọn Create New
			if (isCreate && txtDatabase.getText().equals("neo4j")) {
				txtDatabase.setText("temp_db_" + System.currentTimeMillis() / 1000);
			}
		};
		rbConnectExisting.addActionListener(radioListener);
		rbCreateNew.addActionListener(radioListener);

		// --- 6. Buttons ---
		JPanel pnlBtn = new JPanel(new FlowLayout(FlowLayout.CENTER));
		JButton btnConnect = new JButton("Connect");
		JButton btnCancel = new JButton("Cancel");

		// Làm đẹp nút Connect
		btnConnect.setBackground(new Color(60, 179, 113)); // Màu xanh Neo4j
		btnConnect.setForeground(Color.BLACK);
		btnConnect.setOpaque(true);

		pnlBtn.add(btnConnect);
		pnlBtn.add(btnCancel);
		content.add(Box.createVerticalStrut(10));
		content.add(pnlBtn);

		// --- Event Listeners ---
		btnCancel.addActionListener(e -> dispose());
		btnConnect.addActionListener(e -> doConnect());

		// Cấu hình Dialog
		pack();
		setResizable(false);
		setLocationRelativeTo(parent);
		setModal(true);
		getRootPane().setDefaultButton(btnConnect); // Enter là tự bấm Connect
	}

	private void doConnect() {
		// 1. Lấy dữ liệu từ Form
		String uri = txtUri.getText().trim();
		String user = txtUser.getText().trim();
		String pass = new String(txtPass.getPassword());
		String dbName = txtDatabase.getText().trim();

		// Lấy trạng thái từ Radio Button và Checkbox
		boolean createNew = rbCreateNew.isSelected();
		boolean deleteOnExit = chkDeleteOnExit.isSelected();

		// 2. Validation (Kiểm tra dữ liệu)
		if (dbName.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Tên Database không được để trống!", "Lỗi nhập liệu", JOptionPane.WARNING_MESSAGE);
			return;
		}

		// Không cho phép xóa database hệ thống
		if (deleteOnExit && (dbName.equals("neo4j") || dbName.equals("system"))) {
			JOptionPane.showMessageDialog(this,
					"CẢNH BÁO: Không được phép xóa database hệ thống ('neo4j' hoặc 'system')!",
					"Lỗi bảo mật", JOptionPane.ERROR_MESSAGE);
			return;
		}

		try {
			setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

			// 3. Gọi Manager để xử lý kết nối (bao gồm cả tạo mới nếu cần)
			// Lưu ý: Hàm connect này nhận 6 tham số
			Neo4jDriverManager.connect(uri, user, pass, dbName, createNew, deleteOnExit);

			// 4. Kiểm tra lại kết nối bằng cách chạy thử 1 query nhẹ
			try (org.neo4j.driver.Session session = Neo4jDriverManager.getInstance().openSession()) {
				String dbInfo = session.executeWrite(tx -> {
					// Query lấy tên DB hiện tại
					org.neo4j.driver.Result result = tx.run("CALL db.info() YIELD name RETURN name");
					if (result.hasNext()) return result.single().get("name").asString();
					return "Unknown";
				});

				// Chuẩn bị thông báo thành công
				StringBuilder msg = new StringBuilder();
				if (createNew) {
					msg.append("Đã TẠO MỚI và kết nối thành công vào: ").append(dbInfo);
				} else {
					msg.append("Đã kết nối thành công vào: ").append(dbInfo);
				}

				if (deleteOnExit) {
					msg.append("\n\n(LƯU Ý: Database này sẽ bị XÓA TỰ ĐỘNG khi bạn đóng Plugin)");
				}

				// Ghi log ra USE GUI
				mainWindow.logWriter().println("Neo4j Plugin: " + msg.toString().replace("\n", " "));

				// Hiển thị thông báo
				JOptionPane.showMessageDialog(this, msg.toString(), "Success", JOptionPane.INFORMATION_MESSAGE);
				dispose(); // Đóng dialog
			}

		} catch (org.neo4j.driver.exceptions.ClientException e) {
			// Xử lý lỗi đặc thù Neo4j (ví dụ DB không tồn tại, sai cú pháp...)
			String errParams = e.getMessage();
			if (errParams.contains("Database does not exist")) {
				JOptionPane.showMessageDialog(this,
						"Lỗi: Database '" + dbName + "' không tồn tại!\nHãy chọn 'Create New DB' nếu bạn muốn tạo mới.",
						"Database Error", JOptionPane.ERROR_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(this, "Lỗi Neo4j: " + e.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			JOptionPane.showMessageDialog(this, "Lỗi kết nối: " + ex.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
		} finally {
			setCursor(Cursor.getDefaultCursor());
		}
	}
}