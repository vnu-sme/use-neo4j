package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class SizeCompareImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.NAVIGATION_SIZE_COMPARE
        || pattern.getKind() == MultiPassPatternKind.ALL_INSTANCES_SIZE_COMPARE
        || pattern.getKind() == MultiPassPatternKind.OPTIONAL_NAVIGATION_EMPTY_CHECK;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getKind() == MultiPassPatternKind.OPTIONAL_NAVIGATION_EMPTY_CHECK) {
      return compileEmptyCheck(pattern, compiler, context);
    }
    return compileSizeCompare(pattern, compiler, context);
  }

  private Optional<CypherExpression> compileSizeCompare(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().size() < 2) return Optional.empty();
    PatternInstance source = pattern.getChildren().get(0);

    Optional<Boolean> desiredExists = desiredExists(pattern);
    if (desiredExists.isPresent()) {
      return CollectionSourceTerminalCypher.exists(source, desiredExists.get(), compiler, context);
    }

    Optional<CypherExpression> count = CollectionSourceTerminalCypher.count(source, compiler, context);
    Optional<CypherExpression> threshold = compiler.compileExpression(pattern.getChildren().get(1), context, ExpectedKind.VALUE);
    if (count.isEmpty() || threshold.isEmpty()) return Optional.empty();

    String operator = pattern.getMetadata().getOrDefault("operator", "");
    boolean sizeOnLeft = Boolean.parseBoolean(pattern.getMetadata().getOrDefault("sizeOnLeft", "true"));
    String left = sizeOnLeft ? count.get().getExpression() : threshold.get().getExpression();
    String right = sizeOnLeft ? threshold.get().getExpression() : count.get().getExpression();
    String expression = compare(left, operator, right);
    if (expression == null) return Optional.empty();
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, count.get(), threshold.get()));
  }

  private Optional<CypherExpression> compileEmptyCheck(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    boolean desiredExists = Boolean.parseBoolean(pattern.getMetadata().getOrDefault("desiredExists", "true"));
    return CollectionSourceTerminalCypher.exists(pattern.getChildren().get(0), desiredExists, compiler, context);
  }

  private Optional<Boolean> desiredExists(PatternInstance pattern) {
    String value = pattern.getMetadata().get("desiredExists");
    if (value == null) return Optional.empty();
    return Optional.of(Boolean.parseBoolean(value));
  }

  private String compare(String left, String operator, String right) {
    return switch (operator) {
      case ">", "<", ">=", "<=" -> CypherSupport.numeric(left) + " " + operator + " " + CypherSupport.numeric(right);
      case "=", "<>" -> CypherSupport.normalizeString(left) + " " + operator + " " + CypherSupport.normalizeString(right);
      default -> null;
    };
  }
}
