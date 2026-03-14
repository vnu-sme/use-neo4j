package org.uet.dse.neo4j.gui;

import org.tzi.use.gui.util.TextComponentWriter;
import org.tzi.use.main.Session;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MSystemState;
import org.tzi.use.util.TeeWriter;
import org.uet.dse.neo4j.ocl.IOCLEvaluator;
import org.uet.dse.neo4j.ocl.NEvaluator;
import org.uet.dse.neo4j.ocl.mapper.NExpressionRewriter;
import org.uet.dse.neo4j.ocl.expr.NExpression;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;

public class OclEvaluatorDialog extends JDialog {
    private final JTextArea txtExpression;
    private final JTextArea txtResult;
    private final Session useSession;
    private final IOCLEvaluator oclEvaluator;
    private final MSystemState currentState;


    public OclEvaluatorDialog(Frame parent, Session session, IOCLEvaluator oclEvaluator) {
        super(parent, "Evaluate OCL expression", false);
        this.useSession = session;
      this.oclEvaluator = oclEvaluator;
      this.currentState = session.system().state();

      setSize(600, 300);
        setLayout(new BorderLayout(10, 10));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel pnlLeft = new JPanel(new GridLayout(2, 1, 5, 10));

        JPanel pnlInput = new JPanel(new BorderLayout(5, 5));
        pnlInput.add(new JLabel("Enter OCL expression:"), BorderLayout.NORTH);
        txtExpression = new JTextArea();
        txtExpression.setLineWrap(true);
        pnlInput.add(new JScrollPane(txtExpression), BorderLayout.CENTER);

        JPanel pnlOutput = new JPanel(new BorderLayout(5, 5));
        pnlOutput.add(new JLabel("Result:"), BorderLayout.NORTH);
        txtResult = new JTextArea();
        txtResult.setEditable(false);
        txtResult.setBackground(new Color(245, 245, 245));
        pnlOutput.add(new JScrollPane(txtResult), BorderLayout.CENTER);

        pnlLeft.add(pnlInput);
        pnlLeft.add(pnlOutput);

        JPanel pnlRight = new JPanel();
        pnlRight.setLayout(new BoxLayout(pnlRight, BoxLayout.Y_AXIS));

        JButton btnEvaluate = createStyledButton("Evaluate", new Color(0, 120, 215));
        JButton btnBrowser = createStyledButton("Browser", Color.WHITE);
        JButton btnClear = createStyledButton("Clear", Color.WHITE);
        JButton btnClose = createStyledButton("Close", Color.WHITE);

        pnlRight.add(btnEvaluate);
        pnlRight.add(Box.createVerticalStrut(10));
        pnlRight.add(btnBrowser);
        pnlRight.add(Box.createVerticalStrut(10));
        pnlRight.add(btnClear);
        pnlRight.add(Box.createVerticalStrut(10));
        pnlRight.add(btnClose);

        btnEvaluate.addActionListener(e -> doEvaluate());
        btnClear.addActionListener(e -> {
            txtExpression.setText("");
            txtResult.setText("");
        });
        btnClose.addActionListener(e -> dispose());
        btnBrowser.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Endpoint Browser feature is under development.");
        });

        add(pnlLeft, BorderLayout.CENTER);
        add(pnlRight, BorderLayout.EAST);

        setLocationRelativeTo(parent);
    }

    private void doEvaluate() {
        String expression = txtExpression.getText().trim();
        if (expression.isEmpty()) return;

        MModel model = useSession.system().model();

      StringWriter msgWriter = new StringWriter();
      PrintWriter out = new PrintWriter(new TeeWriter(
          new TextComponentWriter(txtResult), msgWriter), true);

      //core version
      Expression expr = OCLCompiler.compileExpression(
          model,
          useSession.system().state(),
          expression,
          "Neo4jPlugin",
          out,
          useSession.system().varBindings()
      );

      if (expr == null) {
        System.out.println("Error parsing ocl to expr");
      }

      // Rewrite tree -> neo4j version
      NExpression neo4jExpr = NExpressionRewriter.rewrite(expr);
      Value result = null;
        NEvaluator evaluator = new NEvaluator();
        // eval như bình thường - nhưng chạy trên neo4j
        result = evaluator.eval(currentState, neo4jExpr);


        if (result != null) {
            txtResult.setText(result.toStringWithType());
            txtResult.setForeground(new Color(0, 100, 0));
        } else {
            txtResult.setText("Could not evaluate");
            txtResult.setForeground(Color.RED);
        }
    }
//    private void doEvaluate() {
//        String expression = txtExpression.getText().trim();
//        if (expression.isEmpty()) return;
//
//        try {
//            UseSystemApi api = UseSystemApi.create(useSession);
//            Value resultValue = api.evaluate(expression);
//
//            if (resultValue != null) {
//                txtResult.setText(resultValue.toString() + " : " + resultValue.type().toString());
//                txtResult.setForeground(new Color(0, 100, 0)); // Màu xanh lá đậm
//            }
//        } catch (Exception e) {
//            txtResult.setText("Error: " + e.getMessage());
//            txtResult.setForeground(Color.RED);
//        }
//    }

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