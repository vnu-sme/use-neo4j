package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.tzi.use.gui.main.MainWindow;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystemState;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashSet;
import java.util.Set;

public class SyncStatusDialog extends JDialog {

    private final org.tzi.use.main.Session useSession;
    private final JLabel lblMetaStatus;
    private final JLabel lblDataStatus;
    private final JTable tblMetaDiff;
    private final JTable tblDataDiff;
    private final DefaultTableModel modelMeta;
    private final DefaultTableModel modelData;
    private final JButton btnRefresh;

    public SyncStatusDialog(MainWindow parent, org.tzi.use.main.Session useSession) {
        super(parent, "Synchronization Status Dashboard");
        this.useSession = useSession;

        setLayout(new BorderLayout(10, 10));
        setSize(900, 600);
        setLocationRelativeTo(parent);

        // --- TOP PANEL: SUMMARY ---
        JPanel pnlTop = new JPanel(new GridLayout(1, 2, 10, 10));
        pnlTop.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        lblMetaStatus = createStatusLabel("Metamodel Status: Checking...");
        lblDataStatus = createStatusLabel("Data Status: Checking...");
        
        pnlTop.add(createCard("Metamodel", lblMetaStatus));
        pnlTop.add(createCard("Data (Objects)", lblDataStatus));

        add(pnlTop, BorderLayout.NORTH);

        // --- CENTER PANEL: DETAILS TABS ---
        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: Metamodel Diff
        modelMeta = new DefaultTableModel(new String[]{"Element Type", "Name", "In USE", "In Neo4j", "Status"}, 0);
        tblMetaDiff = new JTable(modelMeta);
        setupTable(tblMetaDiff);
        tabs.addTab("Metamodel Differences", new JScrollPane(tblMetaDiff));

        // Tab 2: Data Diff
        modelData = new DefaultTableModel(new String[]{"ID", "Class", "In USE", "In Neo4j", "Status"}, 0);
        tblDataDiff = new JTable(modelData);
        setupTable(tblDataDiff);
        tabs.addTab("Data/Object Differences", new JScrollPane(tblDataDiff));

        add(tabs, BorderLayout.CENTER);

        // --- BOTTOM PANEL: ACTIONS ---
        JPanel pnlBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRefresh = new JButton("Refresh / Re-Check");
        JButton btnClose = new JButton("Close");

        pnlBottom.add(btnRefresh);
        pnlBottom.add(btnClose);
        add(pnlBottom, BorderLayout.SOUTH);

        // Events
        btnClose.addActionListener(e -> dispose());
        btnRefresh.addActionListener(e -> performCheck());

        // Auto Check on Open
        performCheck();
    }

