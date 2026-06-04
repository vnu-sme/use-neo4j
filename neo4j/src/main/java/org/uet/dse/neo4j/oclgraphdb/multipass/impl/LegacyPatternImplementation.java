package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;

public class LegacyPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return true;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    return compiler.compileLegacy(pattern.getAstNode(), context, compiler.expectedFor(pattern.getOutputKind()));
  }
}
