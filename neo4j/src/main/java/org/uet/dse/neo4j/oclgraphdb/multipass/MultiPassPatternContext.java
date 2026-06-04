package org.uet.dse.neo4j.oclgraphdb.multipass;

import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;

public class MultiPassPatternContext {
  private final CompileContext compileContext;
  private final PatternPlanner legacyPlanner;

  public MultiPassPatternContext(CompileContext compileContext, PatternPlanner legacyPlanner) {
    this.compileContext = compileContext;
    this.legacyPlanner = legacyPlanner;
  }

  public CompileContext compileContext() {
    return compileContext;
  }

  public PatternPlanner legacyPlanner() {
    return legacyPlanner;
  }
}
