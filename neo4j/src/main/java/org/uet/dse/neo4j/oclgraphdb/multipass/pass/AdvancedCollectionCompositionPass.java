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
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;

public class AdvancedCollectionCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "advanced-collection-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTQuery query) {
        added += addIsUnique(graph, query, passNumber);
        added += addMultiVarQuantifier(graph, query, passNumber);
      } else if (node instanceof ASTCollectionOp op) {
        added += addAggregate(graph, op, passNumber);
        added += addAsSetSize(graph, op, passNumber);
        added += addIncludesAll(graph, op, passNumber);
      }
    }
    return added;
  }

  private int addIsUnique(PatternGraph graph, ASTQuery query, int passNumber) {
    if (!"isunique".equalsIgnoreCase(query.operation) || query.variables.size() > 1) return 0;
    Optional<PatternInstance> source = collectionSource(graph, query.source);
    if (source.isEmpty()) return 0;
    Optional<PatternInstance> body = bestValueLike(graph, query.body);

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.IS_UNIQUE_GROUP_BY, query)
        .label("isUnique group-by")
        .outputKind(ExpressionKind.BOOLEAN)
        .score(2720 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
        .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(true))
        .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
        .metadata("operation", query.operation)
        .metadata("iterator", query.primaryVariableName());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addMultiVarQuantifier(PatternGraph graph, ASTQuery query, int passNumber) {
    if (query.variables.size() <= 1 || !isQuantifier(query.operation)) return 0;
    Optional<PatternInstance> source = collectionSource(graph, query.source);
    if (source.isEmpty()) return 0;
    Optional<PatternInstance> body = bestBooleanLike(graph, query.body);

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.MULTI_VAR_QUANTIFIER_OPTIMIZED, query)
        .label(query.operation + " multi-var")
        .outputKind("any".equalsIgnoreCase(query.operation) ? ExpressionKind.NODE : ExpressionKind.BOOLEAN)
        .score(2750 + source.get().getScore() / 10 + body.map(p -> p.getScore() / 10).orElse(0))
        .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(true))
        .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
        .metadata("operation", query.operation)
        .metadata("variables", query.variables.stream().map(v -> v.name).reduce((a, b) -> a + "," + b).orElse(""));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addAggregate(PatternGraph graph, ASTCollectionOp op, int passNumber) {
    if (!isAggregate(op.opName)) return 0;

    if (op.source instanceof ASTQuery query && isCollect(query.operation)) {
      Optional<PatternInstance> source = collectionSource(graph, query.source);
      if (source.isEmpty()) return 0;
      Optional<PatternInstance> body = bestValueLike(graph, query.body);
      PatternInstance.Builder builder = aggregateBuilder(op, source.get(), passNumber)
          .children(body.map(pattern -> List.of(source.get(), pattern)).orElseGet(() -> List.of(source.get())))
          .metadata("mode", "collect")
          .metadata("iterator", query.primaryVariableName())
          .exactSemantic(source.get().isExactSemantic() && body.map(PatternInstance::isExactSemantic).orElse(true));
      return graph.add(builder, passNumber).isPresent() ? 1 : 0;
    }

    Optional<PatternInstance> source = collectionSource(graph, op.source);
    if (source.isEmpty()) return 0;
    PatternInstance.Builder builder = aggregateBuilder(op, source.get(), passNumber)
        .children(List.of(source.get()))
        .metadata("mode", "direct")
        .exactSemantic(source.get().isExactSemantic());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addAsSetSize(PatternGraph graph, ASTCollectionOp op, int passNumber) {
    if (!"size".equalsIgnoreCase(op.opName) || !(op.source instanceof ASTCollectionOp setOp)) return 0;
    if (!"asSet".equalsIgnoreCase(setOp.opName) && !"asOrderedSet".equalsIgnoreCase(setOp.opName)) return 0;
    Optional<PatternInstance> source = collectionSource(graph, setOp.source);
    if (source.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.AS_SET_SIZE_DISTINCT_COUNT, op)
        .label("asSet size distinct")
        .outputKind(ExpressionKind.VALUE)
        .score(2680 + source.get().getScore() / 10)
        .exactSemantic(source.get().isExactSemantic())
        .children(List.of(source.get()))
        .metadata("operation", setOp.opName);
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private int addIncludesAll(PatternGraph graph, ASTCollectionOp op, int passNumber) {
    if (!"includesAll".equalsIgnoreCase(op.opName) && !"excludesAll".equalsIgnoreCase(op.opName)) return 0;
    if (op.args.size() != 1) return 0;
    Optional<PatternInstance> source = collectionSource(graph, op.source);
    if (source.isEmpty()) return 0;
    Optional<PatternInstance> target = collectionSource(graph, op.args.get(0));

    List<PatternInstance> children = target
        .map(pattern -> List.of(source.get(), pattern))
        .orElseGet(() -> List.of(source.get()));
    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.INCLUDES_ALL_ANTI_JOIN, op)
        .label(op.opName + " anti-join")
        .outputKind(ExpressionKind.BOOLEAN)
        .score(2620 + source.get().getScore() / 10 + target.map(p -> p.getScore() / 10).orElse(0))
        .exactSemantic(source.get().isExactSemantic() && target.map(PatternInstance::isExactSemantic).orElse(true))
        .children(children)
        .metadata("operation", op.opName)
        .metadata("targetPattern", target.isPresent());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private PatternInstance.Builder aggregateBuilder(ASTCollectionOp op, PatternInstance source, int passNumber) {
    return PatternInstance.builder(MultiPassPatternKind.AGGREGATE_FUNCTION, op)
        .label(op.opName)
        .outputKind(ExpressionKind.VALUE)
        .score(2600 + source.getScore() / 10)
        .metadata("aggregate", op.opName);
  }

  private Optional<PatternInstance> collectionSource(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getKind() == MultiPassPatternKind.ALL_INSTANCES_SOURCE
            || pattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE
            || pattern.getKind() == MultiPassPatternKind.FILTER)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  private Optional<PatternInstance> bestValueLike(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.VALUE
            || pattern.getOutputKind() == ExpressionKind.NODE
            || pattern.getKind() == MultiPassPatternKind.LITERAL
            || pattern.getKind() == MultiPassPatternKind.VARIABLE
            || pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_ACCESS
            || pattern.getKind() == MultiPassPatternKind.BOOLEAN_ATTRIBUTE_ACCESS
            || pattern.getKind() == MultiPassPatternKind.IMPLICIT_ITERATOR_PROPERTY_ACCESS
            || pattern.getKind() == MultiPassPatternKind.IMPLICIT_SELF_PROPERTY_ACCESS
            || pattern.getKind() == MultiPassPatternKind.DATE_TIME_OPERATION_MAPPING
            || pattern.getKind() == MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  private Optional<PatternInstance> bestBooleanLike(PatternGraph graph, ASTNode node) {
    return graph.patternsFor(node).stream()
        .filter(pattern -> pattern.getOutputKind() == ExpressionKind.BOOLEAN)
        .max(Comparator.comparingInt(PatternInstance::getScore));
  }

  private boolean isQuantifier(String operation) {
    return "forall".equalsIgnoreCase(operation)
        || "exists".equalsIgnoreCase(operation)
        || "one".equalsIgnoreCase(operation);
  }

  private boolean isCollect(String operation) {
    return "collect".equalsIgnoreCase(operation) || "collectnested".equalsIgnoreCase(operation);
  }

  private boolean isAggregate(String operation) {
    return "sum".equalsIgnoreCase(operation)
        || "avg".equalsIgnoreCase(operation)
        || "average".equalsIgnoreCase(operation)
        || "min".equalsIgnoreCase(operation)
        || "max".equalsIgnoreCase(operation);
  }
}
