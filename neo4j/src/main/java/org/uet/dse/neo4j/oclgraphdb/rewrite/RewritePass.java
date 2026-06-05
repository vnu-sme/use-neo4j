package org.uet.dse.neo4j.oclgraphdb.rewrite;

public interface RewritePass {
  String name();

  RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round);
}
