package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.*;

public class LiteralPattern implements OclPattern {
  @Override
  public String name() {
    return "literal";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIntegerLiteral
        || node instanceof ASTRealLiteral
        || node instanceof ASTStringLiteral
        || node instanceof ASTBooleanLiteral
        || node instanceof ASTNullLiteral;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    Object value;
    ExpressionKind kind = ExpressionKind.VALUE;
    if (node instanceof ASTIntegerLiteral) value = ((ASTIntegerLiteral) node).value;
    else if (node instanceof ASTRealLiteral) value = ((ASTRealLiteral) node).value;
    else if (node instanceof ASTStringLiteral) value = ((ASTStringLiteral) node).value;
    else if (node instanceof ASTBooleanLiteral) {
      value = ((ASTBooleanLiteral) node).value;
      kind = ExpressionKind.BOOLEAN;
    } else value = null;

    return List.of(PlanCandidate.exact(name(), new CypherExpression(CypherSupport.literal(value, context), kind), 900));
  }
}
