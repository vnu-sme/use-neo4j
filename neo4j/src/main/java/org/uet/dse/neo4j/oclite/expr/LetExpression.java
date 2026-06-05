package org.uet.dse.neo4j.oclite.expr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LetExpression implements ExpressionNode {
  public static class Binding {
    public final String name;
    public final ExpressionNode value;

    public Binding(String name, ExpressionNode value) {
      this.name = name;
      this.value = value;
    }
  }

  private final List<Binding> bindings;
  private final ExpressionNode body;

  public LetExpression(List<Binding> bindings, ExpressionNode body) {
    this.bindings = List.copyOf(bindings);
    this.body = body;
  }

  @Override
  public Object evaluate(ExecutionContext ctx) {
    Map<String, Object> scope = new LinkedHashMap<>();
    for (Binding binding : bindings) {
      ctx.pushScope(scope);
      Object value = binding.value.evaluate(ctx);
      ctx.popScope();
      scope.put(binding.name, value);
    }

    ctx.pushScope(scope);
    try {
      return body.evaluate(ctx);
    } finally {
      ctx.popScope();
    }
  }
}
