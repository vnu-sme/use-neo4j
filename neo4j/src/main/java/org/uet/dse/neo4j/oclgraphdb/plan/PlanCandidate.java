package org.uet.dse.neo4j.oclgraphdb.plan;

public class PlanCandidate implements Comparable<PlanCandidate> {
  private final String patternName;
  private final CypherExpression expression;
  private final int score;
  private final boolean exactSemantic;

  public PlanCandidate(String patternName, CypherExpression expression, int score, boolean exactSemantic) {
    this.patternName = patternName;
    this.expression = expression;
    this.score = score;
    this.exactSemantic = exactSemantic;
  }

  public static PlanCandidate exact(String patternName, CypherExpression expression, int baseScore) {
    int score = baseScore + expression.getCoveredNodes() * 100 - expression.getCost();
    return new PlanCandidate(patternName, expression, score, true);
  }

  public String getPatternName() {
    return patternName;
  }

  public CypherExpression expression() {
    return expression;
  }

  public int getScore() {
    return score;
  }

  public boolean isExactSemantic() {
    return exactSemantic;
  }

  @Override
  public int compareTo(PlanCandidate other) {
    return Integer.compare(other.score, this.score);
  }
}
