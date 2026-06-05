package org.uet.dse.neo4j.oclgraphdb.rewrite;

import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;

public class RewriteContext {
  private final CompileContext compileContext;
  private final PatternPlanner legacyPlanner;
  private final RewritePatternNodeFactory nodeFactory;
  private final RewritePatternSelector patternSelector;

  public RewriteContext(
      CompileContext compileContext,
      PatternPlanner legacyPlanner,
      RewritePatternNodeFactory nodeFactory
  ) {
    this(compileContext, legacyPlanner, nodeFactory, DefaultRewritePatternSelector.INSTANCE);
  }

  public RewriteContext(
      CompileContext compileContext,
      PatternPlanner legacyPlanner,
      RewritePatternNodeFactory nodeFactory,
      RewritePatternSelector patternSelector
  ) {
    this.compileContext = compileContext;
    this.legacyPlanner = legacyPlanner;
    this.nodeFactory = nodeFactory;
    this.patternSelector = patternSelector == null ? DefaultRewritePatternSelector.INSTANCE : patternSelector;
  }

  public CompileContext compileContext() {
    return compileContext;
  }

  public PatternPlanner legacyPlanner() {
    return legacyPlanner;
  }

  public RewritePatternNodeFactory nodeFactory() {
    return nodeFactory;
  }

  public RewritePatternSelector patternSelector() {
    return patternSelector;
  }
}
