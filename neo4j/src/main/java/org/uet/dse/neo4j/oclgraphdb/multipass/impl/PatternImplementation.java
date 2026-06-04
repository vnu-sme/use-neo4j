package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;

public interface PatternImplementation {
  boolean supports(PatternInstance pattern);

  Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  );
}
