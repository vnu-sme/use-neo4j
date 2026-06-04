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
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class AllInstancesIteratorPattern implements OclPattern {
  @Override
  public String name() {
    return "all-instances-iterator";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIterator;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTIterator iterator = (ASTIterator) node;
    String operation = iterator.operation.toLowerCase();
    if (!"forall".equals(operation) && !"exists".equals(operation)) {
      return List.of();
    }

    Optional<String> className = PatternCypherUtils.allInstancesClassName(iterator.source, context);
    if (className.isEmpty()) return List.of();

    CompileContext scoped = context.enterScope(iterator.iteratorName, className.get());
    Optional<PlanCandidate> body = planner.plan(iterator.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return List.of();

    String predicate = "forall".equals(operation)
        ? buildForAllExpression(className.get(), iterator.iteratorName, body.get().expression(), context)
        : buildExistsExpression(className.get(), iterator.iteratorName, body.get().expression(), context);

    CypherExpression expr = new CypherExpression(
        predicate,
        ExpressionKind.BOOLEAN,
        List.of(),
        body.get().expression().getCoveredNodes() + 2,
        body.get().expression().getCost() + 2
    );
    return List.of(PlanCandidate.exact(name(), expr, 1400));
  }

  private String buildForAllExpression(
      String className,
      String iteratorName,
      CypherExpression body,
      CompileContext context
  ) {
    return "NOT EXISTS {\n"
        + String.join("\n", CypherSupport.allInstancesMatch(className, iteratorName, context)) + "\n"
        + String.join("\n", PatternCypherUtils.bodyFilter(body, "NOT (" + body.getExpression() + ")")) + "\n"
        + "}";
  }

  private String buildExistsExpression(
      String className,
      String iteratorName,
      CypherExpression body,
      CompileContext context
  ) {
    return "EXISTS {\n"
        + String.join("\n", CypherSupport.allInstancesMatch(className, iteratorName, context)) + "\n"
        + String.join("\n", PatternCypherUtils.bodyFilter(body, body.getExpression())) + "\n"
        + "}";
  }
}
