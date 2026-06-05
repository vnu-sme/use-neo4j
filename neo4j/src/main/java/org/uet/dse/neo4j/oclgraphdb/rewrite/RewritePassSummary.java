package org.uet.dse.neo4j.oclgraphdb.rewrite;

public class RewritePassSummary {
  private final int round;
  private final String passName;
  private final int rewrittenNodes;
  private final String error;

  public RewritePassSummary(int round, String passName, int rewrittenNodes, String error) {
    this.round = round;
    this.passName = passName;
    this.rewrittenNodes = rewrittenNodes;
    this.error = error;
  }

  public int getRound() {
    return round;
  }

  public String getPassName() {
    return passName;
  }

  public int getRewrittenNodes() {
    return rewrittenNodes;
  }

  public String getError() {
    return error;
  }

  public String toSummaryLine() {
    String line = "round=" + round + " pass=" + passName + " rewritten=" + rewrittenNodes;
    return error == null || error.isBlank() ? line : line + " error=" + error;
  }
}
