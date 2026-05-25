package org.uet.dse.neo4j.gui;

import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.main.Session;
import org.uet.dse.neo4j.config.Neo4jDefaultConnectionConfig;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.manager.SessionManager;
import org.uet.dse.neo4j.manager.WorkLogManager;
import org.uet.dse.neo4j.realtime.Neo4jRealTimeService;
import org.uet.dse.neo4j.repo.Neo4jModelRepository;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

public class Neo4jConnectionDialog extends JDialog {

	private final JTextField txtUri;
	private final JTextField txtUser;
	private final JPasswordField txtPass;
	private final JTextField txtDatabase;
	private final MainWindow mainWindow;
	private final Session useSession;

	// DB Options
	private final JRadioButton rbConnectExisting;
	private final JRadioButton rbCreateNew;
	private final JCheckBox chkDeleteOnExit;

//	// Collaboration Session Options
//	private final JRadioButton rbNewSession;
//	private final JRadioButton rbJoinSession;
//	private final JTextField txtSessionKey;
//	private final JLabel lblGeneratedKey;

	public Neo4jConnectionDialog(MainWindow parent, Session session) {
		super(parent, "Neo4j Database Setup", true);
		this.mainWindow = parent;
		this.useSession = session;

		// --- Layout Main ---
		Container content = getContentPane();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		((JComponent) content).setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

		// --- 1. Connection Section ---
		JPanel pnlConn = new JPanel(new GridLayout(3, 2, 5, 5));
		pnlConn.setBorder(BorderFactory.createTitledBorder("Server Connection"));

		pnlConn.add(new JLabel("URI Server:"));
		txtUri = new JTextField(Neo4jDefaultConnectionConfig.uri, 20);
		pnlConn.add(txtUri);

		pnlConn.add(new JLabel("Username:"));
		txtUser = new JTextField(Neo4jDefaultConnectionConfig.user, 20);
		pnlConn.add(txtUser);

		pnlConn.add(new JLabel("Password:"));
		txtPass = new JPasswordField(Neo4jDefaultConnectionConfig.password, 20);
		pnlConn.add(txtPass);
		content.add(pnlConn);

		content.add(Box.createVerticalStrut(10));

		// --- 2. Database Options Section ---
		JPanel pnlDbOpts = new JPanel(new GridLayout(0, 1));
		pnlDbOpts.setBorder(BorderFactory.createTitledBorder("Database Settings"));

		rbConnectExisting = new JRadioButton("Connect Existing DB", true);
		rbCreateNew = new JRadioButton("Create New DB");
		ButtonGroup dbGroup = new ButtonGroup();
		dbGroup.add(rbConnectExisting);
		dbGroup.add(rbCreateNew);

		JPanel pnlDbMode = new JPanel(new FlowLayout(FlowLayout.LEFT));
		pnlDbMode.add(rbConnectExisting);
		pnlDbMode.add(rbCreateNew);
		pnlDbOpts.add(pnlDbMode);

		JPanel pnlDbName = new JPanel(new FlowLayout(FlowLayout.LEFT));
		pnlDbName.add(new JLabel("DB Name: "));
		txtDatabase = new JTextField(Neo4jDefaultConnectionConfig.database, 12);
		pnlDbName.add(txtDatabase);

		chkDeleteOnExit = new JCheckBox("Delete DB on Exit");
		chkDeleteOnExit.setForeground(Color.RED);
		chkDeleteOnExit.setEnabled(false);
		pnlDbName.add(chkDeleteOnExit);
		pnlDbOpts.add(pnlDbName);
		content.add(pnlDbOpts);

		// Logic DB Mode
		ActionListener dbModeListener = e -> {
			boolean isCreate = rbCreateNew.isSelected();
			chkDeleteOnExit.setEnabled(isCreate);
			if (isCreate && txtDatabase.getText().equals("neo4j")) {
				txtDatabase.setText("uml_db_" + System.currentTimeMillis() / 10000);
			}
		};
		rbConnectExisting.addActionListener(dbModeListener);
		rbCreateNew.addActionListener(dbModeListener);

		content.add(Box.createVerticalStrut(10));

		// --- 3. Collaboration Session Section ---
//		JPanel pnlSession = new JPanel(new GridLayout(0, 1));
//		pnlSession.setBorder(BorderFactory.createTitledBorder("Collaboration Session"));
//
//		rbNewSession = new JRadioButton("Start New Session (Owner)", true);
//		rbJoinSession = new JRadioButton("Join Existing Session (Member)");
//		ButtonGroup sessionGroup = new ButtonGroup();
//		sessionGroup.add(rbNewSession);
//		sessionGroup.add(rbJoinSession);
//
//		pnlSession.add(rbNewSession);
//		lblGeneratedKey = new JLabel("  Key: (Will be generated)");
//		lblGeneratedKey.setFont(new Font("Monospaced", Font.BOLD, 12));
//		pnlSession.add(lblGeneratedKey);
//
//		pnlSession.add(rbJoinSession);
//		JPanel pnlKeyInput = new JPanel(new FlowLayout(FlowLayout.LEFT));
//		pnlKeyInput.add(new JLabel("  Enter Key: "));
//		txtSessionKey = new JTextField(15);
//		txtSessionKey.setEnabled(false);
//		pnlKeyInput.add(txtSessionKey);
//		pnlSession.add(pnlKeyInput);
//		content.add(pnlSession);
//
//		// Logic Session Mode
//		rbJoinSession.addActionListener(e -> txtSessionKey.setEnabled(true));
//		rbNewSession.addActionListener(e -> {
//			txtSessionKey.setEnabled(false);
//			txtSessionKey.setText("");
//		});

		content.add(Box.createVerticalStrut(15));

		// --- 4. Bottom Buttons ---
		JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton btnConnect = new JButton("Connect & Open Dashboard");
		btnConnect.setBackground(new Color(0, 102, 204));
		btnConnect.setForeground(Color.WHITE);

		JButton btnCancel = new JButton("Cancel");
		pnlBtns.add(btnConnect);
		pnlBtns.add(btnCancel);
		content.add(pnlBtns);

		// Event Listeners
		btnCancel.addActionListener(e -> dispose());
		btnConnect.addActionListener(e -> doConnect());

		// Dialog Config
		this.pack();
		if (this.getWidth() < 400) {
			this.setSize(420, this.getHeight());
		}

		this.setLocationRelativeTo(parent);
		this.setResizable(false);
	}

