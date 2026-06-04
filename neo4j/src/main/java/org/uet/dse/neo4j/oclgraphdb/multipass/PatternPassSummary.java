package org.uet.dse.neo4j.oclgraphdb.multipass;

public class PatternPassSummary {
  private final int round;
  private final String passName;
  private final int addedPatterns;
  private final String error;

  public PatternPassSummary(int round, String passName, int addedPatterns) {
    this(round, passName, addedPatterns, null);
  }

  public PatternPassSummary(int round, String passName, int addedPatterns, String error) {
    this.round = round;
    this.passName = passName;
    this.addedPatterns = addedPatterns;
    this.error = error;
  }

  public int getRound() {
    return round;
  }

  public String getPassName() {
    return passName;
  }

  public int getAddedPatterns() {
    return addedPatterns;
  }

  public String getError() {
    return error;
  }

  public String toSummaryLine() {
    String line = "round=" + round + " pass=" + passName + " added=" + addedPatterns;
    return error == null || error.isBlank() ? line : line + " error=" + error;
  }
}
