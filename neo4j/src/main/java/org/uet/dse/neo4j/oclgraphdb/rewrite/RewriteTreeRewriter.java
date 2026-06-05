package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class RewriteTreeRewriter {
  private RewriteTreeRewriter() {}

  public static RewritePassResult bottomUp(
      RewritePatternNode root,
      RewriteContext context,
      int passNumber,
      Function<RewritePatternNode, RewritePatternNode> transform
  ) {
    Counter counter = new Counter();
    RewritePatternNode rewritten = rewrite(root, context, passNumber, transform, counter);
    return new RewritePassResult(rewritten, counter.count);
  }

  private static RewritePatternNode rewrite(
      RewritePatternNode node,
      RewriteContext context,
      int passNumber,
      Function<RewritePatternNode, RewritePatternNode> transform,
      Counter counter
  ) {
    if (node == null) return null;

    List<RewritePatternNode> rewrittenChildren = new ArrayList<>();
    boolean childChanged = false;
    for (RewritePatternNode child : node.getChildren()) {
      RewritePatternNode rewrittenChild = rewrite(child, context, passNumber, transform, counter);
      rewrittenChildren.add(rewrittenChild);
      childChanged |= rewrittenChild != child;
    }

    RewritePatternNode current = childChanged
        ? node.toBuilder(context.nodeFactory(), passNumber).children(rewrittenChildren).build()
        : node;
    RewritePatternNode transformed = transform.apply(current);
    if (transformed != current && !transformed.structuralSignature().equals(current.structuralSignature())) {
      counter.count++;
    }
    return transformed;
  }

  private static class Counter {
    int count;
  }
}
