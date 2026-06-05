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
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;

public class IteratorCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "iterator-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTIterator iterator) {
        added += addSourcePatterns(graph, iterator.source, context, passNumber);
        added += addIteratorPattern(graph, iterator, context, passNumber);
      } else if (node instanceof ASTQuery query) {
        added += addSourcePatterns(graph, query.source, context, passNumber);
        added += addQueryPattern(graph, query, context, passNumber);
      }
    }
    return added;
  }

  private int addSourcePatterns(
      PatternGraph graph,
      ASTNode source,
      MultiPassPatternContext context,
      int passNumber
  ) {
    int added = 0;
    Optional<String> allInstancesClass = AstPatternSupport.allInstancesClassName(source, context.compileContext());
    if (allInstancesClass.isPresent()) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.ALL_INSTANCES_SOURCE, source)
          .label(allInstancesClass.get() + ".allInstances")
          .outputKind(ExpressionKind.COLLECTION)
          .score(1650)
          .exactSemantic(true)
          .metadata("class", allInstancesClass.get());
      if (graph.add(builder, passNumber).isPresent()) added++;
    }

    if (source instanceof ASTProperty property) {
      Optional<PatternInstance> navigation = graph.bestFor(property, MultiPassPatternKind.NAVIGATION_ACCESS);
      if (navigation.isPresent()) {
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.NAVIGATION_SOURCE, property)
            .label(property.name)
            .outputKind(ExpressionKind.COLLECTION)
            .score(1650)
            .exactSemantic(navigation.get().isExactSemantic())
            .children(List.of(navigation.get()))
            .metadata("role", property.name)
            .metadata("targetClass", navigation.get().getMetadata().getOrDefault("targetClass", ""));
        if (graph.add(builder, passNumber).isPresent()) added++;
      }
    }
    return added;
  }

  private int addIteratorPattern(
      PatternGraph graph,
      ASTIterator iterator,
      MultiPassPatternContext context,
      int passNumber
  ) {
    Optional<PatternInstance> body = bestBooleanPattern(graph, iterator.body);
    Optional<PatternInstance> source = bestIteratorSource(graph, iterator.source);
    if (source.isEmpty()) return 0;

    String operation = iterator.operation.toLowerCase();
    if (AstPatternSupport.isSelectOrReject(operation)) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.FILTER, iterator)
          .label(operation + " " + iterator.iteratorName)
          .outputKind(ExpressionKind.COLLECTION)
          .score(2100 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
          .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(false))
          .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
          .metadata("operation", operation)
          .metadata("iterator", iterator.iteratorName);
      return graph.add(builder, passNumber).isPresent() ? 1 : 0;
    }

    if ("forall".equals(operation) || "exists".equals(operation) || "one".equals(operation) || "any".equals(operation)) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.QUANTIFIER, iterator)
          .label(operation + " " + iterator.iteratorName)
          .outputKind("any".equals(operation) ? ExpressionKind.NODE : ExpressionKind.BOOLEAN)
          .score(2200 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
          .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(false))
          .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
          .metadata("operation", operation)
          .metadata("iterator", iterator.iteratorName);
      return graph.add(builder, passNumber).isPresent() ? 1 : 0;
    }

    return 0;
  }

  private int addQueryPattern(
      PatternGraph graph,
      ASTQuery query,
      MultiPassPatternContext context,
      int passNumber
  ) {
    Optional<PatternInstance> body = bestBooleanPattern(graph, query.body);
    Optional<PatternInstance> source = bestIteratorSource(graph, query.source);
    if (source.isEmpty()) return 0;

    String operation = query.operation.toLowerCase();
    String iteratorName = query.primaryVariableName();
    if (AstPatternSupport.isSelectOrReject(operation)) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.FILTER, query)
          .label(operation + " " + iteratorName)
          .outputKind(ExpressionKind.COLLECTION)
          .score(2100 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
          .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(false))
          .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
          .metadata("operation", operation)
          .metadata("iterator", iteratorName);
      return graph.add(builder, passNumber).isPresent() ? 1 : 0;
    }

    if ("forall".equals(operation) || "exists".equals(operation) || "one".equals(operation) || "any".equals(operation)) {
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.QUANTIFIER, query)
          .label(operation + " " + iteratorName)
          .outputKind("any".equals(operation) ? ExpressionKind.NODE : ExpressionKind.BOOLEAN)
          .score(2200 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
          .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(false))
          .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
          .metadata("operation", operation)
          .metadata("iterator", iteratorName);
      return graph.add(builder, passNumber).isPresent() ? 1 : 0;
    }
    return 0;
  }

  private Optional<PatternInstance> bestIteratorSource(PatternGraph graph, ASTNode source) {
    return graph.bestAny(
        source,
        MultiPassPatternKind.FILTER,
        MultiPassPatternKind.ALL_INSTANCES_SOURCE,
        MultiPassPatternKind.NAVIGATION_SOURCE,
        MultiPassPatternKind.CYPHER_CANDIDATE
    );
  }

  private Optional<PatternInstance> bestBooleanPattern(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.BOOLEAN
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_COMPARE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_COMPARE
            || pattern.getKind() == MultiPassPatternKind.COMPARE
            || pattern.getKind() == MultiPassPatternKind.LOGICAL_COMPOSITION
            || pattern.getKind() == MultiPassPatternKind.NEGATION)
        .max(java.util.Comparator.comparingInt(PatternInstance::getScore));
  }
}
