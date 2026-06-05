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
import org.uet.dse.neo4j.oclite.ast.ASTRange;

public class RangePattern implements OclPattern {
  @Override
  public String name() {
    return "range";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTRange;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTRange range = (ASTRange) node;
    Optional<PlanCandidate> start = planner.plan(range.start, context, ExpectedKind.VALUE);
    Optional<PlanCandidate> end = planner.plan(range.end, context, ExpectedKind.VALUE);
    if (start.isEmpty() || end.isEmpty()) return List.of();

    String expression = "range(toInteger(" + CypherSupport.numeric(start.get().expression().getExpression()) + "), toInteger("
        + CypherSupport.numeric(end.get().expression().getExpression()) + "))";
    CypherExpression cypher = CypherExpression.combine(expression, ExpressionKind.COLLECTION, start.get().expression(), end.get().expression());
    return List.of(PlanCandidate.exact(name(), cypher, 940));
  }
}
