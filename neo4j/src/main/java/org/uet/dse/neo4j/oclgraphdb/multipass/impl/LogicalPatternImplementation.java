package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;

public class LogicalPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTBinary binary)) return Optional.empty();
    if (pattern.getChildren().size() < 2) return Optional.empty();
    Optional<CypherExpression> left = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.BOOLEAN);
    Optional<CypherExpression> right = compiler.compileExpression(pattern.getChildren().get(1), context, ExpectedKind.BOOLEAN);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();

    String expression = switch (binary.op) {
      case "and" -> "(" + left.get().getExpression() + " AND " + right.get().getExpression() + ")";
      case "or" -> "(" + left.get().getExpression() + " OR " + right.get().getExpression() + ")";
      case "implies" -> "((NOT (" + left.get().getExpression() + ")) OR " + right.get().getExpression() + ")";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, left.get(), right.get()));
  }
}
