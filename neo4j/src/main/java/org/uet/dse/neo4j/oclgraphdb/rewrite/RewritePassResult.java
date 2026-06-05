package org.uet.dse.neo4j.oclgraphdb.rewrite;

public class RewritePassResult {
  private final RewritePatternNode root;
  private final int rewrittenNodes;

  public RewritePassResult(RewritePatternNode root, int rewrittenNodes) {
    this.root = root;
    this.rewrittenNodes = rewrittenNodes;
  }

  public RewritePatternNode getRoot() {
    return root;
  }

  public int getRewrittenNodes() {
    return rewrittenNodes;
  }
}
