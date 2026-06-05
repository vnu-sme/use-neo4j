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
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class CollectionSourcePass implements PatternPass {
  @Override
  public String name() {
    return "collection-source";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      added += addAllInstancesSource(graph, node, context, passNumber);
      added += addNavigationSource(graph, node, passNumber);
    }
    return added;
  }

  private int addAllInstancesSource(
      PatternGraph graph,
      ASTNode node,
      MultiPassPatternContext context,
      int passNumber
  ) {
    Optional<String> className = AstPatternSupport.allInstancesClassName(node, context.compileContext());
    if (className.isEmpty()) return 0;
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.ALL_INSTANCES_SOURCE, node)
        .label(className.get() + ".allInstances")
        .outputKind(ExpressionKind.COLLECTION)
        .score(1650)
        .exactSemantic(true)
        .metadata("class", className.get());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addNavigationSource(PatternGraph graph, ASTNode node, int passNumber) {
    Optional<PatternInstance> navigation = graph.patternsFor(node).stream()
        .filter(this::isCollectionNavigation)
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
    if (navigation.isEmpty()) return 0;

    String role = navigation.get().getMetadata().getOrDefault("role", navigation.get().getMetadata().getOrDefault("property", ""));
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.NAVIGATION_SOURCE, node)
        .label(role)
        .outputKind(ExpressionKind.COLLECTION)
        .score(1650 + navigation.get().getScore() / 10)
        .exactSemantic(navigation.get().isExactSemantic())
        .children(List.of(navigation.get()))
        .metadata("role", role)
        .metadata("targetClass", navigation.get().getMetadata().getOrDefault("targetClass", ""));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private boolean isCollectionNavigation(PatternInstance pattern) {
    if (pattern.getKind() == MultiPassPatternKind.NAVIGATION_ACCESS
        || pattern.getKind() == MultiPassPatternKind.QUALIFIED_NAVIGATION) {
      return true;
    }
    return (pattern.getKind() == MultiPassPatternKind.IMPLICIT_ITERATOR_PROPERTY_ACCESS
        || pattern.getKind() == MultiPassPatternKind.IMPLICIT_SELF_PROPERTY_ACCESS)
        && "navigation".equals(pattern.getMetadata().get("propertyKind"));
  }
}
