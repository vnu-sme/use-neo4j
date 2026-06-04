package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;

public class RootPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.SCALAR_QUERY
        || pattern.getKind() == MultiPassPatternKind.CONTEXT_INVARIANT;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    PatternInstance child = pattern.getChildren().get(0);
    return compiler.compileExpression(child, context, compiler.expectedFor(child.getOutputKind()));
  }
}
