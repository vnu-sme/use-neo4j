package org.uet.dse.neo4j.oclite.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CollectionLiteralExpression implements ExpressionNode {
  private final String kind;
  private final List<ExpressionNode> items;

  public CollectionLiteralExpression(String kind, List<ExpressionNode> items) {
    this.kind = kind;
    this.items = List.copyOf(items);
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    List<Object> result = new ArrayList<>();
    for (ExpressionNode item : items) {
      Object value = item.evaluate(ctx);
      if (value instanceof Collection<?>) {
        for (Object nested : (Collection<?>) value) {
          addValue(result, nested, ctx);
        }
      } else {
        addValue(result, value, ctx);
      }
    }
    return result;
  }

  private void addValue(List<Object> result, Object value, ExecutionContext ctx) {
    if ("Set".equals(kind) || "OrderedSet".equals(kind)) {
      if (result.stream().noneMatch(existing -> ctx.isSameObject(existing, value))) {
        result.add(value);
      }
      return;
    }
    result.add(value);
  }
}
