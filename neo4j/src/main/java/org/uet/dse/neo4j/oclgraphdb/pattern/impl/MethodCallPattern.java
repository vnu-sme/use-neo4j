package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.*;

public class MethodCallPattern implements OclPattern {
  @Override
  public String name() {
    return "method-call";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTMethodCall;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTMethodCall call = (ASTMethodCall) node;
    String method = call.methodName;

    if ("allInstances".equalsIgnoreCase(method)) {
      return planner.plan(call.source, context, ExpectedKind.TYPE)
          .map(type -> {
            String className = type.expression().getExpression();
            CypherExpression expr = CypherSupport.allInstancesExpression(className, context);
            return List.of(PlanCandidate.exact("allInstances", expr, 1000));
          })
          .orElse(List.of());
    }

    if ("isDefined".equals(method) || "isUndefined".equals(method)) {
      Optional<PlanCandidate> sourceCandidate = planner.plan(call.source, context, ExpectedKind.COLLECTION)
          .or(() -> planner.plan(call.source, context, ExpectedKind.ANY));
      return sourceCandidate
          .map(source -> {
            String condition = source.expression().getKind() == ExpressionKind.COLLECTION
                ? "size(" + source.expression().getExpression() + ") > 0"
                : source.expression().getExpression() + " IS NOT NULL";
            if ("isUndefined".equals(method)) condition = "NOT (" + condition + ")";
            CypherExpression expr = CypherExpression.combine(condition, ExpressionKind.BOOLEAN, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 930));
          })
          .orElse(List.of());
    }

    if ("oclIsTypeOf".equals(method) || "oclIsKindOf".equals(method)) {
      if (call.args.size() != 1 || !(call.args.get(0) instanceof ASTVar)) return List.of();
      String typeName = ((ASTVar) call.args.get(0)).name;
      return planner.plan(call.source, context, ExpectedKind.NODE)
          .map(source -> {
            String typeParam = context.addParam("typeName", typeName);
            String cls = context.nextName("cls");
            String predicate = "EXISTS { MATCH (" + source.expression().getExpression() + ")-[:ObjectInstanceOf]->(" + cls + ") ";
            if ("oclIsKindOf".equals(method)) {
              predicate += "WHERE " + cls + ".name = " + typeParam + " OR EXISTS { MATCH (" + cls + ")-[:Extends*1..]->(parent {name: " + typeParam + "}) }";
            } else {
              predicate += "WHERE " + cls + ".name = " + typeParam;
            }
            predicate += " }";
            CypherExpression expr = CypherExpression.combine(predicate, ExpressionKind.BOOLEAN, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 920));
          })
          .orElse(List.of());
    }

    if (call.args.isEmpty()) {
      ASTProperty synthetic = new ASTProperty(call.source, method);
      return PropertyPattern.INSTANCE.match(synthetic, context, expected, planner);
    }

    return List.of();
  }
}
