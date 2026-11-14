package org.uet.dse.ocl2cypher.plugin.ui;

import static org.uet.dse.ocl2cypher.plugin.ui.WorkbenchStyle.*;

import org.neo4j.driver.*;
import org.tzi.use.gui.main.MainWindow;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.Serializer;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.execution.Neo4jExecutionAdapter;
import org.uet.dse.ocl2cypher.execution.Neo4jBrowserScript;
import org.uet.dse.ocl2cypher.execution.UseEvaluationAdapter;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.input.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Dark, three-column OCL2Cypher workbench embedded in USE. */
public class Ocl2CypherDialog extends JDialog {
    private final JTextField uri = field(Neo4jExecutionAdapter.DEFAULT_URI);
    private final JTextField database = field(Neo4jExecutionAdapter.DEFAULT_DATABASE);
    private final JTextField user = field(Neo4jExecutionAdapter.DEFAULT_USER);
    private final JPasswordField password = passwordField();
    private final JLabel connectionStatus = smallLabel("Not connected");
    private final JButton connect = button("Connect", true);
    private final JTextField usePath = field("");
    private final JTextField soilPath = field("");
    private final JTextField oclPath = field("");
    private final JTextField invariantName = field("");
    private final JPanel invariantRows = panel(new GridBagLayout(), SURFACE);

    private final JButton load = button("1. Load Graph", false);
    private final JButton compile = button("2. Compile OCL", false);
    private final JButton translate = button("Translate OCL", false);
    private final JButton execute = button("3. Execute Query", true);

    private final JTextArea cypher = area(true, new Color(208, 216, 166));
    private final JTextArea resultText = area(false, TEXT);
    private final JTextArea statusText = area(false, TEXT);
    private final JLabel footerStatus = smallLabel("Ready");
    private final JLabel clock = smallLabel("");
    private final GraphCanvas graph = new GraphCanvas();

