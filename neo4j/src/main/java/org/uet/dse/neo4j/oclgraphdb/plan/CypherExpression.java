package org.uet.dse.neo4j.oclgraphdb.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CypherExpression {
  private final String expression;
  private final ExpressionKind kind;
  private final List<String> preludeClauses;
  private final int coveredNodes;
  private final int cost;

  public CypherExpression(String expression, ExpressionKind kind) {
    this(expression, kind, Collections.emptyList(), 1, 0);
  }

  public CypherExpression(
      String expression,
      ExpressionKind kind,
      List<String> preludeClauses,
      int coveredNodes,
      int cost
  ) {
    this.expression = expression;
    this.kind = kind;
    this.preludeClauses = List.copyOf(preludeClauses);
    this.coveredNodes = coveredNodes;
    this.cost = cost;
  }

  public CypherExpression withPrelude(List<String> additionalPrelude) {
    List<String> merged = new ArrayList<>(preludeClauses);
    merged.addAll(additionalPrelude);
    return new CypherExpression(expression, kind, merged, coveredNodes, cost);
  }

  public static CypherExpression combine(String expression, ExpressionKind kind, CypherExpression... children) {
    List<String> prelude = new ArrayList<>();
    int covered = 1;
    int cost = 0;
    for (CypherExpression child : children) {
      prelude.addAll(child.getPreludeClauses());
      covered += child.getCoveredNodes();
      cost += child.getCost();
    }
    return new CypherExpression(expression, kind, prelude, covered, cost);
  }

  public String getExpression() {
    return expression;
  }

  public ExpressionKind getKind() {
    return kind;
  }

  public List<String> getPreludeClauses() {
    return preludeClauses;
  }

  public int getCoveredNodes() {
    return coveredNodes;
  }

  public int getCost() {
    return cost;
  }
}
