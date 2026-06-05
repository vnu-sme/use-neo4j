package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;
import org.uet.dse.neo4j.oclite.ast.ASTVarDecl;

public class AdvancedCollectionImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.MULTI_VAR_QUANTIFIER_OPTIMIZED
        || pattern.getKind() == MultiPassPatternKind.IS_UNIQUE_GROUP_BY
        || pattern.getKind() == MultiPassPatternKind.AGGREGATE_FUNCTION
        || pattern.getKind() == MultiPassPatternKind.AS_SET_SIZE_DISTINCT_COUNT
        || pattern.getKind() == MultiPassPatternKind.INCLUDES_ALL_ANTI_JOIN;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    return switch (pattern.getKind()) {
      case MULTI_VAR_QUANTIFIER_OPTIMIZED -> compileMultiVarQuantifier(pattern, compiler, context);
      case IS_UNIQUE_GROUP_BY -> compileIsUnique(pattern, compiler, context);
      case AGGREGATE_FUNCTION -> compileAggregate(pattern, compiler, context);
      case AS_SET_SIZE_DISTINCT_COUNT -> compileDistinctSize(pattern, compiler, context);
      case INCLUDES_ALL_ANTI_JOIN -> compileIncludesAll(pattern, compiler, context);
      default -> Optional.empty();
    };
  }

  private Optional<CypherExpression> compileMultiVarQuantifier(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTQuery query) || pattern.getChildren().isEmpty()) return Optional.empty();
    if (query.variables.size() <= 1) return Optional.empty();

    PatternInstance source = pattern.getChildren().get(0);
    CompileContext scoped = enterVariables(context, query);
    Optional<CypherExpression> body = pattern.getChildren().size() > 1
        ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(query.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    List<String> matches = multiVarMatches(source, query, compiler, context);
    if (matches.isEmpty()) return Optional.empty();
    String operation = query.operation.toLowerCase(Locale.ROOT);

    if ("one".equals(operation)) {
      String count = context.nextName("count");
      List<String> prelude = new ArrayList<>();
      prelude.add("CALL {");
      prelude.addAll(CollectionSourceCypher.indent(matches, "  "));
      prelude.add("  WITH DISTINCT *");
      prelude.addAll(CollectionSourceCypher.indent(bodyFilter(body.get(), body.get().getExpression()), "  "));
      prelude.add("  RETURN count(*) AS " + count);
      prelude.add("}");
      return Optional.of(new CypherExpression(count + " = 1", ExpressionKind.BOOLEAN, prelude, body.get().getCoveredNodes() + 2, body.get().getCost() + 1));
    }

    boolean forall = "forall".equals(operation);
    String predicate = forall ? "NOT (" + body.get().getExpression() + ")" : body.get().getExpression();
    List<String> clauses = new ArrayList<>();
    clauses.add(forall ? "NOT EXISTS {" : "EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(matches, "  "));
    clauses.add("  WITH DISTINCT *");
    clauses.addAll(CollectionSourceCypher.indent(bodyFilter(body.get(), predicate), "  "));
    clauses.add("}");
    return Optional.of(new CypherExpression(
        String.join("\n", clauses),
        ExpressionKind.BOOLEAN,
        List.of(),
        body.get().getCoveredNodes() + 2,
        body.get().getCost() + 1
    ));
  }

  private Optional<CypherExpression> compileIsUnique(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTQuery query) || pattern.getChildren().isEmpty()) return Optional.empty();
    PatternInstance source = pattern.getChildren().get(0);
    String var = query.primaryVariableName();
    Optional<List<String>> matches = CollectionSourceCypher.matchClauses(source, var, compiler, context);
    if (matches.isEmpty()) return Optional.empty();

    CompileContext scoped = enterPrimaryVariable(context, query);
    Optional<CypherExpression> body = pattern.getChildren().size() > 1
        ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.ANY)
        : compiler.compileLegacy(query.body, scoped, ExpectedKind.ANY);
    if (body.isEmpty()) return Optional.empty();

    String duplicates = context.nextName("duplicates");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matches.get(), "  "));
    prelude.add("  WITH DISTINCT *");
    prelude.addAll(CollectionSourceCypher.indent(body.get().getPreludeClauses(), "  "));
    prelude.add("  WITH " + body.get().getExpression() + " AS key, count(DISTINCT " + var + ") AS count_" + var);
    prelude.add("  WHERE count_" + var + " > 1");
    prelude.add("  RETURN count(*) AS " + duplicates);
    prelude.add("}");
    return Optional.of(new CypherExpression(duplicates + " = 0", ExpressionKind.BOOLEAN, prelude, body.get().getCoveredNodes() + 2, body.get().getCost() + 1));
  }

  private Optional<CypherExpression> compileAggregate(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTCollectionOp op) || pattern.getChildren().isEmpty()) return Optional.empty();
    PatternInstance source = pattern.getChildren().get(0);
    String aggregate = pattern.getMetadata().getOrDefault("aggregate", op.opName).toLowerCase(Locale.ROOT);
    String item = context.nextName("item");
    String value = context.nextName("value");
    Optional<List<String>> matches;
    Optional<CypherExpression> valueExpression;

    if ("collect".equals(pattern.getMetadata().get("mode")) && op.source instanceof ASTQuery query) {
      String iterator = query.primaryVariableName();
      matches = CollectionSourceCypher.matchClauses(source, iterator, compiler, context);
      CompileContext scoped = enterPrimaryVariable(context, query);
      valueExpression = pattern.getChildren().size() > 1
          ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.VALUE)
          : compiler.compileLegacy(query.body, scoped, ExpectedKind.VALUE);
    } else {
      matches = CollectionSourceCypher.matchClauses(source, item, compiler, context);
      valueExpression = Optional.of(new CypherExpression(item, ExpressionKind.VALUE));
    }
    if (matches.isEmpty() || valueExpression.isEmpty()) return Optional.empty();

    String result = context.nextName("agg");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matches.get(), "  "));
    prelude.add("  WITH DISTINCT *");
    prelude.addAll(CollectionSourceCypher.indent(valueExpression.get().getPreludeClauses(), "  "));
    prelude.add("  WITH " + valueExpression.get().getExpression() + " AS " + value);
    prelude.add("  RETURN " + aggregateExpression(aggregate, value) + " AS " + result);
    prelude.add("}");
    return Optional.of(new CypherExpression(result, ExpressionKind.VALUE, prelude, valueExpression.get().getCoveredNodes() + 2, valueExpression.get().getCost() + 1));
  }

  private Optional<CypherExpression> compileDistinctSize(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (pattern.getChildren().isEmpty()) return Optional.empty();
    return CollectionSourceTerminalCypher.count(pattern.getChildren().get(0), compiler, context);
  }

  private Optional<CypherExpression> compileIncludesAll(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTCollectionOp op) || pattern.getChildren().isEmpty() || op.args.size() != 1) {
      return Optional.empty();
    }

    PatternInstance source = pattern.getChildren().get(0);
    String sourceItem = context.nextName("source");
    Optional<List<String>> sourceClauses = CollectionSourceCypher.matchClauses(source, sourceItem, compiler, context);
    if (sourceClauses.isEmpty()) return Optional.empty();

    String targetItem = context.nextName("target");
    List<String> prelude = new ArrayList<>();
    List<String> targetClauses = new ArrayList<>();
    if (pattern.getChildren().size() > 1) {
      Optional<List<String>> clauses = CollectionSourceCypher.matchClauses(pattern.getChildren().get(1), targetItem, compiler, context);
      if (clauses.isEmpty()) return Optional.empty();
      targetClauses.addAll(clauses.get());
    } else {
      Optional<CypherExpression> target = compiler.compileLegacy(op.args.get(0), context, ExpectedKind.ANY);
      if (target.isEmpty()) return Optional.empty();
      prelude.addAll(target.get().getPreludeClauses());
      targetClauses.add("UNWIND " + asCollection(target.get()) + " AS " + targetItem);
    }

    boolean includesAll = "includesAll".equalsIgnoreCase(op.opName);
    List<String> clauses = new ArrayList<>();
    clauses.add("NOT EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(targetClauses, "  "));
    clauses.add("  WITH DISTINCT " + targetItem);
    clauses.add(includesAll ? "  WHERE NOT EXISTS {" : "  WHERE EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(sourceClauses.get(), "    "));
    clauses.add("    WITH *");
    clauses.add("    WHERE " + sameValue(sourceItem, targetItem));
    clauses.add("  }");
    clauses.add("}");

    return Optional.of(new CypherExpression(
        String.join("\n", clauses),
        ExpressionKind.BOOLEAN,
        prelude,
        2,
        1
    ));
  }

  private List<String> multiVarMatches(
      PatternInstance source,
      ASTQuery query,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    List<String> clauses = new ArrayList<>();
    for (ASTVarDecl variable : query.variables) {
      Optional<List<String>> match = CollectionSourceCypher.matchClauses(source, variable.name, compiler, context);
      if (match.isEmpty()) return List.of();
      clauses.addAll(match.get());
    }
    return clauses;
  }

  private CompileContext enterVariables(CompileContext context, ASTQuery query) {
    String inferredType = AstPatternSupport.inferCollectionElementType(query.source, context).orElse(null);
    CompileContext scoped = context;
    for (ASTVarDecl variable : query.variables) {
      scoped = scoped.enterScope(variable.name, variable.hasType() ? variable.typeName : inferredType);
    }
    return scoped;
  }

  private CompileContext enterPrimaryVariable(CompileContext context, ASTQuery query) {
    String inferredType = AstPatternSupport.inferCollectionElementType(query.source, context).orElse(null);
    if (query.variables.isEmpty()) return context.enterScope(query.primaryVariableName(), inferredType);
    ASTVarDecl variable = query.variables.get(0);
    return context.enterScope(variable.name, variable.hasType() ? variable.typeName : inferredType);
  }

  private List<String> bodyFilter(CypherExpression body, String predicate) {
    List<String> clauses = new ArrayList<>(body.getPreludeClauses());
    clauses.add("WITH *");
    clauses.add("WHERE " + predicate);
    return clauses;
  }

  private String aggregateExpression(String aggregate, String value) {
    String numeric = CypherSupport.numeric(value);
    return switch (aggregate) {
      case "sum" -> "coalesce(sum(" + numeric + "), 0.0)";
      case "avg", "average" -> "avg(" + numeric + ")";
      case "min" -> "min(" + numeric + ")";
      case "max" -> "max(" + numeric + ")";
      default -> "collect(" + value + ")";
    };
  }

  private String sameValue(String left, String right) {
    return "(" + left + " = " + right
        + " OR " + CypherSupport.normalizeString(left) + " = " + CypherSupport.normalizeString(right) + ")";
  }

  private String asCollection(CypherExpression expression) {
    if (expression.getKind() == ExpressionKind.COLLECTION) return expression.getExpression();
    return "CASE WHEN " + expression.getExpression() + " IS NULL THEN [] ELSE [" + expression.getExpression() + "] END";
  }
}
