package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class FilteredCompareImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.FILTERED_SIZE_COMPARE
        || pattern.getKind() == MultiPassPatternKind.FILTERED_EXISTENCE_COMPARE;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getKind() == MultiPassPatternKind.FILTERED_EXISTENCE_COMPARE) {
      return compileExistence(pattern, compiler, context);
    }
    return compileSizeCompare(pattern, compiler, context);
  }

  private Optional<CypherExpression> compileSizeCompare(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().size() < 2) return Optional.empty();
    Optional<PatternInstance> filter = filteredSource(pattern.getChildren().get(0));
    if (filter.isEmpty()) return Optional.empty();

    Optional<CypherExpression> count = FilteredQueryCypher.countMatches(filter.get(), compiler, context);
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

  private Optional<CypherExpression> compileExistence(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    Optional<PatternInstance> filter = filteredSource(pattern.getChildren().get(0));
    if (filter.isEmpty()) return Optional.empty();

    boolean desiredExists = Boolean.parseBoolean(pattern.getMetadata().getOrDefault("desiredExists", "true"));
    return FilteredQueryCypher.existsMatches(filter.get(), desiredExists, compiler, context);
  }

  private Optional<PatternInstance> filteredSource(PatternInstance filteredTerminal) {
    if (filteredTerminal.getKind() != MultiPassPatternKind.FILTERED_TERMINAL || filteredTerminal.getChildren().isEmpty()) {
      return Optional.empty();
    }
    PatternInstance filter = filteredTerminal.getChildren().get(0);
    return filter.getKind() == MultiPassPatternKind.FILTER ? Optional.of(filter) : Optional.empty();
  }

  private String compare(String left, String operator, String right) {
    return switch (operator) {
      case ">", "<", ">=", "<=" -> CypherSupport.numeric(left) + " " + operator + " " + CypherSupport.numeric(right);
      case "=", "<>" -> CypherSupport.normalizeString(left) + " " + operator + " " + CypherSupport.normalizeString(right);
      default -> null;
    };
  }
}
