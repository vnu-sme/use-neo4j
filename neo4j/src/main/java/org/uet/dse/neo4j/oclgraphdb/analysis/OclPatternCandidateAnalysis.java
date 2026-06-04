package org.uet.dse.neo4j.oclgraphdb.analysis;

import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class OclPatternCandidateAnalysis {
  private final String patternName;
  private final ExpectedKind expectedKind;
  private final ExpressionKind expressionKind;
  private final int score;
  private final int coveredNodes;
  private final int cost;
  private final boolean exactSemantic;
  private final String cypherExpression;

  public OclPatternCandidateAnalysis(
      String patternName,
      ExpectedKind expectedKind,
      ExpressionKind expressionKind,
      int score,
      int coveredNodes,
      int cost,
      boolean exactSemantic,
      String cypherExpression
  ) {
    this.patternName = patternName;
    this.expectedKind = expectedKind;
    this.expressionKind = expressionKind;
    this.score = score;
    this.coveredNodes = coveredNodes;
    this.cost = cost;
    this.exactSemantic = exactSemantic;
    this.cypherExpression = cypherExpression;
  }

  public String getPatternName() {
    return patternName;
  }

  public ExpectedKind getExpectedKind() {
    return expectedKind;
  }

  public ExpressionKind getExpressionKind() {
    return expressionKind;
  }

  public int getScore() {
    return score;
  }

  public int getCoveredNodes() {
    return coveredNodes;
  }

  public int getCost() {
    return cost;
  }

  public boolean isExactSemantic() {
    return exactSemantic;
  }

  public String getCypherExpression() {
    return cypherExpression;
  }

  public String toSummaryLine() {
    return patternName
        + " expected=" + expectedKind
        + " kind=" + expressionKind
        + " score=" + score
        + " covered=" + coveredNodes
        + " cost=" + cost
        + " exact=" + exactSemantic;
  }
}
