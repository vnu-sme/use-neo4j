package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class PartialRecursiveCompositionImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.PARTIAL_RECURSIVE_COMPOSITION;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    String nodeType = pattern.getMetadata().getOrDefault("nodeType", "");
    return switch (nodeType) {
      case "binary" -> compileBinary(pattern, compiler, context);
      case "collection" -> compileCollection(pattern, compiler, context);
      case "unary", "not" -> compileUnary(pattern, compiler, context);
      default -> Optional.empty();
    };
  }

  private Optional<CypherExpression> compileBinary(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().size() < 2) return Optional.empty();
    String operator = pattern.getMetadata().getOrDefault("operator", "");
    ExpectedKind childExpected = isLogical(operator)
        ? ExpectedKind.BOOLEAN
        : comparisonExpected(operator);
    Optional<CypherExpression> left = compiler.compileExpression(pattern.getChildren().get(0), context, childExpected);
    Optional<CypherExpression> right = compiler.compileExpression(pattern.getChildren().get(1), context, childExpected);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();

    String expression = binaryExpression(left.get(), operator, right.get());
    if (expression == null) return Optional.empty();
    ExpressionKind outputKind = isLogical(operator) || isComparison(operator) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
    return Optional.of(CypherExpression.combine(expression, outputKind, left.get(), right.get()));
  }

  private Optional<CypherExpression> compileCollection(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    Optional<CypherExpression> source = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return Optional.empty();

    String operation = pattern.getMetadata().getOrDefault("operation", "");
    String expression = switch (operation) {
      case "size" -> "size(" + source.get().getExpression() + ")";
      case "isEmpty" -> "size(" + source.get().getExpression() + ") = 0";
      case "isNotEmpty", "notEmpty" -> "size(" + source.get().getExpression() + ") > 0";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind outputKind = "size".equals(operation) ? ExpressionKind.VALUE : ExpressionKind.BOOLEAN;
    return Optional.of(CypherExpression.combine(expression, outputKind, source.get()));
  }

  private Optional<CypherExpression> compileUnary(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    String operator = pattern.getMetadata().getOrDefault("operator", "");
    ExpectedKind expected = "not".equals(operator) ? ExpectedKind.BOOLEAN : ExpectedKind.VALUE;
    Optional<CypherExpression> child = compiler.compileExpression(pattern.getChildren().get(0), context, expected);
    if (child.isEmpty()) return Optional.empty();

    String expression = switch (operator) {
      case "not" -> "NOT (" + child.get().getExpression() + ")";
      case "+" -> CypherSupport.numeric(child.get().getExpression());
      case "-" -> "-(" + CypherSupport.numeric(child.get().getExpression()) + ")";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind outputKind = "not".equals(operator) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
    return Optional.of(CypherExpression.combine(expression, outputKind, child.get()));
  }

  private String binaryExpression(CypherExpression left, String operator, CypherExpression right) {
    return switch (operator) {
      case "and" -> "(" + left.getExpression() + " AND " + right.getExpression() + ")";
      case "or" -> "(" + left.getExpression() + " OR " + right.getExpression() + ")";
      case "xor" -> "((" + left.getExpression() + " AND NOT (" + right.getExpression()
          + ")) OR (NOT (" + left.getExpression() + ") AND " + right.getExpression() + "))";
      case "implies" -> "((NOT (" + left.getExpression() + ")) OR " + right.getExpression() + ")";
      case ">", "<", ">=", "<=" -> CypherSupport.numeric(left.getExpression()) + " " + operator + " " + CypherSupport.numeric(right.getExpression());
      case "=", "<>" -> compareEquality(left, operator, right);
      case "+" -> CypherSupport.numeric(left.getExpression()) + " + " + CypherSupport.numeric(right.getExpression());
      case "-" -> CypherSupport.numeric(left.getExpression()) + " - " + CypherSupport.numeric(right.getExpression());
      case "*" -> CypherSupport.numeric(left.getExpression()) + " * " + CypherSupport.numeric(right.getExpression());
      case "/" -> CypherSupport.numeric(left.getExpression()) + " / " + CypherSupport.numeric(right.getExpression());
      case "div" -> "toInteger(" + CypherSupport.numeric(left.getExpression()) + " / " + CypherSupport.numeric(right.getExpression()) + ")";
      default -> null;
    };
  }

  private String compareEquality(CypherExpression left, String operator, CypherExpression right) {
    if (left.getKind() == ExpressionKind.NODE || right.getKind() == ExpressionKind.NODE) {
      return left.getExpression() + " " + operator + " " + right.getExpression();
    }
    return CypherSupport.normalizeString(left.getExpression())
        + " " + operator + " "
        + CypherSupport.normalizeString(right.getExpression());
  }

  private boolean isComparison(String operator) {
    return "=".equals(operator)
        || "<>".equals(operator)
        || ">".equals(operator)
        || "<".equals(operator)
        || ">=".equals(operator)
        || "<=".equals(operator);
  }

  private boolean isLogical(String operator) {
    return "and".equals(operator) || "or".equals(operator) || "xor".equals(operator) || "implies".equals(operator);
  }

  private ExpectedKind comparisonExpected(String operator) {
    if ("=".equals(operator) || "<>".equals(operator)) return ExpectedKind.ANY;
    return isComparison(operator) ? ExpectedKind.VALUE : ExpectedKind.VALUE;
  }
}
