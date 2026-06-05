package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTLet;
import org.uet.dse.neo4j.oclite.ast.ASTLetBinding;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class LetPattern implements OclPattern {
  @Override
  public String name() {
    return "let";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTLet;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTLet let = (ASTLet) node;
    CompileContext scoped = context;

    for (ASTLetBinding binding : let.bindings) {
      Optional<PlanCandidate> value = planner.plan(binding.value, scoped, ExpectedKind.ANY);
      if (value.isEmpty()) return List.of();
      scoped = scoped.withExpressionBinding(binding.variable.name, value.get().expression(), binding.variable.typeName);
    }

    Optional<PlanCandidate> body = planner.plan(let.body, scoped, expected);
    if (body.isEmpty()) return List.of();

    CypherExpression expression = body.get().expression();
    return List.of(PlanCandidate.exact(name(), expression, 930));
  }
}