	private void doConnect() {
		String uri = txtUri.getText().trim();
		String user = txtUser.getText().trim();
		String pass = new String(txtPass.getPassword());
		String dbName = txtDatabase.getText().trim();
		boolean createNew = rbCreateNew.isSelected();
		boolean deleteOnExit = chkDeleteOnExit.isSelected();

		if (dbName.isEmpty()) {
			JOptionPane.showMessageDialog(this, "Database name required!");
			return;
		}

		try {
				setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				Neo4jDriverManager.connect(uri, user, pass, dbName, createNew, deleteOnExit);

				Neo4jModelRepository repo = new Neo4jModelRepository();
				repo.initializeMetamodel(this.useSession.system().model().name());

				SessionManager sm = new SessionManager();
				sm.joinSession("default-session");

				Neo4jDriverManager.getInstance().setSessionManager(sm);
				Neo4jRealTimeService.start(this.useSession);

				Neo4jDashboardViewLite dashboard = Neo4jDashboardViewLite.getInstance(mainWindow, useSession);
				dashboard.refreshData();
				dashboard.setVisible(true);

				JOptionPane.showMessageDialog(this, "Connected to Database: " + dbName);

				this.dispose();

		} catch (Exception ex) {
			WorkLogManager.getInstance().log("CONNECTION_ERROR", ex.getMessage());
			JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Connection Failed", JOptionPane.ERROR_MESSAGE);
			ex.printStackTrace();
		} finally {
			setCursor(Cursor.getDefaultCursor());
		}
	}
}