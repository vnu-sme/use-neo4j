package org.uet.dse.neo4j.oclgraphdb.multipass;

public class MultiPassPatternOptions {
  private final boolean autoPasses;
  private final int maxPasses;
  private final boolean stopOnFixpoint;

  public MultiPassPatternOptions(boolean autoPasses, int maxPasses, boolean stopOnFixpoint) {
    this.autoPasses = autoPasses;
    this.maxPasses = maxPasses;
    this.stopOnFixpoint = stopOnFixpoint;
  }

  public static MultiPassPatternOptions defaults() {
    return new MultiPassPatternOptions(true, 0, true);
  }

  public static MultiPassPatternOptions fixedPasses(int maxPasses) {
    return new MultiPassPatternOptions(false, maxPasses, true);
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
