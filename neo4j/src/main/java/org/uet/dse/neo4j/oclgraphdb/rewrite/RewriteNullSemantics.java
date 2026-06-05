package org.uet.dse.neo4j.oclgraphdb.rewrite;

public enum RewriteNullSemantics {
  UNKNOWN,
  NULL_AS_UNDEFINED,
  STRICT_NULL,
  FILTERED_OUT
}