    private final DefaultTableModel resultModel = new DefaultTableModel(
            new Object[]{"#", "USE ID", "Result"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable resultTable = new JTable(resultModel);
    private final TableRowSorter<DefaultTableModel> resultSorter = new TableRowSorter<>(resultModel);

    private final AtomicReference<ConnectedDriver> driverRef = new AtomicReference<>();
    private final AtomicReference<LoadPreparation> inputRef = new AtomicReference<>();
    private final AtomicReference<GraphBuilder.GraphBuildArtifact> graphRef = new AtomicReference<>();
    private final AtomicReference<CypherAst.GeneratedArtifact> artifactRef = new AtomicReference<>();
    private final AtomicReference<Serializer.Serialized> serializedRef = new AtomicReference<>();
    private final AtomicReference<MaterializedGraph> materializedRef = new AtomicReference<>();
    private final AtomicReference<UseEvaluationAdapter.Result> useEvaluationRef = new AtomicReference<>();
    private final AtomicBoolean queryRunning = new AtomicBoolean();

    public Ocl2CypherDialog(MainWindow parent, org.tzi.use.main.Session ignored) {
        super(parent, "OCL2Cypher", false);
        initUi();
    }

    private void initUi() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setBackground(WINDOW);
        setLayout(new BorderLayout());
        setSize(1280, 760);
        setMinimumSize(new Dimension(1024, 620));
        add(topBar(), BorderLayout.NORTH);
        add(workspace(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        connect.addActionListener(e -> toggleConnection());
        load.addActionListener(e -> loadFiles());
        compile.addActionListener(e -> compileSelected(false));
        translate.addActionListener(e -> compileSelected(true));
        execute.addActionListener(e -> executeQuery());
        compile.setEnabled(false);
        translate.setEnabled(false);
        execute.setEnabled(false);
        load.setEnabled(false);
        status("OCL2Cypher is ready. Connect to Neo4j, then load a USE/SOIL snapshot.");
        new javax.swing.Timer(1000, e -> clock.setText(
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))).start();
        setLocationRelativeTo(getParent());
    }

    private JComponent topBar() {
        JPanel bar = panel(new BorderLayout(10, 0), WINDOW);
        bar.setPreferredSize(new Dimension(100, 43));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        JPanel nav = panel(new FlowLayout(FlowLayout.LEFT, 0, 0), WINDOW);
        nav.add(nav("Connection", true)); nav.add(nav("Files", false)); nav.add(nav("Workflow", false));
        JPanel actions = panel(new FlowLayout(FlowLayout.LEFT, 7, 6), WINDOW);
        actions.add(connect); actions.add(load); actions.add(compile); actions.add(translate); actions.add(execute);
        JPanel exports = panel(new FlowLayout(FlowLayout.RIGHT, 6, 6), WINDOW);
        JButton exportCypher = button("Ex ↗", true);
        exportCypher.setToolTipText("Export generated Cypher");
        exportCypher.addActionListener(e -> exportDesktopCypher());
        JButton exportResult = button("E↗", true);
        exportResult.setToolTipText("Export comparison result");
        exportResult.addActionListener(e -> export(resultText.getText(), "result.txt", "txt"));
        exports.add(exportCypher); exports.add(exportResult);
        JPanel right = panel(new BorderLayout(), WINDOW);
        right.add(actions, BorderLayout.WEST); right.add(exports, BorderLayout.EAST);
        bar.add(nav, BorderLayout.WEST); bar.add(right, BorderLayout.CENTER);
        return bar;
    }

    private JComponent workspace() {
        JSplitPane centerRight = split(JSplitPane.HORIZONTAL_SPLIT, centerColumn(), rightColumn(), .66);
        JSplitPane all = split(JSplitPane.HORIZONTAL_SPLIT, leftColumn(), centerRight, .315);
        SwingUtilities.invokeLater(() -> { all.setDividerLocation(.315); centerRight.setDividerLocation(.66); });
        return all;
    }

    private JComponent leftColumn() {
        JPanel p = panel(new GridBagLayout(), WINDOW);
        p.setBorder(BorderFactory.createEmptyBorder(7, 5, 7, 4));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.fill = GridBagConstraints.BOTH; g.weightx = 1; g.insets = new Insets(0, 0, 7, 0);
        g.gridy = 0; p.add(connectionCard(), g);
        g.gridy = 1; p.add(filesCard(), g);
        g.gridy = 2; g.weighty = 1; g.insets = new Insets(0, 0, 0, 0); p.add(invariantsCard(), g);
        return p;
    }

    private JComponent connectionCard() {
        JPanel content = panel(new GridBagLayout(), SURFACE);
        GridBagConstraints g = formGbc();
        addRow(content, g, 0, "URI", uri, icon("⚙", "Connection settings"));
        JPanel credentials = panel(new GridLayout(1, 3, 7, 0), SURFACE);
        credentials.add(labelled("Database", database)); credentials.add(labelled("User", user));
        credentials.add(labelled("Password", password));
        g.gridx = 0; g.gridy = 1; g.gridwidth = 3; g.weightx = 1; content.add(credentials, g);
        JButton secondConnect = button("Connect", true);
        secondConnect.addActionListener(e -> toggleConnection());
        connect.addPropertyChangeListener("text", e -> secondConnect.setText(connect.getText()));
        JPanel bottom = panel(new BorderLayout(7, 0), SURFACE);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        bottom.add(secondConnect, BorderLayout.WEST); bottom.add(connectionStatus, BorderLayout.CENTER);
        g.gridy = 2; content.add(bottom, g);
        return card("Neo4j Connection", null, content);
    }

    private JComponent filesCard() {
        JPanel content = panel(new GridBagLayout(), SURFACE);
        GridBagConstraints g = formGbc();
        addRow(content, g, 0, "USE File", usePath, browse(usePath, "use"));
        addRow(content, g, 1, "SOIL File", soilPath, browse(soilPath, "soil"));
        addRow(content, g, 2, "OCL File (optional)", oclPath, browse(oclPath, "ocl"));
        JButton loadFiles = button("Load Graph into Neo4j", true); loadFiles.addActionListener(e -> loadFiles());
        g.gridx = 0; g.gridy = 3; g.gridwidth = 3; g.insets = new Insets(7, 0, 0, 0); content.add(loadFiles, g);
        return card("Input Files", null, content);
    }

    private JComponent invariantsCard() {
        JPanel tools = panel(new FlowLayout(FlowLayout.RIGHT, 5, 0), SURFACE);
        JButton add = button("Add", true), clear = button("Clear", false);
        tools.add(add); tools.add(clear);
        JPanel content = panel(new BorderLayout(0, 6), SURFACE);
        JPanel name = panel(new BorderLayout(6, 0), SURFACE);
        name.add(smallLabel("Name"), BorderLayout.WEST); name.add(invariantName, BorderLayout.CENTER);
        content.add(name, BorderLayout.NORTH);
        JScrollPane rows = scroll(invariantRows); rows.setBorder(BorderFactory.createLineBorder(BORDER));
        content.add(rows, BorderLayout.CENTER);
        add.addActionListener(e -> addInvariantByName());
        clear.addActionListener(e -> { invariantName.setText(""); selectInvariant(null, ""); });
        return card("Invariants", tools, content);
    }

    private JComponent centerColumn() {
        JSplitPane p = split(JSplitPane.VERTICAL_SPLIT, graphCard(), cypherCard(), .56);
        p.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 0));
        SwingUtilities.invokeLater(() -> p.setDividerLocation(.56));
        return p;
    }