    private void performCheck() {
        if (useSession.system() == null) {
            lblMetaStatus.setText("No USE Model Loaded");
            lblDataStatus.setText("No USE Data");
            return;
        }

        btnRefresh.setEnabled(false);
        lblMetaStatus.setText("Checking...");
        lblMetaStatus.setForeground(Color.BLACK);
        lblDataStatus.setText("Checking...");
        lblDataStatus.setForeground(Color.BLACK);
        
        // Clear tables
        modelMeta.setRowCount(0);
        modelData.setRowCount(0);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            boolean metaSynced = true;
            boolean dataSynced = true;

            @Override
            protected Void doInBackground() throws Exception {
                try (Session session = Neo4jDriverManager.getInstance().openSession()) {
                    checkMetamodel(session);
                    checkData(session);
                }
                return null;
            }

            // --- LOGIC CHECK METAMODEL ---
            private void checkMetamodel(Session session) {
                MModel useModel = useSession.system().model();
                Set<String> useClasses = new HashSet<>();
                for (MClass cls : useModel.classes()) {
                    useClasses.add(cls.name());
                }

                // Get Neo4j MetaClasses
                Set<String> neoClasses = new HashSet<>();
                session.executeRead(tx -> {
                    Result res = tx.run("MATCH (n:MetaClass) RETURN n.name as name");
                    while (res.hasNext()) neoClasses.add(res.next().get("name").asString());
                    return null;
                });

                // Compare
                Set<String> all = new HashSet<>(useClasses);
                all.addAll(neoClasses);

                for (String name : all) {
                    boolean inUse = useClasses.contains(name);
                    boolean inNeo = neoClasses.contains(name);
                    String status;
                    
                    if (inUse && inNeo) status = "Synced";
                    else if (inUse) { status = "Missing in Neo4j"; metaSynced = false; }
                    else { status = "Missing in USE"; metaSynced = false; }

                    if (!status.equals("Synced")) {
                        addMetaRow("Class", name, inUse, inNeo, status);
                    }
                }
            }

            // --- LOGIC CHECK DATA (OBJECTS) ---
            private void checkData(Session session) {
                MSystemState useState = useSession.system().state();
                Set<String> useIds = new HashSet<>();
                for (MObject obj : useState.allObjects()) {
                    useIds.add(obj.name());
                }

                Set<String> neoIds = new HashSet<>();
                session.executeRead(tx -> {
                    Result res = tx.run("MATCH (n) WHERE n.use_id IS NOT NULL AND NOT 'MetaClass' IN labels(n) RETURN n.use_id as id");
                    while (res.hasNext()) neoIds.add(res.next().get("id").asString());
                    return null;
                });

                // Compare
                Set<String> all = new HashSet<>(useIds);
                all.addAll(neoIds);

                for (String id : all) {
                    boolean inUse = useIds.contains(id);
                    boolean inNeo = neoIds.contains(id);
                    String status;
                    String clsName = "";

                    if (inUse) {
                        clsName = useState.objectByName(id).cls().name();
                    } else {
                        // Nếu không có trong USE, thử tìm type trong Neo4j (optional)
                        clsName = "(Unknown)"; 
                    }

                    if (inUse && inNeo) status = "Synced";
                    else if (inUse) { status = "Missing in Neo4j"; dataSynced = false; }
                    else { status = "Missing in USE"; dataSynced = false; }

                    // Chỉ hiện những cái lệch, nếu muốn hiện hết thì bỏ if
                    if (!status.equals("Synced")) {
                        addDataRow(id, clsName, inUse, inNeo, status);
                    }
                }
                
                // Nếu không có dòng lệch nào thì check xem có rỗng không
                if (dataSynced && useIds.isEmpty() && neoIds.isEmpty()) {
                    // Cả 2 đều rỗng -> Coi là sync
                }
            }

            @Override
            protected void done() {
                btnRefresh.setEnabled(true);
                updateStatusLabel(lblMetaStatus, metaSynced, modelMeta.getRowCount());
                updateStatusLabel(lblDataStatus, dataSynced, modelData.getRowCount());
            }
        };
        worker.execute();
    }

    // --- UI HELPERS ---

    private void addMetaRow(String type, String name, boolean inUse, boolean inNeo, String status) {
        SwingUtilities.invokeLater(() -> modelMeta.addRow(new Object[]{type, name, inUse, inNeo, status}));
    }

    private void addDataRow(String id, String cls, boolean inUse, boolean inNeo, String status) {
        SwingUtilities.invokeLater(() -> modelData.addRow(new Object[]{id, cls, inUse, inNeo, status}));
    }

    private void updateStatusLabel(JLabel lbl, boolean synced, int diffCount) {
        if (synced && diffCount == 0) {
            lbl.setText("SYNCED (Matched)");
            lbl.setForeground(new Color(0, 153, 0)); // Green
            lbl.setIcon(UIManager.getIcon("OptionPane.informationIcon"));
        } else {
            lbl.setText("OUT OF SYNC (" + diffCount + " diffs)");
            lbl.setForeground(Color.RED);
            lbl.setIcon(UIManager.getIcon("OptionPane.errorIcon"));
        }
    }

    private JLabel createStatusLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        return lbl;
    }

    private JPanel createCard(String title, JLabel content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        p.setBackground(Color.WHITE);
        return p;
    }

    private void setupTable(JTable table) {
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.setDefaultRenderer(Object.class, new StatusCellRenderer());
    }

    // Renderer để tô màu dòng trạng thái
    static class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String status = (String) table.getModel().getValueAt(row, 4); // Cột Status

            if (!isSelected) {
                if ("Synced".equals(status)) {
                    c.setBackground(new Color(230, 255, 230)); // Light Green
                } else if (status.contains("Missing")) {
                    c.setBackground(new Color(255, 230, 230)); // Light Red
                } else {
                    c.setBackground(Color.WHITE);
                }
            }
            return c;
        }
    }
}