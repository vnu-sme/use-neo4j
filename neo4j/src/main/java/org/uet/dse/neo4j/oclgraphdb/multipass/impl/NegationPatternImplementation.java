package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class NegationPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.NEGATION;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    Optional<CypherExpression> inner = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.BOOLEAN);
    return inner.map(expression -> CypherExpression.combine(
        "NOT (" + expression.getExpression() + ")",
        ExpressionKind.BOOLEAN,
        expression
    ));
  }
}