    private JComponent graphCard() {
        JPanel tools = panel(new FlowLayout(FlowLayout.RIGHT, 4, 0), SURFACE);
        JButton schema = button("Schema", false);
        schema.setToolTipText("Return to the schema-only graph");
        JButton plus = icon("+", "Zoom in"), minus = icon("−", "Zoom out"), fit = icon("⛶", "Fit graph"), legend = icon("▽", "Toggle legend");
        schema.addActionListener(e -> graph.resetToSchema());
        plus.addActionListener(e -> graph.zoomBy(1.18)); minus.addActionListener(e -> graph.zoomBy(1 / 1.18));
        fit.addActionListener(e -> graph.fit()); legend.addActionListener(e -> graph.toggleLegend());
        tools.add(schema); tools.add(plus); tools.add(minus); tools.add(fit); tools.add(legend);
        return card("Graph View", tools, graph);
    }

    private JComponent cypherCard() {
        JPanel tools = panel(new FlowLayout(FlowLayout.RIGHT, 5, 0), SURFACE);
        JButton format = button("Format Cypher", false), run = button("Run Query", true), copy = button("Copy", false);
        format.addActionListener(e -> { if (!cypher.getText().isBlank()) cypher.setText(cypher.getText().strip() + "\n"); });
        run.addActionListener(e -> executeQuery());
        copy.setToolTipText("Copy a self-contained query for Neo4j Desktop/Browser");
        copy.addActionListener(e -> copyDesktopCypher());
        tools.add(format); tools.add(run); tools.add(copy);
        JScrollPane editor = scroll(cypher); editor.setRowHeaderView(new LineNumbers(cypher));
        return card("Cypher Editor", tools, editor);
    }

    private JComponent rightColumn() {
        JSplitPane p = split(JSplitPane.VERTICAL_SPLIT, resultCard(), card("Status", null, scroll(statusText)), .66);
        p.setBorder(BorderFactory.createEmptyBorder(7, 5, 7, 5));
        SwingUtilities.invokeLater(() -> p.setDividerLocation(.66));
        return p;
    }

