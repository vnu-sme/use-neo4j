package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTIntegerLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTRealLiteral;

public class SizeCompareCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "size-compare-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTBinary binary && AstPatternSupport.isComparison(binary.op)) {
        added += addSizeCompare(graph, binary, passNumber);
      } else if (node instanceof ASTCollectionOp collectionOp) {
        added += addEmptyCheck(graph, collectionOp, passNumber);
      }
    }
    return added;
  }

  private int addSizeCompare(PatternGraph graph, ASTBinary binary, int passNumber) {
    Optional<PatternInstance> leftSource = sizedSource(graph, binary.left);
    Optional<PatternInstance> rightSource = sizedSource(graph, binary.right);

    if (leftSource.isPresent()) {
      Optional<PatternInstance> threshold = bestValueLike(graph, binary.right);
      if (threshold.isEmpty()) return 0;
      Optional<Boolean> desiredExists = desiredExists(binary.op, true, literalNumber(binary.right));
      return addComparePattern(graph, binary, leftSource.get(), threshold.get(), true, desiredExists, passNumber);
    }

    if (rightSource.isPresent()) {
      Optional<PatternInstance> threshold = bestValueLike(graph, binary.left);
      if (threshold.isEmpty()) return 0;
      Optional<Boolean> desiredExists = desiredExists(binary.op, false, literalNumber(binary.left));
      return addComparePattern(graph, binary, rightSource.get(), threshold.get(), false, desiredExists, passNumber);
    }

    return 0;
  }

  private int addEmptyCheck(PatternGraph graph, ASTCollectionOp op, int passNumber) {
    if (!isEmptyTerminal(op.opName)) return 0;
    Optional<PatternInstance> source = collectionSource(graph, op.source);
    if (source.isEmpty()) return 0;

    boolean desiredExists = !"isEmpty".equalsIgnoreCase(op.opName);
    PatternInstance sourcePattern = source.get();
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.OPTIONAL_NAVIGATION_EMPTY_CHECK, op)
        .label(sourcePattern.getLabel() + " " + op.opName)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(2450 + sourcePattern.getScore() / 10)
        .exactSemantic(sourcePattern.isExactSemantic())
        .children(List.of(sourcePattern))
        .metadata("terminal", op.opName)
        .metadata("desiredExists", desiredExists)
        .metadata("sourceKind", sourcePattern.getKind().name());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addComparePattern(
      PatternGraph graph,
      ASTBinary binary,
      PatternInstance source,
      PatternInstance threshold,
      boolean sizeOnLeft,
      Optional<Boolean> desiredExists,
      int passNumber
  ) {
    MultiPassPatternKind kind = source.getKind() == MultiPassPatternKind.ALL_INSTANCES_SOURCE
        ? MultiPassPatternKind.ALL_INSTANCES_SIZE_COMPARE
        : MultiPassPatternKind.NAVIGATION_SIZE_COMPARE;
    int baseScore = desiredExists.isPresent() ? 2520 : 2420;
    PatternInstance.Builder builder = PatternInstance.builder(kind, binary)
        .label(source.getLabel() + ".size " + binary.op)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(baseScore + source.getScore() / 10 + threshold.getScore() / 10)
        .exactSemantic(source.isExactSemantic() && threshold.isExactSemantic())
        .children(List.of(source, threshold))
        .metadata("operator", binary.op)
        .metadata("sizeOnLeft", sizeOnLeft)
        .metadata("sourceKind", source.getKind().name());
    desiredExists.ifPresent(value -> builder.metadata("desiredExists", value));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private Optional<PatternInstance> sizedSource(PatternGraph graph, ASTNode node) {
    if (!(node instanceof ASTCollectionOp collectionOp) || !"size".equalsIgnoreCase(collectionOp.opName)) {
      return Optional.empty();
    }
    return collectionSource(graph, collectionOp.source);
  }

  private Optional<PatternInstance> collectionSource(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getKind() == MultiPassPatternKind.ALL_INSTANCES_SOURCE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  private Optional<PatternInstance> bestValueLike(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.VALUE
            || pattern.getOutputKind() == ExpressionKind.NODE
            || pattern.getKind() == MultiPassPatternKind.LITERAL
            || pattern.getKind() == MultiPassPatternKind.VARIABLE
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_ACCESS
            || pattern.getKind() == MultiPassPatternKind.QUALIFIED_NAVIGATION
            || pattern.getKind() == MultiPassPatternKind.BOOLEAN_ATTRIBUTE_ACCESS
            || pattern.getKind() == MultiPassPatternKind.IMPLICIT_ITERATOR_PROPERTY_ACCESS
            || pattern.getKind() == MultiPassPatternKind.IMPLICIT_SELF_PROPERTY_ACCESS
            || pattern.getKind() == MultiPassPatternKind.AGGREGATE_FUNCTION
            || pattern.getKind() == MultiPassPatternKind.AS_SET_SIZE_DISTINCT_COUNT
            || pattern.getKind() == MultiPassPatternKind.DATE_TIME_OPERATION_MAPPING
            || pattern.getKind() == MultiPassPatternKind.CYPHER_CANDIDATE
            || pattern.getKind() == MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  private boolean isEmptyTerminal(String opName) {
    return "isEmpty".equalsIgnoreCase(opName)
        || "isNotEmpty".equalsIgnoreCase(opName)
        || "notEmpty".equalsIgnoreCase(opName);
  }

  private Optional<Boolean> desiredExists(String operator, boolean sizeOnLeft, Optional<Double> threshold) {
    if (threshold.isEmpty()) return Optional.empty();
    double value = threshold.get();
    if (sizeOnLeft) {
      if (">".equals(operator) && is(value, 0)) return Optional.of(true);
      if (">=".equals(operator) && is(value, 1)) return Optional.of(true);
      if ("<>".equals(operator) && is(value, 0)) return Optional.of(true);
      if ("=".equals(operator) && is(value, 0)) return Optional.of(false);
      if ("<".equals(operator) && is(value, 1)) return Optional.of(false);
      if ("<=".equals(operator) && is(value, 0)) return Optional.of(false);
      return Optional.empty();
    }

    if ("<".equals(operator) && is(value, 0)) return Optional.of(true);
    if ("<=".equals(operator) && is(value, 1)) return Optional.of(true);
    if ("<>".equals(operator) && is(value, 0)) return Optional.of(true);
    if ("=".equals(operator) && is(value, 0)) return Optional.of(false);
    if (">".equals(operator) && is(value, 1)) return Optional.of(false);
    if (">=".equals(operator) && is(value, 0)) return Optional.of(false);
    return Optional.empty();
  }

  private Optional<Double> literalNumber(ASTNode node) {
    if (node instanceof ASTIntegerLiteral literal) return Optional.of((double) literal.value);
    if (node instanceof ASTRealLiteral literal) return Optional.of(literal.value);
    return Optional.empty();
  }

  private boolean is(double actual, double expected) {
    return Math.abs(actual - expected) < 0.0000001;
  }
}
