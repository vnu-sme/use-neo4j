package org.uet.dse.neo4j.oclgraphdb.rewrite.pass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;

public class QueryTreePass implements RewritePass {
  @Override
  public String name() {
    return "rewrite-query";
  }

  @Override
  public RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round) {
    return RewriteTreeRewriter.bottomUp(root, context, round, node -> rewrite(node, context, round));
  }

  private RewritePatternNode rewrite(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getKind() == RewritePatternKind.COLLECTION_OPERATION && node.getAstNode() instanceof ASTCollectionOp op) {
      return rewriteCollectionOperation(node, op, context, round);
    }

    if (node.getKind() != RewritePatternKind.ITERATOR && node.getKind() != RewritePatternKind.QUERY) {
      return node;
    }
    if (node.getChildren().size() < 2) return node;

    RewriteNodeSupport.QueryShape query = RewriteNodeSupport.queryShape(node);
    String operation = query.operation().toLowerCase(Locale.ROOT);
    RewritePatternNode source = node.getChildren().get(0);
    RewritePatternNode body = refineBodyWithIteratorScope(query, source, node.getChildren().get(1), context, round);
    Optional<String> elementType = RewriteNodeSupport.inferCollectionElementType(source, context.compileContext());

    List<RewritePatternCandidate> candidates = new ArrayList<>();
    switch (operation) {
      case "select", "reject" -> addQueryCandidate(
          candidates,
          "filter",
          filter(node, source, body, operation, elementType.orElse(""), context, round),
          node,
          70,
          80,
          "Iterator predicate can be represented as a collection filter."
      );
      case "forall", "exists", "one", "any" -> addQueryCandidate(
          candidates,
          "quantifier",
          quantifier(node, source, body, operation, context, round),
          node,
          85,
          90,
          "Iterator predicate can be represented as a Cypher quantifier."
      );
      case "collect", "collectnested" -> addQueryCandidate(
          candidates,
          "collect",
          collect(node, source, body, operation, context, round),
          node,
          65,
          70,
          "Iterator body can be represented as a Cypher projection."
      );
      case "isunique" -> addQueryCandidate(
          candidates,
          "unique",
          unaryQuery(node, source, body, RewritePatternKind.UNIQUE, operation, RewriteSemantic.bool(), context, round),
          node,
          75,
          80,
          "Iterator uniqueness can be represented as a distinct-size comparison."
      );
      case "sortedby" -> addQueryCandidate(
          candidates,
          "sorted",
          unaryQuery(
              node,
              source,
              body,
              RewritePatternKind.SORTED,
              operation,
              RewriteSemantic.collection(elementType.orElse(""), RewriteCollectionKind.SEQUENCE),
              context,
              round
          ),
          node,
          60,
          65,
          "Iterator sorting can be represented as an ordered Cypher subquery."
      );
      case "closure" -> addQueryCandidate(
          candidates,
          "closure",
          unaryQuery(
              node,
              source,
              body,
              RewritePatternKind.CLOSURE,
              operation,
              RewriteSemantic.collection(elementType.orElse(""), RewriteCollectionKind.SET),
              context,
              round
          ),
          node,
          65,
          70,
          "Iterator closure can be represented as recursive graph expansion."
      );
      default -> {
        if (body != node.getChildren().get(1)) {
          candidates.add(RewritePatternCandidate.builder(
                  "refined-query-body",
                  node.toBuilder(context.nodeFactory(), round).children(List.of(source, body)).build()
              )
              .coverage(countSubtreeNodes(node))
              .specificity(20)
              .cypherPushdown(20)
              .estimatedCost(30)
              .semanticExact(body.getSemantic().isExact())
              .reason("Only the iterator body was refined in scoped context.")
              .build());
        }
      }
    }
    return RewriteCandidateSupport.selectOrOriginal(node, candidates, context, round);
  }

  private RewritePatternNode rewriteCollectionOperation(
      RewritePatternNode node,
      ASTCollectionOp op,
      RewriteContext context,
      int round
  ) {
    if (node.getChildren().isEmpty() || !isFilterTerminal(op.opName)) return node;
    RewritePatternNode source = node.getChildren().get(0);
    if (source.getKind() != RewritePatternKind.FILTER) return node;

    RewriteSemantic semantic = "size".equals(op.opName)
        ? RewriteSemantic.value("Integer")
        : RewriteSemantic.bool();
    RewritePatternNode terminal = context.nodeFactory().builder(RewritePatternKind.FILTER_TERMINAL, node.getAstNode(), round)
        .label(source.getLabel() + "->" + op.opName)
        .children(List.of(source))
        .semantic(semantic.toBuilder().exact(source.getSemantic().isExact()).build())
        .metadata(node.getMetadata())
        .metadata("operation", op.opName)
        .metadata("filterOperation", source.metadata("operation"))
        .score(2350 + source.getScore() / 10)
        .build();

    int coverage = countSubtreeNodes(node);
    List<RewritePatternCandidate> candidates = new ArrayList<>();
    candidates.add(RewritePatternCandidate.builder("filter-terminal", terminal)
        .coverage(coverage)
        .specificity(95)
        .cypherPushdown(95)
        .estimatedCost("size".equals(op.opName) ? 15 : 5)
        .semanticExact(source.getSemantic().isExact())
        .reason("Filtered collection terminal can avoid materializing an intermediate collection.")
        .build());

    if (isExistenceTerminal(op.opName)) {
      RewritePatternNode existsFilter = context.nodeFactory().builder(RewritePatternKind.EXISTS_FILTER, node.getAstNode(), round)
          .label(source.getLabel() + "->" + op.opName)
          .children(List.of(source))
          .semantic(RewriteSemantic.bool().toBuilder().exact(source.getSemantic().isExact()).build())
          .metadata(node.getMetadata())
          .metadata("operation", op.opName)
          .metadata("filterOperation", source.metadata("operation"))
          .score(2500 + source.getScore() / 10)
          .build();
      candidates.add(RewritePatternCandidate.builder("exists-filter", existsFilter)
          .coverage(coverage)
          .specificity(110)
          .cypherPushdown(110)
          .estimatedCost(0)
          .semanticExact(source.getSemantic().isExact())
          .reason("Existence terminal can be represented as an any(...) predicate instead of counting a filtered list.")
          .build());
    }
    return RewriteCandidateSupport.selectOrOriginal(node, candidates, context, round);
  }

  private RewritePatternNode refineBodyWithIteratorScope(
      RewriteNodeSupport.QueryShape query,
      RewritePatternNode source,
      RewritePatternNode body,
      RewriteContext context,
      int round
  ) {
    CompileContext scopedCompileContext = RewriteNodeSupport.enterQueryScope(query, source, context.compileContext());
    RewriteContext scopedContext = new RewriteContext(
        scopedCompileContext,
        context.legacyPlanner(),
        context.nodeFactory(),
        context.patternSelector()
    );
    RewritePatternNode refined = body;
    refined = new SemanticPropertyTreePass().apply(refined, scopedContext, round).getRoot();
    refined = new CompareTreePass().apply(refined, scopedContext, round).getRoot();
    refined = new ControlFlowTreePass().apply(refined, scopedContext, round).getRoot();
    return refined;
  }

  private RewritePatternNode filter(
      RewritePatternNode original,
      RewritePatternNode source,
      RewritePatternNode body,
      String operation,
      String elementType,
      RewriteContext context,
      int round
  ) {
    if (!RewriteNodeSupport.isBooleanLike(body)) return null;
    return context.nodeFactory().builder(RewritePatternKind.FILTER, original.getAstNode(), round)
        .label(operation)
        .children(List.of(source, body))
        .semantic(RewriteSemantic.collection(elementType, RewriteCollectionKind.COLLECTION)
            .toBuilder()
            .exact(source.getSemantic().isExact() && body.getSemantic().isExact())
            .build())
        .metadata(original.getMetadata())
        .metadata("operation", operation)
        .score(2100 + source.getScore() / 10 + body.getScore() / 10)
        .build();
  }

  private RewritePatternNode quantifier(
      RewritePatternNode original,
      RewritePatternNode source,
      RewritePatternNode body,
      String operation,
      RewriteContext context,
      int round
  ) {
    if (!RewriteNodeSupport.isBooleanLike(body)) return null;
    RewriteSemantic semantic = "any".equals(operation)
        ? RewriteSemantic.node("", RewriteCardinality.ZERO_OR_ONE).toBuilder().exact(source.getSemantic().isExact() && body.getSemantic().isExact()).build()
        : RewriteSemantic.bool().toBuilder().exact(source.getSemantic().isExact() && body.getSemantic().isExact()).build();
    return context.nodeFactory().builder(RewritePatternKind.QUANTIFIER, original.getAstNode(), round)
        .label(operation)
        .children(List.of(source, body))
        .semantic(semantic)
        .metadata(original.getMetadata())
        .metadata("operation", operation)
        .score(2200 + source.getScore() / 10 + body.getScore() / 10)
        .build();
  }

  private RewritePatternNode collect(
      RewritePatternNode original,
      RewritePatternNode source,
      RewritePatternNode body,
      String operation,
      RewriteContext context,
      int round
  ) {
    return context.nodeFactory().builder(RewritePatternKind.COLLECT, original.getAstNode(), round)
        .label(operation)
        .children(List.of(source, body))
        .semantic(RewriteSemantic.collection(body.getSemantic().getNodeType(), RewriteCollectionKind.BAG)
            .toBuilder()
            .exact(source.getSemantic().isExact() && body.getSemantic().isExact())
            .build())
        .metadata(original.getMetadata())
        .metadata("operation", operation)
        .score(1900 + source.getScore() / 10 + body.getScore() / 10)
        .build();
  }

  private RewritePatternNode unaryQuery(
      RewritePatternNode original,
      RewritePatternNode source,
      RewritePatternNode body,
      RewritePatternKind kind,
      String operation,
      RewriteSemantic semantic,
      RewriteContext context,
      int round
  ) {
    return context.nodeFactory().builder(kind, original.getAstNode(), round)
        .label(operation)
        .children(List.of(source, body))
        .semantic(semantic.toBuilder().exact(source.getSemantic().isExact() && body.getSemantic().isExact()).build())
        .metadata(original.getMetadata())
        .metadata("operation", operation)
        .score(1850 + source.getScore() / 10 + body.getScore() / 10)
        .build();
  }

  private void addQueryCandidate(
      List<RewritePatternCandidate> candidates,
      String name,
      RewritePatternNode replacement,
      RewritePatternNode original,
      int specificity,
      int cypherPushdown,
      String reason
  ) {
    if (replacement == null) return;
    candidates.add(RewritePatternCandidate.builder(name, replacement)
        .coverage(countSubtreeNodes(original))
        .specificity(specificity)
        .cypherPushdown(cypherPushdown)
        .estimatedCost(Math.max(0, 100 - cypherPushdown))
        .semanticExact(replacement.getSemantic().isExact())
        .reason(reason)
        .build());
  }

  private boolean isFilterTerminal(String opName) {
    return "size".equals(opName) || "isEmpty".equals(opName) || "isNotEmpty".equals(opName) || "notEmpty".equals(opName);
  }

  private boolean isExistenceTerminal(String opName) {
    return "isEmpty".equals(opName) || "isNotEmpty".equals(opName) || "notEmpty".equals(opName);
  }

  private int countSubtreeNodes(RewritePatternNode node) {
    int count = 1;
    for (RewritePatternNode child : node.getChildren()) {
      count += countSubtreeNodes(child);
    }
    return count;
  }
}
