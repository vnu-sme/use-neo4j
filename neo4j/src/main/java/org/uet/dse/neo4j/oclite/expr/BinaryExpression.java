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
            if (lVal == null || rVal == null) return lVal == rVal;

            // Xử lý so sánh số (10.0 = 10)
            if (lVal instanceof Number && rVal instanceof Number) {
                return Double.compare(((Number) lVal).doubleValue(), ((Number) rVal).doubleValue()) == 0;
            }

            return java.util.Objects.equals(lVal, rVal);
        }
        // ...
        return null;
    }
}