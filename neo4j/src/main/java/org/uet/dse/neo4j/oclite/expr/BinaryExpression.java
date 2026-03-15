package org.uet.dse.neo4j.oclite.expr;

import java.util.Objects;

public class BinaryExpression implements ExpressionNode {
    private ExpressionNode left, right;
    private String op;

    public BinaryExpression(ExpressionNode l, String op, ExpressionNode r) {
        this.left = l; this.op = op; this.right = r;
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object lVal = left.evaluate(ctx);
        Object rVal = right.evaluate(ctx);

        if (op.equals("=")) {
            if (lVal instanceof Number && rVal instanceof Number) {
                return ((Number) lVal).doubleValue() == ((Number) rVal).doubleValue();
            }
            return java.util.Objects.equals(lVal, rVal);
        }
        // others
        return null;
    }
}