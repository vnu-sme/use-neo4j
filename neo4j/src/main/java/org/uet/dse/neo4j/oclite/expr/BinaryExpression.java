package org.uet.dse.neo4j.oclite.expr;

import java.util.Objects;
import java.util.Collection;
import org.neo4j.driver.types.Node;

public class BinaryExpression implements ExpressionNode {
    private final ExpressionNode left, right;
    private final String op;

    public BinaryExpression(ExpressionNode l, String op, ExpressionNode r) {
        this.left = l;
        this.op = op;
        this.right = r;
    }

    @Override
    public Object evaluate(ExecutionContext ctx) {
        Object lVal = left.evaluate(ctx);
        Object rVal = right.evaluate(ctx);

        if (op.equals("=") || op.equals("<>")) {
            boolean isEqual = compareEquals(lVal, rVal);
            return op.equals("=") ? isEqual : !isEqual;
        }

        if (op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=")) {
            return compareNumbers(lVal, rVal, op);
        }

        if (op.equals("+") || op.equals("-") || op.equals("*") || op.equals("/") || op.equals("div")) {
            return calculateNumbers(lVal, rVal, op);
        }

        if (op.equals("and") || op.equals("or") || op.equals("xor") || op.equals("implies")) {
            return evaluateLogic(lVal, rVal, op);
        }

        return null;
    }

    private boolean compareEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        if (a instanceof Node && b instanceof Node) {
            return ((Node) a).id() == ((Node) b).id();
        }
        if (a instanceof Collection && b instanceof Collection) {
            Collection<?> leftItems = (Collection<?>) a;
            Collection<?> rightItems = (Collection<?>) b;
            return leftItems.size() == rightItems.size()
                && leftItems.stream().allMatch(l -> rightItems.stream().anyMatch(r -> compareEquals(l, r)));
        }
        return Objects.equals(a, b);
    }

    private Object compareNumbers(Object a, Object b, String operator) {
        Number leftNumber = asNumber(a);
        Number rightNumber = asNumber(b);
        if (leftNumber == null || rightNumber == null) return false;

        double n1 = leftNumber.doubleValue();
        double n2 = rightNumber.doubleValue();

        switch (operator) {
            case ">":  return n1 > n2;
            case "<":  return n1 < n2;
            case ">=": return n1 >= n2;
            case "<=": return n1 <= n2;
        }
        return false;
    }

    private Object calculateNumbers(Object a, Object b, String operator) {
        Number leftNumber = asNumber(a);
        Number rightNumber = asNumber(b);
        if (leftNumber == null || rightNumber == null) return null;

        double n1 = leftNumber.doubleValue();
        double n2 = rightNumber.doubleValue();

        switch (operator) {
            case "+": return n1 + n2;
            case "-": return n1 - n2;
            case "*": return n1 * n2;
            case "/":
                if (n2 == 0) throw new ArithmeticException("Lỗi chia cho 0");
                return n1 / n2;
            case "div":
                if (n2 == 0) throw new ArithmeticException("Lỗi chia cho 0");
                return (double) ((long) n1 / (long) n2);
        }
        return null;
    }

    private Object evaluateLogic(Object a, Object b, String operator) {
        Boolean b1 = (a instanceof Boolean) ? (Boolean) a : false;
        Boolean b2 = (b instanceof Boolean) ? (Boolean) b : false;

      return switch (op) {
        case "and" -> b1 && b2;
        case "or" -> b1 || b2;
        case "xor" -> b1 ^ b2;
        case "implies" ->
          //A implies B  <=>  !A or B
            !b1 || b2;
        default -> false;
      };

    }

    private Number asNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            String raw = ((String) value).trim();
            if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            raw = raw.replaceAll("[^0-9.\\-]", "");
            if (raw.isEmpty() || raw.equals("-") || raw.equals(".")) {
                return null;
            }
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
