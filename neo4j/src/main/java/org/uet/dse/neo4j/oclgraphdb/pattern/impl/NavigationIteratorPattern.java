package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
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
import org.uet.dse.neo4j.oclite.ast.ASTProperty;

public class NavigationIteratorPattern implements OclPattern {
  @Override
  public String name() {
    return "navigation-iterator";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIterator;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTIterator iterator = (ASTIterator) node;
    String operation = iterator.operation.toLowerCase();
    if (!"forall".equals(operation) && !"exists".equals(operation)) return List.of();
    if (!(iterator.source instanceof ASTProperty navigation)) return List.of();

    Optional<String> sourceType = PatternCypherUtils.sourceType(navigation.source, context);
    Optional<PropertyDescriptor> navigationDescriptor = sourceType.flatMap(type -> context.resolver().resolveNavigation(type, navigation.name));
    if (navigationDescriptor.isEmpty()) return List.of();

    Optional<PatternCypherUtils.BoundNode> source = PatternCypherUtils.bindNode(navigation.source, context, planner);
    if (source.isEmpty()) return List.of();

    Optional<String> targetType = navigationDescriptor.get().getTargetClassName();
    CompileContext scoped = context.enterScope(iterator.iteratorName, targetType.orElse(null));
    Optional<PlanCandidate> body = planner.plan(iterator.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return List.of();

    String predicate = "forall".equals(operation)
        ? buildForAll(source.get(), navigation.name, iterator.iteratorName, body.get().expression(), context)
        : buildExists(source.get(), navigation.name, iterator.iteratorName, body.get().expression(), context);
    CypherExpression expr = new CypherExpression(
        predicate,
        ExpressionKind.BOOLEAN,
        source.get().preludeClauses(),
        body.get().expression().getCoveredNodes() + 2,
        body.get().expression().getCost() + 1
    );
    return List.of(PlanCandidate.exact(name(), expr, 1350));
  }

  private String buildExists(
      PatternCypherUtils.BoundNode source,
      String roleName,
      String iteratorName,
      CypherExpression body,
      CompileContext context
  ) {
    List<String> clauses = new ArrayList<>();
    clauses.add("EXISTS {");
    clauses.addAll(CypherSupport.navigationMatch(source.name(), roleName, iteratorName, context));
    clauses.addAll(PatternCypherUtils.bodyFilter(body, body.getExpression()));
    clauses.add("}");
    return String.join("\n", clauses);
  }

  private String buildForAll(
      PatternCypherUtils.BoundNode source,
      String roleName,
      String iteratorName,
      CypherExpression body,
      CompileContext context
  ) {
    List<String> clauses = new ArrayList<>();
    clauses.add("NOT EXISTS {");
    clauses.addAll(CypherSupport.navigationMatch(source.name(), roleName, iteratorName, context));
    clauses.addAll(PatternCypherUtils.bodyFilter(body, "NOT (" + body.getExpression() + ")"));
    clauses.add("}");
    return String.join("\n", clauses);
  }
}
