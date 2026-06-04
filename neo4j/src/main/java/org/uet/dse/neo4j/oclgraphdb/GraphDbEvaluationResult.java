package org.uet.dse.neo4j.oclgraphdb;

public class GraphDbEvaluationResult {
  private final Object value;
  private final String cypher;
  private final boolean cypherBacked;

  public GraphDbEvaluationResult(Object value, String cypher, boolean cypherBacked) {
    this.value = value;
    this.cypher = cypher;
    this.cypherBacked = cypherBacked;
  }

  public Object getValue() {
    return value;
  }

  public String getCypher() {
    return cypher;
  }

  public boolean isCypherBacked() {
    return cypherBacked;
  }
}
