package org.uet.dse.neo4j.oclite.expr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class QueryExpression implements ExpressionNode {
  private final ExpressionNode source;
  private final String operation;
  private final List<String> variableNames;
  private final ExpressionNode body;

  public QueryExpression(ExpressionNode source, String operation, List<String> variableNames, ExpressionNode body) {
    this.source = source;
    this.operation = operation;
    this.variableNames = List.copyOf(variableNames);
    this.body = body;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Collection<?> collection = ctx.convertToCollection(source.evaluate(ctx));
    String op = operation.toLowerCase();

    return switch (op) {
      case "select" -> filter(ctx, collection, value -> value);
      case "reject" -> filter(ctx, collection, value -> !value);
      case "forall" -> evaluateForAll(ctx, collection);
      case "exists" -> evaluateExists(ctx, collection);
      case "one" -> evaluateOne(ctx, collection);
      case "any" -> evaluateAny(ctx, collection);
      case "collect" -> evaluateCollect(ctx, collection, false);
      case "collectnested" -> evaluateCollect(ctx, collection, true);
      case "isunique" -> evaluateIsUnique(ctx, collection);
      case "sortedby" -> evaluateSortedBy(ctx, collection);
      case "closure" -> evaluateClosure(ctx, collection);
      default -> throw new RuntimeException("Chưa hỗ trợ Query: " + operation);
    };
  }

  private List<Object> filter(ExecutionContext ctx, Collection<?> collection, Predicate<Boolean> predicate) {
    requireUnary();
    String var = iteratorName();
    List<Object> result = new ArrayList<>();
    for (Object item : collection) {
      ctx.pushScope(var, item);
      Object value = body.evaluate(ctx);
      ctx.popScope();
      if (value instanceof Boolean && predicate.test((Boolean) value)) {
        result.add(item);
      }
    }
    return result;
  }

  private boolean evaluateForAll(ExecutionContext ctx, Collection<?> collection) {
    if (collection.isEmpty()) return true;
    return everyCombination(ctx, collection, values -> {
      Object result = body.evaluate(ctx);
      return result instanceof Boolean && (Boolean) result;
    });
  }

  private boolean evaluateExists(ExecutionContext ctx, Collection<?> collection) {
    return anyCombination(ctx, collection, values -> {
      Object result = body.evaluate(ctx);
      return result instanceof Boolean && (Boolean) result;
    });
  }

  private boolean evaluateOne(ExecutionContext ctx, Collection<?> collection) {
    final int[] count = {0};
    anyCombination(ctx, collection, values -> {
      Object result = body.evaluate(ctx);
      if (result instanceof Boolean && (Boolean) result) {
        count[0]++;
      }
      return count[0] > 1;
    });
    return count[0] == 1;
  }

  private Object evaluateAny(ExecutionContext ctx, Collection<?> collection) {
    requireUnary();
    String var = iteratorName();
    for (Object item : collection) {
      ctx.pushScope(var, item);
      Object result = body.evaluate(ctx);
      ctx.popScope();
      if (result instanceof Boolean && (Boolean) result) {
        return item;
      }
    }
    return null;
  }

  private List<Object> evaluateCollect(ExecutionContext ctx, Collection<?> collection, boolean flatten) {
    requireUnary();
    String var = iteratorName();
    List<Object> result = new ArrayList<>();
    for (Object item : collection) {
      ctx.pushScope(var, item);
      Object value = body.evaluate(ctx);
      ctx.popScope();
      if (value != null) result.add(value);
    }
    return flatten ? ctx.flatten(result) : result;
  }

  private boolean evaluateIsUnique(ExecutionContext ctx, Collection<?> collection) {
    requireUnary();
    String var = iteratorName();
    List<Object> seen = new ArrayList<>();
    for (Object item : collection) {
      ctx.pushScope(var, item);
      Object value = body.evaluate(ctx);
      ctx.popScope();
      if (seen.stream().anyMatch(existing -> ctx.isSameObject(existing, value))) {
        return false;
      }
      seen.add(value);
    }
    return true;
  }

  private List<Object> evaluateSortedBy(ExecutionContext ctx, Collection<?> collection) {
    requireUnary();
    String var = iteratorName();
    List<Object> result = new ArrayList<>(collection);
    result.sort((left, right) -> compareKeys(comparableKey(ctx, var, left), comparableKey(ctx, var, right)));
    return result;
  }

  private List<Object> evaluateClosure(ExecutionContext ctx, Collection<?> collection) {
    requireUnary();
    String var = iteratorName();
    Set<Object> result = new LinkedHashSet<>();
    Deque<Object> queue = new ArrayDeque<>(collection);
    while (!queue.isEmpty()) {
      Object item = queue.removeFirst();
      if (!result.add(item)) continue;

      ctx.pushScope(var, item);
      Object next = body.evaluate(ctx);
      ctx.popScope();

      for (Object related : ctx.convertToCollection(next)) {
        if (!containsSame(result, related, ctx)) {
          queue.addLast(related);
        }
      }
    }
    return new ArrayList<>(result);
  }

  private Comparable comparableKey(ExecutionContext ctx, String var, Object item) {
    ctx.pushScope(var, item);
    Object value = body.evaluate(ctx);
    ctx.popScope();
    if (value instanceof Comparable<?>) {
      return (Comparable) value;
    }
    return value == null ? null : value.toString();
  }

  private boolean everyCombination(ExecutionContext ctx, Collection<?> collection, Predicate<Map<String, Object>> predicate) {
    if (variableNames.isEmpty() || variableNames.size() == 1) {
      String var = iteratorName();
      for (Object item : collection) {
        ctx.pushScope(var, item);
        boolean ok = predicate.test(singleScope(var, item));
        ctx.popScope();
        if (!ok) return false;
      }
      return true;
    }
    return visitCombinations(ctx, new ArrayList<>(collection), 0, new LinkedHashMap<>(), predicate, false);
  }

  private boolean anyCombination(ExecutionContext ctx, Collection<?> collection, Predicate<Map<String, Object>> predicate) {
    if (variableNames.isEmpty() || variableNames.size() == 1) {
      String var = iteratorName();
      for (Object item : collection) {
        ctx.pushScope(var, item);
        boolean ok = predicate.test(singleScope(var, item));
        ctx.popScope();
        if (ok) return true;
      }
      return false;
    }
    return visitCombinations(ctx, new ArrayList<>(collection), 0, new LinkedHashMap<>(), predicate, true);
  }

  private boolean visitCombinations(
      ExecutionContext ctx,
      List<?> collection,
      int depth,
      LinkedHashMap<String, Object> scope,
      Predicate<Map<String, Object>> predicate,
      boolean stopOnTrue
  ) {
    if (depth == variableNames.size()) {
      ctx.pushScope(scope);
      boolean result = predicate.test(scope);
      ctx.popScope();
      return result;
    }

    String var = variableNames.get(depth);
    for (Object item : collection) {
      scope.put(var, item);
      boolean matched = visitCombinations(ctx, collection, depth + 1, scope, predicate, stopOnTrue);
      scope.remove(var);
      if (stopOnTrue && matched) return true;
      if (!stopOnTrue && !matched) return false;
    }
    return !stopOnTrue;
  }

  private int compareKeys(Comparable left, Comparable right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    return left.compareTo(right);
  }

  private boolean containsSame(Collection<?> values, Object target, ExecutionContext ctx) {
    return values.stream().anyMatch(value -> ctx.isSameObject(value, target));
  }

  private Map<String, Object> singleScope(String var, Object value) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put(var, value);
    return scope;
  }

  private String iteratorName() {
    return variableNames.isEmpty() ? "it" : variableNames.get(0);
  }

  private void requireUnary() {
    if (variableNames.size() > 1) {
      throw new RuntimeException(operation + " chỉ hỗ trợ một iterator variable trong fallback Java.");
    }
  }
}
