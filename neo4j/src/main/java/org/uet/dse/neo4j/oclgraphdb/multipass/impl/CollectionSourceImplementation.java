package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;

public class CollectionSourceImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.ALL_INSTANCES_SOURCE;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    String className = pattern.getMetadata().get("class");
    if (className == null || className.isBlank()) return Optional.empty();
    return Optional.of(CypherSupport.allInstancesExpression(className, context));
  }
}
