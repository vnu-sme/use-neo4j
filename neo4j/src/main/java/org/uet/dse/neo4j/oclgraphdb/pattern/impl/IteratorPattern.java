package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class IteratorPattern implements OclPattern {
  @Override
  public String name() {
    return "iterator";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIterator;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTIterator it = (ASTIterator) node;
    Optional<PlanCandidate> source = planner.plan(it.source, context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return List.of();

    CompileContext scoped = PatternCypherUtils.collectionElementType(it.source, context)
        .map(type -> context.enterScope(it.iteratorName, type))
        .orElseGet(() -> context.enterScope(it.iteratorName));
    ExpectedKind bodyExpected = switch (it.operation.toLowerCase()) {
      case "select", "reject", "forall", "exists", "one", "any" -> ExpectedKind.BOOLEAN;
      case "collect", "collectnested" -> ExpectedKind.ANY;
      default -> ExpectedKind.ANY;
    };
    Optional<PlanCandidate> body = planner.plan(it.body, scoped, bodyExpected);
    if (body.isEmpty()) return List.of();

    String collection = source.get().expression().getExpression();
    String var = it.iteratorName;
    String bodyExpr = body.get().expression().getExpression();
    String expression;
    ExpressionKind kind;

    switch (it.operation.toLowerCase()) {
      case "exists" -> {
        expression = "any(" + var + " IN " + collection + " WHERE " + bodyExpr + ")";
        kind = ExpressionKind.BOOLEAN;
      }
      case "forall" -> {
        expression = "all(" + var + " IN " + collection + " WHERE " + bodyExpr + ")";
        kind = ExpressionKind.BOOLEAN;
      }
      case "one" -> {
        expression = "size([" + var + " IN " + collection + " WHERE " + bodyExpr + "]) = 1";
        kind = ExpressionKind.BOOLEAN;
      }
      case "any" -> {
        expression = "head([" + var + " IN " + collection + " WHERE " + bodyExpr + " | " + var + "])";
        kind = ExpressionKind.NODE;
      }
      case "select" -> {
        expression = "[" + var + " IN " + collection + " WHERE " + bodyExpr + "]";
        kind = ExpressionKind.COLLECTION;
      }
      case "reject" -> {
        expression = "[" + var + " IN " + collection + " WHERE NOT (" + bodyExpr + ")]";
        kind = ExpressionKind.COLLECTION;
      }
      case "collect" -> {
        expression = "[" + var + " IN " + collection + " | " + bodyExpr + "]";
        kind = ExpressionKind.COLLECTION;
      }
      case "collectnested" -> {
        expression = "reduce(acc = [], item IN [" + var + " IN " + collection + " | " + bodyExpr + "] | acc + item)";
        kind = ExpressionKind.COLLECTION;
      }
      default -> {
        return List.of();
      }
    }

    CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression(), body.get().expression());
    return List.of(PlanCandidate.exact(it.operation, expr, 940));
  }
}
