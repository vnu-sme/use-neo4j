package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;

public class ComparePatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.COMPARE
        || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
        || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTBinary binary)) return Optional.empty();
    if (pattern.getChildren().size() < 2) return Optional.empty();

    Optional<CypherExpression> left = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.ANY);
    Optional<CypherExpression> right = compiler.compileExpression(pattern.getChildren().get(1), context, ExpectedKind.ANY);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();

    String expression = compare(left.get(), right.get(), binary.op);
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, left.get(), right.get()));
  }

  private String compare(CypherExpression left, CypherExpression right, String op) {
    if (">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op)) {
      return CypherSupport.numeric(left.getExpression()) + " " + op + " " + CypherSupport.numeric(right.getExpression());
    }
    if (left.getKind() == ExpressionKind.NODE || right.getKind() == ExpressionKind.NODE) {
      return left.getExpression() + " " + op + " " + right.getExpression();
    }
    return CypherSupport.normalizeString(left.getExpression())
        + " " + op + " "
        + CypherSupport.normalizeString(right.getExpression());
  }
}
