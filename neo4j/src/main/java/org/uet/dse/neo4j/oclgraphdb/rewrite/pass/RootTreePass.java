package org.uet.dse.neo4j.oclgraphdb.rewrite.pass;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;
import org.uet.dse.neo4j.oclite.ast.ASTContext;

public class RootTreePass implements RewritePass {
  @Override
  public String name() {
    return "rewrite-root";
  }

  @Override
  public RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round) {
    RewritePatternNode rewritten = rewriteRoot(root, context, round);
    int changed = rewritten.structuralSignature().equals(root.structuralSignature()) ? 0 : 1;
    return new RewritePassResult(rewritten, changed);
  }

  private RewritePatternNode rewriteRoot(RewritePatternNode root, RewriteContext context, int round) {
    if (root.getKind() == RewritePatternKind.CONTEXT_INVARIANT || root.getKind() == RewritePatternKind.SCALAR_QUERY) {
      return root;
    }

    if (root.getKind() == RewritePatternKind.CONTEXT && root.getAstNode() instanceof ASTContext astContext) {
      if (root.getChildren().isEmpty() || !RewriteNodeSupport.isBooleanLike(root.getChildren().get(0))) {
        return root;
      }
      RewritePatternNode expression = root.getChildren().get(0);
      return context.nodeFactory().builder(RewritePatternKind.CONTEXT_INVARIANT, root.getAstNode(), round)
          .label(astContext.className + "::" + astContext.invName)
          .children(List.of(expression))
          .semantic(RewriteSemantic.bool().toBuilder().exact(expression.getSemantic().isExact()).build())
          .metadata(root.getMetadata())
          .metadata("class", astContext.className)
          .metadata("invariant", astContext.invName)
          .metadata("contextVariable", astContext.contextVariableName)
          .score(2600 + expression.getScore() / 10)
          .build();
    }

    return context.nodeFactory().builder(RewritePatternKind.SCALAR_QUERY, root.getAstNode(), round)
        .label(root.getLabel())
        .children(List.of(root))
        .semantic(root.getSemantic())
        .metadata(root.getMetadata())
        .score(2500 + root.getScore() / 10)
        .build();
  }
}
