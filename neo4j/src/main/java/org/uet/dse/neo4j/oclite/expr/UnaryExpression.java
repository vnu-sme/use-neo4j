package org.uet.dse.neo4j.oclite.expr;

public class UnaryExpression implements ExpressionNode {
  private final String operator;
  private final ExpressionNode expression;

  public UnaryExpression(String operator, ExpressionNode expression) {
    this.operator = operator;
    this.expression = expression;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Object value = expression.evaluate(ctx);
    return switch (operator) {
      case "not" -> !(value instanceof Boolean && (Boolean) value);
      case "+" -> ctx.asNumber(value) == null ? null : ctx.asNumber(value).doubleValue();
      case "-" -> ctx.asNumber(value) == null ? null : -ctx.asNumber(value).doubleValue();
      default -> null;
    };
  }
}
