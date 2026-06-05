package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.ArrayDeque;
import java.util.Deque;

public class RewritePatternTree {
  private final RewritePatternNode root;

  public RewritePatternTree(RewritePatternNode root) {
    this.root = root;
  }

  public RewritePatternNode getRoot() {
    return root;
  }

  public String structuralSignature() {
    return root == null ? "<empty>" : root.structuralSignature();
  }

  public int countNodes() {
    if (root == null) return 0;
    int count = 0;
    Deque<RewritePatternNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      RewritePatternNode node = stack.pop();
      count++;
      for (RewritePatternNode child : node.getChildren()) {
        stack.push(child);
      }
    }
    return count;
  }

  public int maxDepth() {
    return maxDepth(root, 1);
  }

  private int maxDepth(RewritePatternNode node, int depth) {
    if (node == null) return depth - 1;
    int max = depth;
    for (RewritePatternNode child : node.getChildren()) {
      max = Math.max(max, maxDepth(child, depth + 1));
    }
    return max;
  }
}
