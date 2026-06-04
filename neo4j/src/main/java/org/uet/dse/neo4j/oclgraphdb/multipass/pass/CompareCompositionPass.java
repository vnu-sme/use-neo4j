package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.ArrayList;
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
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTNot;

public class CompareCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "compare-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (!(node instanceof ASTBinary binary)) continue;

      if (AstPatternSupport.isComparison(binary.op)) {
        added += addComparePattern(graph, binary, passNumber);
      } else if (AstPatternSupport.isLogical(binary.op)) {
        added += addLogicalPattern(graph, binary, passNumber);
      }
    }
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTNot not) {
        added += addNegationPattern(graph, not, passNumber);
      }
    }
    return added;
  }

  private int addComparePattern(PatternGraph graph, ASTBinary binary, int passNumber) {
    Optional<PatternInstance> left = bestValueLike(graph, binary.left);
    Optional<PatternInstance> right = bestValueLike(graph, binary.right);
    if (left.isEmpty() || right.isEmpty()) return 0;

    MultiPassPatternKind kind = MultiPassPatternKind.COMPARE;
    if (left.get().getKind() == MultiPassPatternKind.ATTRIBUTE_ACCESS || right.get().getKind() == MultiPassPatternKind.ATTRIBUTE_ACCESS) {
      kind = MultiPassPatternKind.ATTRIBUTE_COMPARE;
    } else if (left.get().getKind() == MultiPassPatternKind.NAVIGATION_ACCESS || right.get().getKind() == MultiPassPatternKind.NAVIGATION_ACCESS) {
      kind = MultiPassPatternKind.NAVIGATION_COMPARE;
    }

    int score = 1900 + left.get().getScore() / 10 + right.get().getScore() / 10;
    PatternInstance.Builder builder = PatternInstance.builder(kind, binary)
        .label(binary.op)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(score)
        .exactSemantic(left.get().isExactSemantic() && right.get().isExactSemantic())
        .children(List.of(left.get(), right.get()))
        .metadata("operator", binary.op);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addNegationPattern(PatternGraph graph, ASTNot not, int passNumber) {
    Optional<PatternInstance> inner = bestBooleanLike(graph, not.expression);
    if (inner.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.NEGATION, not)
        .label("not")
        .outputKind(ExpressionKind.BOOLEAN)
        .score(1750 + inner.get().getScore() / 10)
        .exactSemantic(inner.get().isExactSemantic())
        .children(List.of(inner.get()));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addLogicalPattern(PatternGraph graph, ASTBinary binary, int passNumber) {
    Optional<PatternInstance> left = bestBooleanLike(graph, binary.left);
    Optional<PatternInstance> right = bestBooleanLike(graph, binary.right);
    if (left.isEmpty() || right.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.LOGICAL_COMPOSITION, binary)
        .label(binary.op)
        .outputKind(ExpressionKind.BOOLEAN)
        .score(1700 + left.get().getScore() / 10 + right.get().getScore() / 10)
        .exactSemantic(left.get().isExactSemantic() && right.get().isExactSemantic())
        .children(List.of(left.get(), right.get()))
        .metadata("operator", binary.op);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private Optional<PatternInstance> bestValueLike(PatternGraph graph, ASTNode node) {
    List<MultiPassPatternKind> preferred = new ArrayList<>();
    preferred.add(MultiPassPatternKind.ATTRIBUTE_ACCESS);
    preferred.add(MultiPassPatternKind.NAVIGATION_ACCESS);
    preferred.add(MultiPassPatternKind.LITERAL);
    preferred.add(MultiPassPatternKind.VARIABLE);
    preferred.add(MultiPassPatternKind.CYPHER_CANDIDATE);
    return graph.patternsFor(node).stream()
        .filter(pattern -> preferred.contains(pattern.getKind()))
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
  }

  private Optional<PatternInstance> bestBooleanLike(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.BOOLEAN
            || pattern.getKind() == MultiPassPatternKind.COMPARE
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE
            || pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION
            || pattern.getKind() == MultiPassPatternKind.NEGATION)
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
  }
}
