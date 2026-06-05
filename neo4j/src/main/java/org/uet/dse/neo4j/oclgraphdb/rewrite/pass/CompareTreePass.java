package org.uet.dse.neo4j.oclgraphdb.rewrite.pass;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;
import org.uet.dse.neo4j.oclite.ast.ASTNot;
import org.uet.dse.neo4j.oclite.ast.ASTUnary;

public class CompareTreePass implements RewritePass {
  @Override
  public String name() {
    return "rewrite-compare-logical";
  }

  @Override
  public RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round) {
    return RewriteTreeRewriter.bottomUp(root, context, round, node -> rewrite(node, context, round));
  }

  private RewritePatternNode rewrite(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getKind() == RewritePatternKind.BINARY && node.getAstNode() instanceof ASTBinary binary) {
      return rewriteBinary(node, binary, context, round);
    }
    if (node.getKind() == RewritePatternKind.UNARY && node.getAstNode() instanceof ASTUnary unary && "not".equals(unary.operator)) {
      return rewriteNegation(node, context, round);
    }
    if (node.getKind() == RewritePatternKind.NOT && node.getAstNode() instanceof ASTNot) {
      return rewriteNegation(node, context, round);
    }
    return node;
  }

  private RewritePatternNode rewriteBinary(RewritePatternNode node, ASTBinary binary, RewriteContext context, int round) {
    if (node.getChildren().size() < 2) return node;
    RewritePatternNode left = node.getChildren().get(0);
    RewritePatternNode right = node.getChildren().get(1);

    if (RewriteNodeSupport.isComparison(binary.op)
        && RewriteNodeSupport.isValueLike(left)
        && RewriteNodeSupport.isValueLike(right)) {
      List<RewritePatternCandidate> candidates = new ArrayList<>();
      boolean exact = left.getSemantic().isExact() && right.getSemantic().isExact();
      int coverage = countSubtreeNodes(node);

      candidates.add(RewritePatternCandidate.builder(
              "generic-compare",
              compareNode(node, binary, left, right, RewritePatternKind.COMPARE, exact, context, round, 1900)
          )
          .coverage(coverage)
          .specificity(30)
          .cypherPushdown(70)
          .estimatedCost(20)
          .semanticExact(exact)
          .reason("Generic value comparison.")
          .build());

      if (left.getKind() == RewritePatternKind.ATTRIBUTE_ACCESS || right.getKind() == RewritePatternKind.ATTRIBUTE_ACCESS) {
        candidates.add(RewritePatternCandidate.builder(
                "attribute-compare",
                compareNode(node, binary, left, right, RewritePatternKind.ATTRIBUTE_COMPARE, exact, context, round, 1950)
            )
            .coverage(coverage)
            .specificity(80)
            .cypherPushdown(90)
            .estimatedCost(10)
            .semanticExact(exact)
            .reason("Comparison contains attribute access and can be pushed as an attribute predicate.")
            .build());
      }

      if (left.getKind() == RewritePatternKind.NAVIGATION_ACCESS || right.getKind() == RewritePatternKind.NAVIGATION_ACCESS) {
        candidates.add(RewritePatternCandidate.builder(
                "navigation-compare",
                compareNode(node, binary, left, right, RewritePatternKind.NAVIGATION_COMPARE, exact, context, round, 1930)
            )
            .coverage(coverage)
            .specificity(75)
            .cypherPushdown(85)
            .estimatedCost(15)
            .semanticExact(exact)
            .reason("Comparison contains navigation access and can be pushed as a graph navigation predicate.")
            .build());
      }

      return RewriteCandidateSupport.selectOrOriginal(node, candidates, context, round);
    }

    if (RewriteNodeSupport.isLogical(binary.op)
        && RewriteNodeSupport.isBooleanLike(left)
        && RewriteNodeSupport.isBooleanLike(right)) {
      return context.nodeFactory().builder(RewritePatternKind.LOGICAL_COMPOSITION, node.getAstNode(), round)
          .label(binary.op)
          .children(List.of(left, right))
          .semantic(RewriteSemantic.bool().toBuilder().exact(left.getSemantic().isExact() && right.getSemantic().isExact()).build())
          .metadata(node.getMetadata())
          .metadata("operator", binary.op)
          .score(1700 + left.getScore() / 10 + right.getScore() / 10)
          .build();
    }
    return node;
  }

  private RewritePatternNode compareNode(
      RewritePatternNode original,
      ASTBinary binary,
      RewritePatternNode left,
      RewritePatternNode right,
      RewritePatternKind kind,
      boolean exact,
      RewriteContext context,
      int round,
      int baseScore
  ) {
    return context.nodeFactory().builder(kind, original.getAstNode(), round)
        .label(binary.op)
        .children(List.of(left, right))
        .semantic(RewriteSemantic.bool().toBuilder().exact(exact).build())
        .metadata(original.getMetadata())
        .metadata("operator", binary.op)
        .score(baseScore + left.getScore() / 10 + right.getScore() / 10)
        .build();
  }

  private RewritePatternNode rewriteNegation(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getChildren().isEmpty() || !RewriteNodeSupport.isBooleanLike(node.getChildren().get(0))) return node;
    RewritePatternNode child = node.getChildren().get(0);
    return context.nodeFactory().builder(RewritePatternKind.NEGATION, node.getAstNode(), round)
        .label("not")
        .children(List.of(child))
        .semantic(RewriteSemantic.bool().toBuilder().exact(child.getSemantic().isExact()).build())
        .metadata(node.getMetadata())
        .score(1750 + child.getScore() / 10)
        .build();
  }

  private int countSubtreeNodes(RewritePatternNode node) {
    int count = 1;
    for (RewritePatternNode child : node.getChildren()) {
      count += countSubtreeNodes(child);
    }
    return count;
  }
}
