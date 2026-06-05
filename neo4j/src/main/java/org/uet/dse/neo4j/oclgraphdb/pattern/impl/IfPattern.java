package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTIf;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class IfPattern implements OclPattern {
  @Override
  public String name() {
    return "if";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIf;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTIf ifNode = (ASTIf) node;
    Optional<PlanCandidate> condition = planner.plan(ifNode.condition, context, ExpectedKind.BOOLEAN);
    Optional<PlanCandidate> thenBranch = planner.plan(ifNode.thenExpression, context, expected == ExpectedKind.ANY ? ExpectedKind.ANY : expected);
    Optional<PlanCandidate> elseBranch = planner.plan(ifNode.elseExpression, context, expected == ExpectedKind.ANY ? ExpectedKind.ANY : expected);
    if (condition.isEmpty() || thenBranch.isEmpty() || elseBranch.isEmpty()) return List.of();

    ExpressionKind kind = compatibleKind(thenBranch.get().expression().getKind(), elseBranch.get().expression().getKind());
    if (kind == null) return List.of();

    String expression = "CASE WHEN " + condition.get().expression().getExpression()
        + " THEN " + thenBranch.get().expression().getExpression()
        + " ELSE " + elseBranch.get().expression().getExpression()
        + " END";
    CypherExpression cypher = CypherExpression.combine(
        expression,
        kind,
        condition.get().expression(),
        thenBranch.get().expression(),
        elseBranch.get().expression()
    );
    return List.of(PlanCandidate.exact(name(), cypher, 920));
  }

  private ExpressionKind compatibleKind(ExpressionKind left, ExpressionKind right) {
    if (left == right) return left;
    if (left == ExpressionKind.VALUE && right == ExpressionKind.BOOLEAN) return ExpressionKind.VALUE;
    if (left == ExpressionKind.BOOLEAN && right == ExpressionKind.VALUE) return ExpressionKind.VALUE;
    return null;
  }
}
