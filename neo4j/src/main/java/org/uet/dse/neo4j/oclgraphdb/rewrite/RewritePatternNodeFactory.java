package org.uet.dse.neo4j.oclgraphdb.rewrite;

import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class RewritePatternNodeFactory {
  private int nextId = 1;

  int nextId() {
    return nextId++;
  }

  public RewritePatternNode.Builder builder(RewritePatternKind kind, ASTNode astNode, int passNumber) {
    return new RewritePatternNode.Builder(this, kind, astNode, passNumber);
  }
}
