package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTIterate;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class IteratePattern implements OclPattern {
  @Override
  public String name() {
    return "iterate";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTIterate;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTIterate iterate = (ASTIterate) node;
    Optional<PlanCandidate> source = planner.plan(iterate.source, context, ExpectedKind.COLLECTION);
    Optional<PlanCandidate> init = planner.plan(iterate.accumulator.value, context, ExpectedKind.ANY);
    if (source.isEmpty() || init.isEmpty()) return List.of();

    String accumulator = iterate.accumulator.variable.name;
    String inferredType = PatternCypherUtils.collectionElementType(iterate.source, context).orElse(null);
    CompileContext scoped = context.enterScope(iterate.iterator.name, iterate.iterator.hasType() ? iterate.iterator.typeName : inferredType)
        .withExpressionBinding(accumulator, new CypherExpression(accumulator, init.get().expression().getKind()), iterate.accumulator.variable.typeName);

    Optional<PlanCandidate> body = planner.plan(iterate.body, scoped, ExpectedKind.ANY);
    if (body.isEmpty() || !body.get().expression().getPreludeClauses().isEmpty()) return List.of();

    String expression = "reduce(" + accumulator + " = " + init.get().expression().getExpression()
        + ", " + iterate.iterator.name + " IN " + source.get().expression().getExpression()
        + " | " + body.get().expression().getExpression() + ")";
    CypherExpression cypher = CypherExpression.combine(expression, body.get().expression().getKind(), source.get().expression(), init.get().expression());
    return List.of(PlanCandidate.exact(name(), cypher, 900));
  }
}
