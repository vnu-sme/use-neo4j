package org.uet.dse.neo4j.oclgraphdb.rewrite.pass;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;

public class ControlFlowTreePass implements RewritePass {
  @Override
  public String name() {
    return "rewrite-control-flow";
  }

  @Override
  public RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round) {
    return RewriteTreeRewriter.bottomUp(root, context, round, node -> rewrite(node, context, round));
  }

  private RewritePatternNode rewrite(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getKind() == RewritePatternKind.LET) {
      return rewriteLet(node, context, round);
    }
    if (node.getKind() == RewritePatternKind.IF) {
      return rewriteIf(node, context, round);
    }
    return node;
  }

  private RewritePatternNode rewriteLet(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getChildren().isEmpty()) return node;
    RewritePatternNode body = node.getChildren().get(node.getChildren().size() - 1);
    boolean exact = body.getSemantic().isExact();
    for (RewritePatternNode child : node.getChildren()) {
      exact &= child.getSemantic().isExact();
    }
    return context.nodeFactory().builder(RewritePatternKind.LET_SCOPE, node.getAstNode(), round)
        .label("let")
        .children(node.getChildren())
        .semantic(body.getSemantic().toBuilder().exact(exact).build())
        .metadata(node.getMetadata())
        .score(1800 + body.getScore() / 10)
        .build();
  }

  private RewritePatternNode rewriteIf(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getChildren().size() < 3) return node;
    RewritePatternNode condition = node.getChildren().get(0);
    RewritePatternNode thenNode = node.getChildren().get(1);
    RewritePatternNode elseNode = node.getChildren().get(2);
    if (!RewriteNodeSupport.isBooleanLike(condition)) return node;

    RewriteSemantic semantic = compatibleSemantic(thenNode.getSemantic(), elseNode.getSemantic())
        .toBuilder()
        .exact(condition.getSemantic().isExact() && thenNode.getSemantic().isExact() && elseNode.getSemantic().isExact())
        .build();
    return context.nodeFactory().builder(RewritePatternKind.CONDITIONAL, node.getAstNode(), round)
        .label("if")
        .children(List.of(condition, thenNode, elseNode))
        .semantic(semantic)
        .metadata(node.getMetadata())
        .score(1800 + condition.getScore() / 10 + thenNode.getScore() / 10 + elseNode.getScore() / 10)
        .build();
  }

  private RewriteSemantic compatibleSemantic(RewriteSemantic left, RewriteSemantic right) {
    if (left.getOutputKind() == right.getOutputKind()) {
      return left;
    }
    if ((left.getOutputKind() == ExpressionKind.VALUE && right.getOutputKind() == ExpressionKind.BOOLEAN)
        || (left.getOutputKind() == ExpressionKind.BOOLEAN && right.getOutputKind() == ExpressionKind.VALUE)) {
      return RewriteSemantic.value("");
    }
    return RewriteSemantic.unknown();
  }
}
