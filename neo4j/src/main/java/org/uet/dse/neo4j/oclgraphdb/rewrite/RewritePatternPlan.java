package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.List;

public class RewritePatternPlan {
  private final RewritePatternTree initialTree;
  private final RewritePatternTree finalTree;
  private final List<RewritePassSummary> passSummaries;
  private final int configuredMaxPasses;
  private final int executedRounds;
  private final boolean reachedFixpoint;

  public RewritePatternPlan(
      RewritePatternTree initialTree,
      RewritePatternTree finalTree,
      List<RewritePassSummary> passSummaries,
      int configuredMaxPasses,
      int executedRounds,
      boolean reachedFixpoint
  ) {
    this.initialTree = initialTree;
    this.finalTree = finalTree;
    this.passSummaries = List.copyOf(passSummaries);
    this.configuredMaxPasses = configuredMaxPasses;
    this.executedRounds = executedRounds;
    this.reachedFixpoint = reachedFixpoint;
  }

  public RewritePatternTree getInitialTree() {
    return initialTree;
  }

  public RewritePatternTree getFinalTree() {
    return finalTree;
  }

  public List<RewritePassSummary> getPassSummaries() {
    return passSummaries;
  }

  public int getConfiguredMaxPasses() {
    return configuredMaxPasses;
  }

  public int getExecutedRounds() {
    return executedRounds;
  }

  public boolean isReachedFixpoint() {
    return reachedFixpoint;
  }
}
