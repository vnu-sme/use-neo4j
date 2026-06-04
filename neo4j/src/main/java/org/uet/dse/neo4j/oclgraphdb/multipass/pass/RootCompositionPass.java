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
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class RootCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "root-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    ASTNode root = graph.getRoot();

    if (root instanceof ASTContext astContext) {
      Optional<PatternInstance> expression = bestBooleanPattern(graph, astContext.expression);
      if (expression.isPresent()) {
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.CONTEXT_INVARIANT, root)
            .label(astContext.className + "::" + astContext.invName)
            .outputKind(ExpressionKind.BOOLEAN)
            .score(2600 + expression.get().getScore() / 10)
            .exactSemantic(expression.get().isExactSemantic())
            .children(List.of(expression.get()))
            .metadata("class", astContext.className)
            .metadata("invariant", astContext.invName);
        if (graph.add(builder, passNumber).isPresent()) added++;
      }
      return added;
    }

    Optional<PatternInstance> rootPattern = graph.patternsFor(root).stream()
        .filter(pattern -> pattern.getKind() != MultiPassPatternKind.SCALAR_QUERY)
        .filter(pattern -> pattern.getKind() != MultiPassPatternKind.CONTEXT_INVARIANT)
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
    if (rootPattern.isPresent()) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.SCALAR_QUERY, root)
          .label(AstPatternSupport.labelOf(root))
          .outputKind(rootPattern.get().getOutputKind())
          .score(2500 + rootPattern.get().getScore() / 10)
          .exactSemantic(rootPattern.get().isExactSemantic())
          .children(List.of(rootPattern.get()));
      if (graph.add(builder, passNumber).isPresent()) added++;
    }
    return added;
  }

  private Optional<PatternInstance> bestBooleanPattern(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.BOOLEAN
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE
            || pattern.getKind() == MultiPassPatternKind.COMPARE
            || pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION
            || pattern.getKind() == MultiPassPatternKind.NEGATION
            || pattern.getKind() == MultiPassPatternKind.QUANTIFIER)
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
  }
}
