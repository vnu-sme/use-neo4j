package org.uet.dse.neo4j.oclite.expr;

public class IfExpression implements ExpressionNode {
  private final ExpressionNode condition;
  private final ExpressionNode thenExpression;
  private final ExpressionNode elseExpression;

  public IfExpression(ExpressionNode condition, ExpressionNode thenExpression, ExpressionNode elseExpression) {
    this.condition = condition;
    this.thenExpression = thenExpression;
    this.elseExpression = elseExpression;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Object value = condition.evaluate(ctx);
    if (value instanceof Boolean && (Boolean) value) {
      return thenExpression.evaluate(ctx);
    }
    return elseExpression.evaluate(ctx);
  }
}
