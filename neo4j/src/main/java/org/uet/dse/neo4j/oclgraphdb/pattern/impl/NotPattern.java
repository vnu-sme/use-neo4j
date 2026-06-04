package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTNot;

public class NotPattern implements OclPattern {
  @Override
  public String name() {
    return "not";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTNot;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTNot not = (ASTNot) node;
    return planner.plan(not.expression, context, ExpectedKind.BOOLEAN)
        .map(inner -> {
          CypherExpression expr = CypherExpression.combine("NOT (" + inner.expression().getExpression() + ")", ExpressionKind.BOOLEAN, inner.expression());
          return List.of(PlanCandidate.exact(name(), expr, 950));
        })
        .orElse(List.of());
  }
}
