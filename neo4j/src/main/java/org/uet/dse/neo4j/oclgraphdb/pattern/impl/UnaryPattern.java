package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTUnary;

public class UnaryPattern implements OclPattern {
  @Override
  public String name() {
    return "unary";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTUnary;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTUnary unary = (ASTUnary) node;
    ExpectedKind childExpected = "not".equals(unary.operator) ? ExpectedKind.BOOLEAN : ExpectedKind.VALUE;
    Optional<PlanCandidate> child = planner.plan(unary.expression, context, childExpected);
    if (child.isEmpty()) return List.of();

    String expression = switch (unary.operator) {
      case "not" -> "NOT (" + child.get().expression().getExpression() + ")";
      case "+" -> CypherSupport.numeric(child.get().expression().getExpression());
      case "-" -> "-" + CypherSupport.numeric(child.get().expression().getExpression());
      default -> null;
    };
    if (expression == null) return List.of();

    ExpressionKind kind = "not".equals(unary.operator) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
    return List.of(PlanCandidate.exact(name(), CypherExpression.combine(expression, kind, child.get().expression()), 930));
  }
}
