package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.List;

public class MultiPassPatternPlan {
  private final PatternGraph graph;
  private final List<PatternPassSummary> passSummaries;
  private final int configuredMaxPasses;
  private final int executedRounds;
  private final boolean reachedFixpoint;

  public MultiPassPatternPlan(
      PatternGraph graph,
      List<PatternPassSummary> passSummaries,
      int configuredMaxPasses,
      int executedRounds,
      boolean reachedFixpoint
  ) {
    this.graph = graph;
    this.passSummaries = List.copyOf(passSummaries);
    this.configuredMaxPasses = configuredMaxPasses;
    this.executedRounds = executedRounds;
    this.reachedFixpoint = reachedFixpoint;
  }

  public PatternGraph getGraph() {
    return graph;
  }

  public List<PatternPassSummary> getPassSummaries() {
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

  public int getPatternCount() {
    return graph.allPatterns().size();
  }
}
