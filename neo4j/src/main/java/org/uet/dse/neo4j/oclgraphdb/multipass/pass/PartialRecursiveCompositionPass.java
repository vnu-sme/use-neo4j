package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

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
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTNot;
import org.uet.dse.neo4j.oclite.ast.ASTUnary;

public class PartialRecursiveCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "partial-recursive-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTBinary binary) {
        added += addBinary(graph, binary, passNumber);
      } else if (node instanceof ASTCollectionOp collectionOp) {
        added += addCollectionOperation(graph, collectionOp, passNumber);
      } else if (node instanceof ASTUnary unary) {
        added += addUnary(graph, unary, passNumber);
      } else if (node instanceof ASTNot not) {
        added += addNot(graph, not, passNumber);
      }
    }
    return added;
  }

  private int addBinary(PatternGraph graph, ASTBinary binary, int passNumber) {
    ExpressionKind outputKind = outputKind(binary.op);
    if (outputKind == null) return 0;

    ExpectedShape childShape = AstPatternSupport.isLogical(binary.op)
        ? ExpectedShape.BOOLEAN
        : comparisonChildShape(binary.op);
    Optional<PatternInstance> left = best(graph, binary.left, childShape);
    Optional<PatternInstance> right = best(graph, binary.right, childShape);
    if (left.isEmpty() || right.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION, binary)
        .label("partial " + binary.op)
        .outputKind(outputKind)
        .score(1280 + left.get().getScore() / 20 + right.get().getScore() / 20)
        .exactSemantic(left.get().isExactSemantic() && right.get().isExactSemantic())
        .children(List.of(left.get(), right.get()))
        .metadata("nodeType", "binary")
        .metadata("operator", binary.op);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addCollectionOperation(PatternGraph graph, ASTCollectionOp collectionOp, int passNumber) {
    ExpressionKind outputKind = collectionOutputKind(collectionOp.opName);
    if (outputKind == null) return 0;
    Optional<PatternInstance> source = best(graph, collectionOp.source, ExpectedShape.COLLECTION);
    if (source.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION, collectionOp)
        .label("partial ->" + collectionOp.opName + "()")
        .outputKind(outputKind)
        .score(1260 + source.get().getScore() / 20)
        .exactSemantic(source.get().isExactSemantic())
        .children(List.of(source.get()))
        .metadata("nodeType", "collection")
        .metadata("operation", collectionOp.opName);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addUnary(PatternGraph graph, ASTUnary unary, int passNumber) {
    ExpectedShape shape = "not".equals(unary.operator) ? ExpectedShape.BOOLEAN : ExpectedShape.VALUE;
    Optional<PatternInstance> child = best(graph, unary.expression, shape);
    if (child.isEmpty()) return 0;

    ExpressionKind outputKind = "not".equals(unary.operator) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION, unary)
        .label("partial " + unary.operator)
        .outputKind(outputKind)
        .score(1240 + child.get().getScore() / 20)
        .exactSemantic(child.get().isExactSemantic())
        .children(List.of(child.get()))
        .metadata("nodeType", "unary")
        .metadata("operator", unary.operator);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addNot(PatternGraph graph, ASTNot not, int passNumber) {
    Optional<PatternInstance> child = best(graph, not.expression, ExpectedShape.BOOLEAN);
    if (child.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION, not)
        .label("partial not")
        .outputKind(ExpressionKind.BOOLEAN)
        .score(1240 + child.get().getScore() / 20)
        .exactSemantic(child.get().isExactSemantic())
        .children(List.of(child.get()))
        .metadata("nodeType", "not")
        .metadata("operator", "not");
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private Optional<PatternInstance> best(PatternGraph graph, ASTNode node, ExpectedShape shape) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getKind() != MultiPassPatternKind.SCALAR_QUERY)
        .filter(pattern -> pattern.getKind() != MultiPassPatternKind.CONTEXT_INVARIANT)
        .filter(pattern -> accepts(shape, pattern))
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
  }

  private boolean accepts(ExpectedShape shape, PatternInstance pattern) {
    ExpressionKind outputKind = pattern.getOutputKind();
    if (shape == ExpectedShape.ANY) {
      return outputKind != null || pattern.getKind() == MultiPassPatternKind.CYPHER_CANDIDATE;
    }
    if (shape == ExpectedShape.BOOLEAN) {
      return outputKind == ExpressionKind.BOOLEAN
          || pattern.getKind() == MultiPassPatternKind.COMPARE
          || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
          || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE
          || pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION
          || pattern.getKind() == MultiPassPatternKind.LOGICAL_WITH_BOOLEAN_ATTRIBUTE
          || pattern.getKind() == MultiPassPatternKind.NEGATION
          || pattern.getKind() == MultiPassPatternKind.FILTERED_EXISTENCE_COMPARE
          || pattern.getKind() == MultiPassPatternKind.FILTERED_SIZE_COMPARE
          || pattern.getKind() == MultiPassPatternKind.MULTI_VAR_QUANTIFIER_OPTIMIZED
          || pattern.getKind() == MultiPassPatternKind.IS_UNIQUE_GROUP_BY
          || pattern.getKind() == MultiPassPatternKind.INCLUDES_ALL_ANTI_JOIN;
    }
    if (shape == ExpectedShape.COLLECTION) {
      return outputKind == ExpressionKind.COLLECTION;
    }
    return outputKind == ExpressionKind.VALUE
        || outputKind == ExpressionKind.NODE
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
        || pattern.getKind() == MultiPassPatternKind.CYPHER_CANDIDATE;
  }

  private ExpressionKind outputKind(String operator) {
    if (AstPatternSupport.isLogical(operator) || AstPatternSupport.isComparison(operator)) return ExpressionKind.BOOLEAN;
    return switch (operator) {
      case "+", "-", "*", "/", "div" -> ExpressionKind.VALUE;
      default -> null;
    };
  }

  private ExpectedShape comparisonChildShape(String operator) {
    if ("=".equals(operator) || "<>".equals(operator)) return ExpectedShape.ANY;
    return AstPatternSupport.isComparison(operator) ? ExpectedShape.VALUE : ExpectedShape.VALUE;
  }

  private ExpressionKind collectionOutputKind(String operation) {
    return switch (operation) {
      case "size" -> ExpressionKind.VALUE;
      case "isEmpty", "isNotEmpty", "notEmpty" -> ExpressionKind.BOOLEAN;
      default -> null;
    };
  }

  private enum ExpectedShape {
    ANY,
    VALUE,
    BOOLEAN,
    COLLECTION
  }
}
