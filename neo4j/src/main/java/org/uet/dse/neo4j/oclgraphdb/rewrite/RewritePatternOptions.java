package org.uet.dse.neo4j.oclgraphdb.rewrite;

public class RewritePatternOptions {
  private final boolean autoPasses;
  private final int maxPasses;
  private final boolean stopOnFixpoint;

  public RewritePatternOptions(boolean autoPasses, int maxPasses, boolean stopOnFixpoint) {
    this.autoPasses = autoPasses;
    this.maxPasses = maxPasses;
    this.stopOnFixpoint = stopOnFixpoint;
  }

  public static RewritePatternOptions defaults() {
    return new RewritePatternOptions(true, 0, true);
  }

  public static RewritePatternOptions fixedPasses(int maxPasses) {
    return new RewritePatternOptions(false, maxPasses, true);
  }

  public boolean isAutoPasses() {
    return autoPasses;
  }

  public int getMaxPasses() {
    return maxPasses;
  }

  public boolean isStopOnFixpoint() {
    return stopOnFixpoint;
  }
}
