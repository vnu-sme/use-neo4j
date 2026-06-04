package org.uet.dse.neo4j.gui;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.neo4j.driver.types.Node;
import org.tzi.use.main.Session;
import org.uet.dse.neo4j.OCLLexer;
import org.uet.dse.neo4j.OCLParser;
import org.uet.dse.neo4j.oclgraphdb.OclGraphDbEvaluationOptions;
import org.uet.dse.neo4j.oclgraphdb.GraphDbEvaluationResult;
import org.uet.dse.neo4j.oclgraphdb.OclGraphDbEvaluator;
import org.uet.dse.neo4j.oclgraphdb.analysis.OclAnalysisResult;
import org.uet.dse.neo4j.oclgraphdb.analysis.OclAnalysisService;
import org.uet.dse.neo4j.oclgraphdb.analysis.OclAstAnalysisNode;
import org.uet.dse.neo4j.oclgraphdb.analysis.OclPatternCandidateAnalysis;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclite.Neo4jRepository;
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTVisitor;
import org.uet.dse.neo4j.oclite.expr.ExecutionContext;
import org.uet.dse.neo4j.oclite.expr.ExpressionBinder;
import org.uet.dse.neo4j.oclite.expr.ExpressionNode;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.List;
import java.util.Optional;

public class OclEvaluatorDialog extends JDialog {
  private final JTextArea txtExpression;
  private final JTextArea txtResult;
  private final JTextArea txtAnalysis;
  private final JTextArea txtMermaid;
  private final JTextField txtSelfObject;
  private final JTextField txtPatternPasses;
  private final JTree treeAnalysis;
  private final JTabbedPane outputTabs;
  private final Session useSession;
  private final OclGraphDbEvaluator graphDbEvaluator = new OclGraphDbEvaluator();
  private final OclAnalysisService analysisService = new OclAnalysisService();

  public OclEvaluatorDialog(Frame parent, Session session) {
    super(parent, "Evaluate OCL expression", false);
    this.useSession = session;

    setSize(1000, 720);
    setMinimumSize(new Dimension(780, 520));
    setLayout(new BorderLayout(10, 10));
    ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

    JPanel pnlInput = new JPanel(new BorderLayout(5, 5));
    pnlInput.add(new JLabel("Enter OCL expression:"), BorderLayout.NORTH);
    txtExpression = new JTextArea();
    txtExpression.setLineWrap(true);
    pnlInput.add(new JScrollPane(txtExpression), BorderLayout.CENTER);
    JPanel pnlOptions = new JPanel(new GridLayout(2, 1, 5, 4));
    JPanel pnlSelf = new JPanel(new BorderLayout(5, 5));
    pnlSelf.add(new JLabel("self object id (optional):"), BorderLayout.WEST);
    txtSelfObject = new JTextField();
    pnlSelf.add(txtSelfObject, BorderLayout.CENTER);
    JPanel pnlPasses = new JPanel(new BorderLayout(5, 5));
    pnlPasses.add(new JLabel("pattern passes (0 = auto):"), BorderLayout.WEST);
    txtPatternPasses = new JTextField("0");
    pnlPasses.add(txtPatternPasses, BorderLayout.CENTER);
    pnlOptions.add(pnlSelf);
    pnlOptions.add(pnlPasses);
    pnlInput.add(pnlOptions, BorderLayout.SOUTH);

    txtResult = new JTextArea();
    txtResult.setEditable(false);
    txtResult.setBackground(new Color(245, 245, 245));

    txtAnalysis = new JTextArea();
    txtAnalysis.setEditable(false);
    txtAnalysis.setBackground(new Color(245, 245, 245));
    txtAnalysis.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    txtMermaid = new JTextArea();
    txtMermaid.setEditable(false);
    txtMermaid.setBackground(new Color(245, 245, 245));
    txtMermaid.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

    treeAnalysis = new JTree(new DefaultMutableTreeNode("No analysis yet"));
    treeAnalysis.setRootVisible(true);

    JSplitPane analysisSplit = new JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        new JScrollPane(treeAnalysis),
        new JScrollPane(txtAnalysis)
    );
    analysisSplit.setResizeWeight(0.45);

    outputTabs = new JTabbedPane();
    outputTabs.addTab("Result", new JScrollPane(txtResult));
    outputTabs.addTab("Analysis", analysisSplit);
    outputTabs.addTab("Mermaid", new JScrollPane(txtMermaid));

    JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pnlInput, outputTabs);
    mainSplit.setResizeWeight(0.34);

    JPanel pnlRight = new JPanel();
    pnlRight.setLayout(new BoxLayout(pnlRight, BoxLayout.Y_AXIS));

    JButton btnEvaluate = createStyledButton("Evaluate", new Color(0, 120, 215));
    JButton btnAnalyze = createStyledButton("Analyze", Color.WHITE);
    JButton btnBrowser = createStyledButton("Browser", Color.WHITE);
    JButton btnClear = createStyledButton("Clear", Color.WHITE);
    JButton btnClose = createStyledButton("Close", Color.WHITE);

    pnlRight.add(btnEvaluate);
    pnlRight.add(Box.createVerticalStrut(10));
    pnlRight.add(btnAnalyze);
    pnlRight.add(Box.createVerticalStrut(10));
    pnlRight.add(btnBrowser);
    pnlRight.add(Box.createVerticalStrut(10));
    pnlRight.add(btnClear);
    pnlRight.add(Box.createVerticalStrut(10));
    pnlRight.add(btnClose);

    btnEvaluate.addActionListener(e -> doEvaluate());
    btnAnalyze.addActionListener(e -> doAnalyze());
    btnClear.addActionListener(e -> {
      txtExpression.setText("");
      txtSelfObject.setText("");
      txtPatternPasses.setText("0");
      txtResult.setText("");
      txtAnalysis.setText("");
      txtMermaid.setText("");
      treeAnalysis.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("No analysis yet")));
    });
    btnClose.addActionListener(e -> dispose());
    btnBrowser.addActionListener(e -> {
      JOptionPane.showMessageDialog(this, "Endpoint Browser feature is under development.");
    });

    add(mainSplit, BorderLayout.CENTER);
    add(pnlRight, BorderLayout.EAST);

    setLocationRelativeTo(parent);
  }

  private void doEvaluate() {
    String expression = txtExpression.getText().trim();
    if (expression.isEmpty())
      return;

    ASTNode astTree = parseAst(expression);
    String modelName = currentModelName();
    String selfObjectId = txtSelfObject.getText().trim();
    OclGraphDbEvaluationOptions graphOptions = graphOptionsFromUi();

    Object result;
    boolean isValidation = astTree instanceof ASTContext;
    Optional<GraphDbEvaluationResult> graphDbResult = graphDbEvaluator.tryEvaluate(astTree, modelName, graphOptions);
    if (graphDbResult.isPresent()) {
      result = graphDbResult.get().getValue();
    } else {
      ExpressionBinder binder = new ExpressionBinder(modelName);
      if (astTree instanceof ASTContext) {

        result = handleValidation((ASTContext) astTree, modelName, binder);
      } else {

        result = handleNormalEvaluation(astTree, modelName, binder, selfObjectId);
      }
    }
    //Object result = execTree.evaluate(context);

    System.out.println("--- 3. Kết quả thực thi OCL ---");
    System.out.println("Result: " + result);

    if (result != null) {
      String resStr = result.toString();
      txtResult.setText(resStr);
      outputTabs.setSelectedIndex(0);

      if (isValidation && resStr.startsWith("FAILURE")) {
        txtResult.setForeground(Color.RED);
      } else {
        txtResult.setForeground(new Color(0, 100, 0));
      }
    } else {
      txtResult.setText("Could not evaluate");
      txtResult.setForeground(Color.RED);
      outputTabs.setSelectedIndex(0);
    }
  }

  private void doAnalyze() {
    String expression = txtExpression.getText().trim();
    if (expression.isEmpty()) return;

    try {
      ASTNode astTree = parseAst(expression);
      OclAnalysisResult analysis = analysisService.analyze(
          astTree,
          currentModelName(),
          graphOptionsFromUi()
      );
      txtAnalysis.setText(analysis.toTextReport());
      txtMermaid.setText(analysis.toMermaidDiagram());
      treeAnalysis.setModel(new DefaultTreeModel(toSwingTree(analysis.getRoot())));
      expandAll(treeAnalysis);
      outputTabs.setSelectedIndex(1);
    } catch (RuntimeException ex) {
      txtAnalysis.setText("Analyze failed:\n" + ex.getMessage());
      txtMermaid.setText("");
      treeAnalysis.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Analyze failed")));
      outputTabs.setSelectedIndex(1);
    }
  }

  private ASTNode parseAst(String expression) {
    CharStream stream = CharStreams.fromString(expression);
    OCLLexer lexer = new OCLLexer(stream);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    OCLParser parser = new OCLParser(tokens);
    OCLParser.OclFileContext parseTree = parser.oclFile();

    ASTVisitor astBuilder = new ASTVisitor();
    ASTNode astTree = astBuilder.visit(parseTree);
    if (astTree == null) {
      throw new RuntimeException("Cannot parse OCL expression into AST.");
    }
    return astTree;
  }

  private String currentModelName() {
    String modelName = this.useSession.system().model().name();
    if (modelName == null) {
      throw new RuntimeException("Dont know which model is being worked on");
    }
    return modelName;
  }

  private OclGraphDbEvaluationOptions graphOptionsFromUi() {
    String selfObjectId = txtSelfObject.getText().trim();
    int patternPasses = patternPassesFromUi();
    OclGraphDbEvaluationOptions.Builder builder = OclGraphDbEvaluationOptions.builder()
        .maxPatternPasses(patternPasses)
        .autoPatternPasses(patternPasses == 0);
    if (!selfObjectId.isEmpty()) {
      builder.bindObject("self", selfObjectId);
    }
    return builder.build();
  }

  private int patternPassesFromUi() {
    String text = txtPatternPasses.getText().trim();
    if (text.isEmpty()) return 0;
    try {
      int value = Integer.parseInt(text);
      return Math.max(0, value);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private DefaultMutableTreeNode toSwingTree(OclAstAnalysisNode node) {
    DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node.toTreeLabel());

    if (!node.getMultiPassPatterns().isEmpty()) {
      DefaultMutableTreeNode multiPassRoot = new DefaultMutableTreeNode("multi-pass patterns (" + node.getMultiPassPatterns().size() + ")");
      for (PatternInstance pattern : node.getMultiPassPatterns()) {
        multiPassRoot.add(new DefaultMutableTreeNode(pattern.toSummaryLine()));
      }
      treeNode.add(multiPassRoot);
    }

    if (!node.getCandidates().isEmpty()) {
      DefaultMutableTreeNode patternRoot = new DefaultMutableTreeNode("legacy candidates (" + node.getCandidates().size() + ")");
      for (OclPatternCandidateAnalysis candidate : node.getCandidates()) {
        patternRoot.add(new DefaultMutableTreeNode(candidate.toSummaryLine()));
      }
      treeNode.add(patternRoot);
    }

    if (!node.getNotes().isEmpty()) {
      DefaultMutableTreeNode noteRoot = new DefaultMutableTreeNode("notes (" + node.getNotes().size() + ")");
      for (String note : node.getNotes()) {
        noteRoot.add(new DefaultMutableTreeNode(note));
      }
      treeNode.add(noteRoot);
    }

    for (OclAstAnalysisNode child : node.getChildren()) {
      treeNode.add(toSwingTree(child));
    }
    return treeNode;
  }

  private void expandAll(JTree tree) {
    for (int i = 0; i < tree.getRowCount(); i++) {
      tree.expandRow(i);
    }
  }

  private Object handleNormalEvaluation(ASTNode astTree, String modelName, ExpressionBinder binder, String selfObjectId) {
    ExpressionNode execTree = binder.bind(astTree);
    ExecutionContext context = new ExecutionContext(modelName);
    bindSelfIfProvided(context, modelName, selfObjectId);
    return execTree.evaluate(context);
  }

  private void bindSelfIfProvided(ExecutionContext context, String modelName, String selfObjectId) {
    if (selfObjectId == null || selfObjectId.isBlank()) return;

    Neo4jRepository repo = new Neo4jRepository(modelName);
    Node self = repo.findNodeById(selfObjectId.trim());
    if (self != null) {
      context.setVariable("self", self);
    }
  }

  private Object handleValidation(ASTContext astContext, String modelName, ExpressionBinder binder) {
    ExpressionNode constraintExecTree = binder.bind(astContext.expression);
    Neo4jRepository repo = new Neo4jRepository(modelName);

    List<Node> instances = repo.findAllInstancesOfClass(astContext.className);

    int total = instances.size();
    int failed = 0;
    StringBuilder report = new StringBuilder();
    report.append("--- Invariant: ").append(astContext.invName).append(" ---\n");

    if (total == 0) {
      return "No instances of class '" + astContext.className + "' found to validate.";
    }

    for (Node node : instances) {
      ExecutionContext ctx = new ExecutionContext(modelName);
      ctx.pushScope("self", node);
      ctx.setVariable("self", node);

      Object isOk = constraintExecTree.evaluate(ctx);

      if (isOk instanceof Boolean && !((Boolean) isOk)) {
        failed++;
        String objId = node.get("use_id").asString();
        report.append("  [!] Violation: ").append(objId).append("\n");
      }
    }

    if (failed == 0) {
      return "SUCCESS: All " + total + " instances of '" + astContext.className + "' satisfy the invariant.";
    } else {
      return "FAILURE: " + failed + "/" + total + " violations found.\n" + report.toString();
    }
  }

  private JButton createStyledButton(String text, Color bgColor) {
    JButton btn = new JButton(text);
    btn.setMaximumSize(new Dimension(120, 35));
    btn.setPreferredSize(new Dimension(120, 35));
    btn.setAlignmentX(Component.CENTER_ALIGNMENT);
    if (bgColor != Color.WHITE) {
      btn.setBackground(bgColor);
      btn.setForeground(Color.WHITE);
      btn.setOpaque(true);
      btn.setBorderPainted(false);
    }
    return btn;
  }
}