    private JComponent resultCard() {
        styleTable(resultTable); resultTable.setRowSorter(resultSorter);
        JTextField filter = field("");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(filter.getText()); }
            public void removeUpdate(DocumentEvent e) { filter(filter.getText()); }
            public void changedUpdate(DocumentEvent e) { filter(filter.getText()); }
        });
        JPanel filterBar = panel(new BorderLayout(6, 0), SURFACE);
        filterBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        filterBar.add(smallLabel("Filter"), BorderLayout.WEST); filterBar.add(filter, BorderLayout.CENTER);
        JPanel table = panel(new BorderLayout(), SURFACE); table.add(filterBar, BorderLayout.NORTH); table.add(scroll(resultTable));
        JTabbedPane tabs = new JTabbedPane(); tabs.setBackground(SURFACE); tabs.setForeground(TEXT);
        tabs.addTab("Query Results", table); tabs.addTab("Text / JSON", scroll(resultText));
        return card("Result", null, tabs);
    }

    private JComponent footer() {
        JPanel p = panel(new BorderLayout(), SURFACE_2);
        p.setPreferredSize(new Dimension(100, 27));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JPanel state = panel(new FlowLayout(FlowLayout.LEFT, 5, 0), SURFACE_2);
        JLabel dot = new JLabel("●"); dot.setForeground(OK); state.add(dot); state.add(footerStatus);
        p.add(state, BorderLayout.WEST); p.add(clock, BorderLayout.EAST); return p;
    }

    private void toggleConnection() {
        if (queryRunning.get()) {
            status("A query is still running. Wait before changing the connection.");
            return;
        }
        if (driverRef.get() == null) connect(); else disconnect();
    }

    private void connect() {
        String u = uri.getText().trim(), db = database.getText().trim(), usr = user.getText().trim();
        if (u.isEmpty() || db.isEmpty() || usr.isEmpty()) { warn("URI, database and user are required."); return; }
        busy(true); status("Connecting to " + u + " / " + db + " …");
        try {
            Driver driver = GraphDatabase.driver(u, AuthTokens.basic(usr, new String(password.getPassword())));
            try { driver.verifyConnectivity(); try (Session s = driver.session(SessionConfig.forDatabase(db))) { s.run("RETURN 1").consume(); } }
            catch (RuntimeException failure) { driver.close(); throw failure; }
            driverRef.set(new ConnectedDriver(driver, db)); materializedRef.set(null); connect.setText("Disconnect");
            connectionStatus.setText("Connected: " + db); connectionStatus.setForeground(OK);
            load.setEnabled(true);
            execute.setEnabled(false);
            status("Connection established. Load the USE/SOIL snapshot into Neo4j next.");
        } catch (Exception ex) { connectionStatus.setText("Failed to connect"); connectionStatus.setForeground(ERROR_COLOR); status("Connection failed: " + message(ex)); }
        finally { busy(false); }
    }

    private void disconnect() {
        ConnectedDriver c = driverRef.getAndSet(null); if (c != null) c.driver().close();
        materializedRef.set(null);
        connect.setText("Connect"); connectionStatus.setText("Not connected"); connectionStatus.setForeground(ERROR_COLOR);
        load.setEnabled(false);
        execute.setEnabled(false);
        status("Disconnected from Neo4j. Reconnect and load the snapshot before executing Cypher.");
    }

    private void loadFiles() {
        ConnectedDriver connection = driverRef.get();
        if (connection == null) { warn("Connect to Neo4j before loading USE/SOIL files."); return; }
        if (usePath.getText().isBlank() || soilPath.getText().isBlank()) { warn("Both USE and SOIL files are required."); return; }
        if (!queryRunning.compareAndSet(false, true)) { status("Another workflow operation is still running."); return; }

        Path modelPath = Path.of(usePath.getText().trim());
        Path snapshotPath = Path.of(soilPath.getText().trim());
        Path constraintsPath = oclPath.getText().isBlank() ? null : Path.of(oclPath.getText().trim());
        clearLoaded();
        load.setEnabled(false);
        busy(true);
        status("Reading USE/SOIL files and building the canonical graph in the background …");

        new SwingWorker<LoadPreparation, String>() {
            @Override protected LoadPreparation doInBackground() {
                Result<ModelInputBundle> loaded = UseSoilInput.load(modelPath, snapshotPath, constraintsPath);
                if (loaded.isFailure()) throw new WorkflowException("Load failed:\n" + diagnostics(loaded));
                publish("Building the canonical graph …");
                Result<GraphBuilder.GraphBuildArtifact> built = GraphBuilder.build(
                        loaded.value().schema(), loaded.value().snapshot());
                if (built.isFailure()) throw new WorkflowException("Graph build failed:\n" + diagnostics(built));
                return new LoadPreparation(loaded.value(), built.value(), constraintsPath);
            }

            @Override protected void process(List<String> messages) {
                if (!messages.isEmpty()) status(messages.get(messages.size() - 1));
            }

            @Override protected void done() {
                try {
                    LoadPreparation prepared = get();
                    materializeLoadedGraph(connection, prepared);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status("Load interrupted. Neo4j was not modified.");
                    finishWorkflow();
                } catch (ExecutionException failure) {
                    status(message(failure.getCause()));
                    finishWorkflow();
                }
            }
        }.execute();
    }

    private void materializeLoadedGraph(ConnectedDriver connection, LoadPreparation prepared) {
        GraphModel graphModel = prepared.graph().graph();
        status("Materializing " + graphModel.nodes().size() + " nodes and "
                + graphModel.relationships().size()
                + " relationships in Neo4j: schema phase → object snapshot phase …");
        new SwingWorker<GraphLoadOutcome, Void>() {
            @Override protected GraphLoadOutcome doInBackground() {
                long start = System.nanoTime();
                boolean changed;
                try (Session session = connection.driver().session(
                        SessionConfig.forDatabase(connection.database()))) {
                    changed = Neo4jExecutionAdapter.materializeGraph(session, graphModel);
                }
                return new GraphLoadOutcome(elapsedMillis(start), changed);
            }

            @Override protected void done() {
                try {
                    GraphLoadOutcome outcome = get();
                    inputRef.set(prepared);
                    graphRef.set(prepared.graph());
                    materializedRef.set(new MaterializedGraph(connection, graphModel));
                    graph.setGraph(graphModel);
                    populateInvariants(prepared.input().invariants());
                    compile.setEnabled(!prepared.input().invariants().isEmpty());
                    if (!prepared.input().invariants().isEmpty()) {
                        invariantName.setText(prepared.input().invariants().get(0).name());
                    }
                    status((outcome.changed()
                            ? "Graph loaded into Neo4j successfully in "
                            : "Graph is unchanged; reused the existing Neo4j namespace in ")
                            + outcome.elapsedMillis() + " ms."
                            + "\nNamespace: " + graphModel.modelKey()
                            + "\nClasses: " + prepared.input().schema().classes().size()
                            + "   Associations: " + prepared.input().schema().associations().size()
                            + "\nObjects: " + prepared.input().snapshot().objects().size()
                            + "   Links: " + prepared.input().snapshot().links().size()
                            + "\nInvariants: " + prepared.input().invariants().size());
                    footerStatus.setText("Graph loaded in Neo4j — choose an invariant and compile OCL");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status("Neo4j materialization interrupted.");
                } catch (ExecutionException failure) {
                    materializedRef.set(null);
                    status("Neo4j materialization failed: " + message(failure.getCause()));
                } finally {
                    finishWorkflow();
                }
            }
        }.execute();
    }

    private void finishWorkflow() {
        queryRunning.set(false);
        load.setEnabled(driverRef.get() != null);
        busy(false);
    }

    private void compileSelected(boolean showTranslation) {
        if (queryRunning.get()) { status("A query is still running. Wait before compiling another invariant."); return; }
        LoadPreparation prepared = inputRef.get();
        ModelInputBundle input = prepared == null ? null : prepared.input();
        if (input == null) { warn("Load files first."); return; }
        String name = invariantName.getText().trim();
        if (name.isEmpty() || input.invariants().stream().noneMatch(i -> i.name().equals(name))) { warn("Choose an invariant from the loaded list."); return; }
        if (!showTranslation) clearCompilation();
        busy(true); status((showTranslation ? "Translating '" : "Compiling '") + name + "' …");
        try {
            Result<FileCompilation> compiled = ModelInputCompiler.compile(input, name, CypherAst.Dialect.CYPHER_5);
            if (compiled.isFailure()) { status((showTranslation ? "Translation" : "Compilation") + " failed:\n" + diagnostics(compiled)); return; }
            translate.setEnabled(true);
            if (showTranslation) {
                artifactRef.set(compiled.value().cypherAst());
                serializedRef.set(compiled.value().serializedCypher());
                cypher.setText(compiled.value().serializedCypher().cypherText()); cypher.setCaretPosition(0);
                ConnectedDriver connection = driverRef.get();
                GraphBuilder.GraphBuildArtifact built = graphRef.get();
                execute.setEnabled(connection != null && built != null
                        && isMaterialized(connection, built.graph()));
                status("Translated '" + name + "' successfully.\nDialect: Cypher 5\nParameters: " + compiled.value().serializedCypher().parameters());
            } else status("Compiled '" + name + "' successfully. The typed OCL expression is ready for Cypher 5 translation.");
        } catch (Exception ex) { status("Compilation error: " + message(ex)); }
        finally { busy(false); }
    }

    private void executeQuery() {
        Serializer.Serialized serialized = serializedRef.get(); GraphBuilder.GraphBuildArtifact built = graphRef.get(); ConnectedDriver c = driverRef.get();
        LoadPreparation prepared = inputRef.get();
        if (serialized == null || built == null || prepared == null) { warn("Compile and translate OCL first."); return; }
        if (c == null) { warn("Connect to Neo4j first."); return; }
        if (!isMaterialized(c, built.graph())) {
            execute.setEnabled(false);
            warn("The loaded snapshot is not materialized in the current Neo4j connection. Click '1. Load Graph' first.");
            return;
        }
        if (!queryRunning.compareAndSet(false, true)) { status("A query is already running."); return; }

        CypherAst.GeneratedArtifact artifact = artifactRef.get();
        Path modelPath = prepared.input().schemaPath();
        Path snapshotPath = prepared.input().snapshotPath();
        Path constraintsPath = prepared.oclPath();
        String selectedInvariant = invariantName.getText().trim();
        execute.setEnabled(false);
        busy(true);
        status("Starting Neo4j execution in the background …");

        new SwingWorker<QueryOutcome, String>() {
            @Override protected QueryOutcome doInBackground() throws Exception {
                long totalStart = System.nanoTime();
                long materializeMillis = 0;
                boolean reusedGraph = true;
                try (Session session = c.driver().session(SessionConfig.forDatabase(c.database()))) {
                    publish("Executing generated Cypher against the graph loaded in Neo4j …");
                    long queryStart = System.nanoTime();
                    Result<Neo4jExecutionAdapter.ExecutionResult> run =
                            Neo4jExecutionAdapter.execute(session, artifact, Map.of(), built.graph());
                    long queryMillis = elapsedMillis(queryStart);
                    if (run.isFailure()) {
                        return QueryOutcome.failure("Execution failed:\n" + diagnostics(run),
                                materializeMillis, queryMillis, elapsedMillis(totalStart), reusedGraph);
                    }

                    List<String> violations = run.value().violationIds();
                    StringBuilder report = new StringBuilder("Query executed successfully.\n\nViolations (")
                            .append(violations.size()).append("):\n");
                    if (violations.isEmpty()) report.append("  (no violating objects)\n");
                    else violations.forEach(id -> report.append("  - ").append(id).append('\n'));

                    long useMillis = 0;
                    publish("Comparing the result with USE …");
                    try {
                        UseEvaluationAdapter.Result useResult = useEvaluationRef.get();
                        if (useResult == null) {
                            long useStart = System.nanoTime();
                            useResult = constraintsPath == null
                                    ? UseEvaluationAdapter.evaluate(modelPath, snapshotPath)
                                    : UseEvaluationAdapter.evaluate(modelPath, constraintsPath, snapshotPath);
                            useMillis = elapsedMillis(useStart);
                            useEvaluationRef.compareAndSet(null, useResult);
                        }
                        Set<String> neo = new TreeSet<>(violations);
                        Set<String> useIds = new TreeSet<>(useResult.violationsFor(selectedInvariant));
                        boolean equal = neo.equals(useIds);
                        report.append("\nUSE ↔ Neo4j comparison: ").append(equal ? "PASS" : "MISMATCH").append('\n')
                                .append("USE violations: ").append(useIds).append('\n')
                                .append("Neo4j violations: ").append(neo).append('\n');
                        if (!equal) report.append("Only in USE: ").append(difference(useIds, neo)).append('\n')
                                .append("Only in Neo4j: ").append(difference(neo, useIds)).append('\n');
                    } catch (Exception comparisonFailure) {
                        report.append("\nUSE ↔ Neo4j comparison: UNAVAILABLE\n")
                                .append(message(comparisonFailure)).append('\n');
                    }
                    long totalMillis = elapsedMillis(totalStart);
                    report.append("\nTiming:\n")
                            .append("  Graph materialization: completed during Load Graph\n")
                            .append("  Cypher execution: ").append(queryMillis).append(" ms\n")
                            .append("  USE evaluation: ")
                            .append(useMillis == 0 ? "reused/unavailable" : useMillis + " ms").append('\n')
                            .append("  Total: ").append(totalMillis).append(" ms\n")
                            .append("\nCypher:\n").append(serialized.cypherText()).append('\n');
                    return QueryOutcome.success(violations, report.toString(), materializeMillis,
                            queryMillis, useMillis, totalMillis, reusedGraph);
                }
            }

            @Override protected void process(List<String> messages) {
                if (!messages.isEmpty()) status(messages.get(messages.size() - 1));
            }

            @Override protected void done() {
                try {
                    QueryOutcome outcome = get();
                    resultText.setText(outcome.report());
                    resultText.setCaretPosition(0);
                    populateResults(outcome.violations());
                    if (outcome.success()) {
                        status("Execution complete in " + outcome.totalMillis() + " ms. Violations: "
                                + outcome.violations().size() + " (queried preloaded graph)");
                    } else {
                        status("Execution failed after " + outcome.totalMillis()
                                + " ms. See Result / Text.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    status("Execution interrupted.");
                } catch (ExecutionException failure) {
                    resultText.setText("Execution error: " + message(failure.getCause()));
                    status("Execution error: " + message(failure.getCause()));
                } finally {
                    queryRunning.set(false);
                    ConnectedDriver currentConnection = driverRef.get();
                    GraphBuilder.GraphBuildArtifact currentGraph = graphRef.get();
                    execute.setEnabled(currentConnection != null && currentGraph != null
                            && serializedRef.get() != null
                            && isMaterialized(currentConnection, currentGraph.graph()));
                    busy(false);
                }
            }
        }.execute();
    }

    private void populateInvariants(List<SourceInvariant> invariants) {
        invariantRows.removeAll(); GridBagConstraints g = new GridBagConstraints(); g.gridx = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        int row = 0; for (SourceInvariant invariant : invariants) { g.gridy = row++; invariantRows.add(invariantRow(invariant.name(), row == 1), g); }
        g.gridy = row; g.weighty = 1; g.fill = GridBagConstraints.BOTH; invariantRows.add(Box.createGlue(), g);
        invariantRows.revalidate(); invariantRows.repaint();
    }

    private JPanel invariantRow(String name, boolean checked) {
        JPanel row = panel(new GridBagLayout(), invariantRows.getComponentCount() % 2 == 0 ? SURFACE_2 : SURFACE);
        GridBagConstraints g = new GridBagConstraints(); g.gridy = 0; g.insets = new Insets(3, 2, 3, 2);
        JCheckBox check = new JCheckBox(); check.setSelected(checked); check.setBackground(row.getBackground());
        check.addActionListener(e -> { if (check.isSelected()) selectInvariant(check, name); }); g.gridx = 0; row.add(check, g);
        JLabel title = new JLabel(name); title.setForeground(TEXT); g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; row.add(title, g);
        JButton c = tiny("Compile", true); c.addActionListener(e -> { check.setSelected(true); selectInvariant(check, name); compileSelected(false); });
        g.gridx = 2; g.weightx = 0; g.fill = GridBagConstraints.NONE; row.add(c, g);
        JButton remove = tiny("Remove", false); remove.addActionListener(e -> { invariantRows.remove(row); invariantRows.revalidate(); invariantRows.repaint(); });
        g.gridx = 3; row.add(remove, g); return row;
    }

    private void selectInvariant(JCheckBox selected, String name) {
        for (Component component : invariantRows.getComponents()) { JCheckBox box = checkbox(component); if (box != null && box != selected) box.setSelected(false); }
        if (!name.isEmpty()) invariantName.setText(name); clearCompilation();
    }

    private void addInvariantByName() {
        String name = invariantName.getText().trim();
        LoadPreparation prepared = inputRef.get();
        ModelInputBundle input = prepared == null ? null : prepared.input();
        if (input == null || input.invariants().stream().noneMatch(i -> i.name().equals(name))) { warn("The invariant name is not present in the loaded USE model."); return; }
        GridBagConstraints g = new GridBagConstraints(); g.gridx = 0; g.gridy = Math.max(0, invariantRows.getComponentCount() - 1); g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        invariantRows.add(invariantRow(name, true), g, g.gridy); invariantRows.revalidate(); invariantRows.repaint();
    }

    private static JCheckBox checkbox(Component c) {
        if (c instanceof JCheckBox box) return box;
        if (c instanceof Container container) for (Component child : container.getComponents()) { JCheckBox found = checkbox(child); if (found != null) return found; }
        return null;
    }

    private void populateResults(List<String> ids) { resultModel.setRowCount(0); for (int i = 0; i < ids.size(); i++) resultModel.addRow(new Object[]{i + 1, ids.get(i), "Violation"}); }
    private void filter(String value) { try { resultSorter.setRowFilter(value.isBlank() ? null : RowFilter.regexFilter("(?i)" + value)); } catch (java.util.regex.PatternSyntaxException ignored) { } }
    private void clearCompilation() { artifactRef.set(null); serializedRef.set(null); translate.setEnabled(false); execute.setEnabled(false); cypher.setText(""); resultText.setText(""); resultModel.setRowCount(0); }
    private void clearLoaded() { inputRef.set(null); graphRef.set(null); materializedRef.set(null); useEvaluationRef.set(null); compile.setEnabled(false); clearCompilation(); invariantRows.removeAll(); graph.setGraph(null); }
    private void status(String value) { statusText.setText(value); statusText.setCaretPosition(statusText.getDocument().getLength()); }
    private void busy(boolean yes) { setCursor(Cursor.getPredefinedCursor(yes ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR)); }
    private String diagnostics(Result<?> r) { return r.diagnostics().stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse("Unknown error"); }
    private static long elapsedMillis(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000L; }

    private boolean isMaterialized(ConnectedDriver connection, GraphModel graphModel) {
        MaterializedGraph current = materializedRef.get();
        return current != null && current.connection() == connection && current.graph() == graphModel;
    }

    private String desktopCypher() {
        Serializer.Serialized serialized = serializedRef.get();
        CypherAst.GeneratedArtifact artifact = artifactRef.get();
        GraphBuilder.GraphBuildArtifact built = graphRef.get();
        if (serialized == null || artifact == null || built == null) return cypher.getText();
        return Neo4jBrowserScript.selfContained(serialized, artifact, built.graph());
    }

    private void copyDesktopCypher() {
        try {
            String query = desktopCypher();
            if (query.isBlank()) { warn("There is no generated Cypher to copy."); return; }
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(query), null);
            status("Copied a self-contained Cypher query. Generated parameters were inlined for Neo4j Desktop/Browser.");
        } catch (IllegalArgumentException failure) {
            warn("Cannot create a standalone query: " + message(failure));
        }
    }

    private void exportDesktopCypher() {
        try {
            export(desktopCypher(), "query.cypher", "cypher");
        } catch (IllegalArgumentException failure) {
            warn("Cannot export a standalone query: " + message(failure));
        }
    }

    private void export(String value, String name, String extension) {
        if (value.isBlank()) { warn("There is no content to export."); return; }
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(name)); chooser.setFileFilter(new FileNameExtensionFilter(extension + " files", extension));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) try { Files.writeString(chooser.getSelectedFile().toPath(), value); status("Exported " + chooser.getSelectedFile()); }
        catch (IOException ex) { JOptionPane.showMessageDialog(this, "Export failed: " + message(ex), "OCL2Cypher", JOptionPane.ERROR_MESSAGE); }
    }

    private JButton browse(JTextField target, String extension) {
        JButton b = button("Browse", false); b.addActionListener(e -> { JFileChooser chooser = new JFileChooser(); chooser.setAcceptAllFileFilterUsed(false); chooser.setFileFilter(new FileNameExtensionFilter(extension + " files", extension)); if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) target.setText(chooser.getSelectedFile().getAbsolutePath()); }); return b;
    }

    private static String message(Throwable t) { return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage(); }
    private void warn(String text) { JOptionPane.showMessageDialog(this, text, "OCL2Cypher", JOptionPane.WARNING_MESSAGE); }
    private static Set<String> difference(Set<String> left, Set<String> right) { Set<String> out = new TreeSet<>(left); out.removeAll(right); return out; }

    @Override public void dispose() { ConnectedDriver c = driverRef.getAndSet(null); if (c != null) c.driver().close(); super.dispose(); }
    private record LoadPreparation(ModelInputBundle input,
                                   GraphBuilder.GraphBuildArtifact graph,
                                   Path oclPath) { }
    private record ConnectedDriver(Driver driver, String database) { private ConnectedDriver { Objects.requireNonNull(driver); Objects.requireNonNull(database); } }
    private record MaterializedGraph(ConnectedDriver connection, GraphModel graph) { }
    private record GraphLoadOutcome(long elapsedMillis, boolean changed) { }
    private static final class WorkflowException extends RuntimeException {
        private WorkflowException(String message) { super(message); }
    }
    private record QueryOutcome(boolean success, List<String> violations, String report,
                                long materializeMillis, long queryMillis, long useMillis,
                                long totalMillis, boolean reusedGraph) {
        private static QueryOutcome success(List<String> violations, String report,
                                            long materializeMillis, long queryMillis,
                                            long useMillis, long totalMillis,
                                            boolean reusedGraph) {
            return new QueryOutcome(true, List.copyOf(violations), report, materializeMillis,
                    queryMillis, useMillis, totalMillis, reusedGraph);
        }

        private static QueryOutcome failure(String report, long materializeMillis,
                                            long queryMillis, long totalMillis,
                                            boolean reusedGraph) {
            return new QueryOutcome(false, List.of(), report, materializeMillis,
                    queryMillis, 0, totalMillis, reusedGraph);
        }
    }

}
