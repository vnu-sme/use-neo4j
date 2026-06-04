package org.uet.dse.neo4j.oclgraphdb.plan;

public enum ExpectedKind {
  ANY,
  VALUE,
  BOOLEAN,
  NODE,
  COLLECTION,
  TYPE;

  public boolean accepts(ExpressionKind actual) {
    if (this == ANY) return true;
    return switch (this) {
      case VALUE -> actual == ExpressionKind.VALUE || actual == ExpressionKind.NODE;
      case BOOLEAN -> actual == ExpressionKind.BOOLEAN;
      case NODE -> actual == ExpressionKind.NODE;
      case COLLECTION -> actual == ExpressionKind.COLLECTION;
      case TYPE -> actual == ExpressionKind.TYPE;
      default -> true;
    };
  }
}
