package org.uet.dse.neo4j.oclite.expr;

import java.util.ArrayList;
import java.util.List;

public class RangeExpression implements ExpressionNode {
  private final ExpressionNode start;
  private final ExpressionNode end;

  public RangeExpression(ExpressionNode start, ExpressionNode end) {
    this.start = start;
    this.end = end;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Number startNumber = ctx.asNumber(start.evaluate(ctx));
    Number endNumber = ctx.asNumber(end.evaluate(ctx));
    if (startNumber == null || endNumber == null) return List.of();

    int startValue = startNumber.intValue();
    int endValue = endNumber.intValue();
    List<Object> result = new ArrayList<>();
    if (startValue <= endValue) {
      for (int i = startValue; i <= endValue; i++) result.add((double) i);
    } else {
      for (int i = startValue; i >= endValue; i--) result.add((double) i);
    }
    return result;
  }
}
