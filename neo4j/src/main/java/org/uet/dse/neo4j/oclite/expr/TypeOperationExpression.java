package org.uet.dse.neo4j.oclite.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TypeOperationExpression implements ExpressionNode {
  private final ExpressionNode source;
  private final String typeName;
  private final boolean kindOf;
  private final boolean filterCollection;

  public TypeOperationExpression(ExpressionNode source, String typeName, boolean kindOf, boolean filterCollection) {
    this.source = source;
    this.typeName = typeName;
    this.kindOf = kindOf;
    this.filterCollection = filterCollection;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Object value = source.evaluate(ctx);
    if (filterCollection) {
      Collection<?> collection = ctx.convertToCollection(value);
      List<Object> result = new ArrayList<>();
      for (Object item : collection) {
        if (matches(ctx, item)) {
          result.add(item);
        }
      }
      return result;
    }
    return matches(ctx, value) ? value : null;
  }

  private boolean matches(ExecutionContext ctx, Object value) {
    return kindOf ? ctx.isKindOf(value, typeName) : ctx.isTypeOf(value, typeName);
  }
}
