package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

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

public class FilteredCompareCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "filtered-compare-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTBinary binary && AstPatternSupport.isComparison(binary.op)) {
        added += addFilteredCompare(graph, binary, passNumber);
      } else if (node instanceof ASTCollectionOp collectionOp) {
        added += addTerminalExistence(graph, collectionOp, passNumber);
      }
    }
    return added;
  }

  private int addFilteredCompare(PatternGraph graph, ASTBinary binary, int passNumber) {
    Optional<PatternInstance> leftSize = filteredSizeTerminal(graph, binary.left);
    Optional<PatternInstance> rightSize = filteredSizeTerminal(graph, binary.right);

    if (leftSize.isPresent()) {
      Optional<PatternInstance> threshold = bestValueLike(graph, binary.right);
      if (threshold.isEmpty()) return 0;
      Optional<Boolean> desiredExists = desiredExists(binary.op, true, literalNumber(binary.right));
      return addComparePattern(graph, binary, leftSize.get(), threshold.get(), true, desiredExists, passNumber);
    }

    if (rightSize.isPresent()) {
      Optional<PatternInstance> threshold = bestValueLike(graph, binary.left);
      if (threshold.isEmpty()) return 0;
      Optional<Boolean> desiredExists = desiredExists(binary.op, false, literalNumber(binary.left));
      return addComparePattern(graph, binary, rightSize.get(), threshold.get(), false, desiredExists, passNumber);
    }

    return 0;
  }

  private int addTerminalExistence(PatternGraph graph, ASTCollectionOp terminal, int passNumber) {
    if (!"isEmpty".equals(terminal.opName) && !"isNotEmpty".equals(terminal.opName) && !"notEmpty".equals(terminal.opName)) {
      return 0;
    }
    Optional<PatternInstance> filteredTerminal = graph.bestFor(terminal, MultiPassPatternKind.FILTERED_TERMINAL);
    if (filteredTerminal.isEmpty() || filteredTerminal.get().getChildren().isEmpty()) return 0;
    PatternInstance filter = filteredTerminal.get().getChildren().get(0);
    if (filter.getKind() != MultiPassPatternKind.FILTER) return 0;

    boolean desiredExists = !"isEmpty".equals(terminal.opName);
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.FILTERED_EXISTENCE_COMPARE, terminal)
        .label("filtered " + terminal.opName)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(2520 + filteredTerminal.get().getScore() / 10)
        .exactSemantic(filteredTerminal.get().isExactSemantic())
        .children(java.util.List.of(filteredTerminal.get()))
        .metadata("terminal", terminal.opName)
        .metadata("desiredExists", desiredExists)
        .metadata("source", "terminal");
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addComparePattern(
      PatternGraph graph,
      ASTBinary binary,
      PatternInstance filteredSize,
      PatternInstance threshold,
      boolean sizeOnLeft,
      Optional<Boolean> desiredExists,
      int passNumber
  ) {
    MultiPassPatternKind kind = desiredExists.isPresent()
        ? MultiPassPatternKind.FILTERED_EXISTENCE_COMPARE
        : MultiPassPatternKind.FILTERED_SIZE_COMPARE;
    int baseScore = desiredExists.isPresent() ? 2650 : 2550;
    PatternInstance.Builder builder = PatternInstance.builder(kind, binary)
        .label("filtered size " + binary.op)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(baseScore + filteredSize.getScore() / 10 + threshold.getScore() / 10)
        .exactSemantic(filteredSize.isExactSemantic() && threshold.isExactSemantic())
        .children(java.util.List.of(filteredSize, threshold))
        .metadata("operator", binary.op)
        .metadata("sizeOnLeft", sizeOnLeft)
        .metadata("source", "compare");
    desiredExists.ifPresent(value -> builder.metadata("desiredExists", value));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private Optional<PatternInstance> filteredSizeTerminal(PatternGraph graph, ASTNode node) {
    if (!(node instanceof ASTCollectionOp terminal) || !"size".equals(terminal.opName)) return Optional.empty();
    return graph.bestFor(node, MultiPassPatternKind.FILTERED_TERMINAL)
        .filter(pattern -> !pattern.getChildren().isEmpty())
        .filter(pattern -> pattern.getChildren().get(0).getKind() == MultiPassPatternKind.FILTER);
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
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
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
