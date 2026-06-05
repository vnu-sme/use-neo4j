package org.uet.dse.neo4j.oclite.expr;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class IterateExpression implements ExpressionNode {
  private final ExpressionNode source;
  private final String iteratorName;
  private final String accumulatorName;
  private final ExpressionNode accumulatorInit;
  private final ExpressionNode body;

  public IterateExpression(
      ExpressionNode source,
      String iteratorName,
      String accumulatorName,
      ExpressionNode accumulatorInit,
      ExpressionNode body
  ) {
    this.source = source;
    this.iteratorName = iteratorName;
    this.accumulatorName = accumulatorName;
    this.accumulatorInit = accumulatorInit;
    this.body = body;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Collection<?> collection = ctx.convertToCollection(source.evaluate(ctx));
    Object accumulator = accumulatorInit.evaluate(ctx);
    for (Object item : collection) {
      Map<String, Object> scope = new LinkedHashMap<>();
      scope.put(iteratorName, item);
      scope.put(accumulatorName, accumulator);
      ctx.pushScope(scope);
      accumulator = body.evaluate(ctx);
      ctx.popScope();
    }
    return accumulator;
  }
}
