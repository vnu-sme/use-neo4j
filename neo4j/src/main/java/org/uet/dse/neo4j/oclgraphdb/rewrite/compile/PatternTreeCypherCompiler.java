package org.uet.dse.neo4j.oclgraphdb.rewrite.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;
import org.uet.dse.neo4j.oclite.ast.*;

public class PatternTreeCypherCompiler {
  private final PatternPlanner legacyPlanner;

  public PatternTreeCypherCompiler() {
    this(PatternPlanner.withDefaultPatterns());
  }

  public PatternTreeCypherCompiler(PatternPlanner legacyPlanner) {
    this.legacyPlanner = legacyPlanner;
  }

  public Optional<CypherPlan> compilePlan(RewritePatternPlan plan, CompileContext context) {
    return compilePlan(plan.getFinalTree(), context);
  }

  public Optional<CypherPlan> compilePlan(RewritePatternTree tree, CompileContext context) {
    RewritePatternNode root = tree.getRoot();
    if (root == null) return Optional.empty();

    if (root.getKind() == RewritePatternKind.CONTEXT_INVARIANT && root.getAstNode() instanceof ASTContext astContext) {
      if (root.getChildren().isEmpty()) return Optional.empty();
      CompileContext scoped = contextWithSelf(astContext, context);
      Optional<CypherExpression> predicate = compileExpression(root.getChildren().get(0), scoped, ExpectedKind.BOOLEAN);
      return predicate.map(expression -> CypherPlan.validation(astContext.className, expression, scoped));
    }

    RewritePatternNode scalarRoot = root.getKind() == RewritePatternKind.SCALAR_QUERY && !root.getChildren().isEmpty()
        ? root.getChildren().get(0)
        : root;
    return compileExpression(scalarRoot, context, ExpectedKind.ANY)
        .map(expression -> CypherPlan.scalar(expression, context));
  }

  public Optional<CypherExpression> compileExpression(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    Optional<CypherExpression> expression = compileSpecific(node, context, expected);
    if (expression.isPresent() && expected.accepts(expression.get().getKind())) {
      return expression;
    }
    return compileLegacy(node, context, expected);
  }

  private Optional<CypherExpression> compileSpecific(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    return switch (node.getKind()) {
      case ATTRIBUTE_ACCESS, BOOLEAN_ATTRIBUTE_ACCESS -> compileAttribute(node, context, expected);
      case NAVIGATION_ACCESS -> compileNavigation(node, context, expected);
      case COMPARE, ATTRIBUTE_COMPARE, NAVIGATION_COMPARE -> compileCompare(node, context);
      case LOGICAL_COMPOSITION -> compileLogical(node, context);
      case NEGATION -> compileNegation(node, context);
      case FILTER -> compileFilter(node, context);
      case EXISTS_FILTER -> compileExistsFilter(node, context);
      case FILTER_TERMINAL -> compileFilterTerminal(node, context);
      case QUANTIFIER -> compileQuantifier(node, context);
      case COLLECT -> compileCollect(node, context);
      case UNIQUE -> compileIsUnique(node, context);
      case SORTED -> compileSortedBy(node, context);
      case LET_SCOPE -> compileLet(node, context, expected);
      case CONDITIONAL -> compileConditional(node, context, expected);
      default -> Optional.empty();
    };
  }

  private Optional<CypherExpression> compileAttribute(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (!(node.getAstNode() instanceof ASTProperty property) || node.getChildren().isEmpty()) return Optional.empty();
    Optional<CypherExpression> source = compileExpression(node.getChildren().get(0), context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = Boolean.parseBoolean(node.metadata("collection"));
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.VALUE;
    String expression = collection
        ? CypherSupport.attributeValues(source.get().getExpression(), property.name, context)
        : CypherSupport.attributeValue(source.get().getExpression(), property.name, context);
    if (node.getKind() == RewritePatternKind.BOOLEAN_ATTRIBUTE_ACCESS) {
      return Optional.of(CypherExpression.combine(CypherSupport.booleanValue(expression), ExpressionKind.BOOLEAN, source.get()));
    }
    return Optional.of(CypherExpression.combine(expression, kind, source.get()));
  }

  private Optional<CypherExpression> compileNavigation(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (!(node.getAstNode() instanceof ASTProperty property) || node.getChildren().isEmpty()) return Optional.empty();
    Optional<CypherExpression> source = compileExpression(node.getChildren().get(0), context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = Boolean.parseBoolean(node.metadata("collection"));
    String navigation = CypherSupport.navigation(source.get().getExpression(), property.name, context);
    if (collection || expected == ExpectedKind.COLLECTION) {
      return Optional.of(CypherExpression.combine(navigation, ExpressionKind.COLLECTION, source.get()));
    }
    return Optional.of(CypherExpression.combine("head(" + navigation + ")", ExpressionKind.NODE, source.get()));
  }

  private Optional<CypherExpression> compileCompare(RewritePatternNode node, CompileContext context) {
    if (!(node.getAstNode() instanceof ASTBinary binary) || node.getChildren().size() < 2) return Optional.empty();
    Optional<CypherExpression> left = compileExpression(node.getChildren().get(0), context, ExpectedKind.ANY);
    Optional<CypherExpression> right = compileExpression(node.getChildren().get(1), context, ExpectedKind.ANY);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();
    return Optional.of(CypherExpression.combine(compare(left.get(), right.get(), binary.op), ExpressionKind.BOOLEAN, left.get(), right.get()));
  }

  private Optional<CypherExpression> compileLogical(RewritePatternNode node, CompileContext context) {
    if (!(node.getAstNode() instanceof ASTBinary binary) || node.getChildren().size() < 2) return Optional.empty();
    Optional<CypherExpression> left = compileExpression(node.getChildren().get(0), context, ExpectedKind.BOOLEAN);
    Optional<CypherExpression> right = compileExpression(node.getChildren().get(1), context, ExpectedKind.BOOLEAN);
    if (left.isEmpty() || right.isEmpty()) return Optional.empty();

    String expression = switch (binary.op) {
      case "and" -> "(" + left.get().getExpression() + " AND " + right.get().getExpression() + ")";
      case "or" -> "(" + left.get().getExpression() + " OR " + right.get().getExpression() + ")";
      case "xor" -> "((" + left.get().getExpression() + " AND NOT (" + right.get().getExpression()
          + ")) OR (NOT (" + left.get().getExpression() + ") AND " + right.get().getExpression() + "))";
      case "implies" -> "((NOT (" + left.get().getExpression() + ")) OR " + right.get().getExpression() + ")";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, left.get(), right.get()));
  }

  private Optional<CypherExpression> compileNegation(RewritePatternNode node, CompileContext context) {
    if (node.getChildren().isEmpty()) return Optional.empty();
    Optional<CypherExpression> child = compileExpression(node.getChildren().get(0), context, ExpectedKind.BOOLEAN);
    return child.map(expression -> CypherExpression.combine("NOT (" + expression.getExpression() + ")", ExpressionKind.BOOLEAN, expression));
  }

  private Optional<CypherExpression> compileFilter(RewritePatternNode node, CompileContext context) {
    QueryCompileData data = queryData(node, context, ExpectedKind.BOOLEAN);
    if (data == null || data.body().getPreludeClauses().size() > 0 || data.variables().size() != 1) return Optional.empty();

    String var = data.variables().get(0);
    String predicate = "reject".equals(data.operation())
        ? "NOT (" + data.body().getExpression() + ")"
        : data.body().getExpression();
    String expression = "[" + var + " IN " + data.source().getExpression() + " WHERE " + predicate + "]";
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.COLLECTION, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileFilterTerminal(RewritePatternNode node, CompileContext context) {
    if (!(node.getAstNode() instanceof ASTCollectionOp terminal) || node.getChildren().isEmpty()) return Optional.empty();
    RewritePatternNode filter = node.getChildren().get(0);
    if (filter.getKind() != RewritePatternKind.FILTER) return Optional.empty();

    QueryCompileData data = queryData(filter, context, ExpectedKind.BOOLEAN);
    if (data == null || data.body().getPreludeClauses().size() > 0) return Optional.empty();

    String predicate = "reject".equals(data.operation())
        ? "NOT (" + data.body().getExpression() + ")"
        : data.body().getExpression();
    String count = countMatches(data.variables(), data.source().getExpression(), predicate);
    String expression = switch (terminal.opName) {
      case "size" -> count;
      case "isEmpty" -> count + " = 0";
      case "isNotEmpty", "notEmpty" -> count + " > 0";
      default -> null;
    };
    if (expression == null) return Optional.empty();

    ExpressionKind kind = "size".equals(terminal.opName) ? ExpressionKind.VALUE : ExpressionKind.BOOLEAN;
    return Optional.of(CypherExpression.combine(expression, kind, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileExistsFilter(RewritePatternNode node, CompileContext context) {
    if (!(node.getAstNode() instanceof ASTCollectionOp terminal) || node.getChildren().isEmpty()) return Optional.empty();
    RewritePatternNode filter = node.getChildren().get(0);
    if (filter.getKind() != RewritePatternKind.FILTER) return Optional.empty();

    QueryCompileData data = queryData(filter, context, ExpectedKind.BOOLEAN);
    if (data == null || data.body().getPreludeClauses().size() > 0) return Optional.empty();

    String predicate = "reject".equals(data.operation())
        ? "NOT (" + data.body().getExpression() + ")"
        : data.body().getExpression();
    String exists = nestedQuantifier("any", data.variables(), data.source().getExpression(), predicate, 0);
    String expression = switch (terminal.opName) {
      case "isEmpty" -> "NOT (" + exists + ")";
      case "isNotEmpty", "notEmpty" -> exists;
      default -> null;
    };
    if (expression == null) return Optional.empty();
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileQuantifier(RewritePatternNode node, CompileContext context) {
    QueryCompileData data = queryData(node, context, ExpectedKind.BOOLEAN);
    if (data == null || data.body().getPreludeClauses().size() > 0) return Optional.empty();

    String expression = switch (data.operation()) {
      case "exists" -> nestedQuantifier("any", data.variables(), data.source().getExpression(), data.body().getExpression(), 0);
      case "forall" -> nestedQuantifier("all", data.variables(), data.source().getExpression(), data.body().getExpression(), 0);
      case "one" -> countMatches(data.variables(), data.source().getExpression(), data.body().getExpression()) + " = 1";
      case "any" -> data.variables().size() == 1
          ? "head([" + data.variables().get(0) + " IN " + data.source().getExpression() + " WHERE " + data.body().getExpression()
              + " | " + data.variables().get(0) + "])"
          : null;
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind kind = "any".equals(data.operation()) ? ExpressionKind.NODE : ExpressionKind.BOOLEAN;
    return Optional.of(CypherExpression.combine(expression, kind, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileCollect(RewritePatternNode node, CompileContext context) {
    QueryCompileData data = queryData(node, context, ExpectedKind.ANY);
    if (data == null || data.body().getPreludeClauses().size() > 0 || data.variables().size() != 1) return Optional.empty();

    String var = data.variables().get(0);
    String raw = "[" + var + " IN " + data.source().getExpression() + " | " + data.body().getExpression() + "]";
    String expression = "collectnested".equals(data.operation())
        ? "reduce(acc = [], item IN " + raw + " | acc + item)"
        : raw;
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.COLLECTION, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileIsUnique(RewritePatternNode node, CompileContext context) {
    QueryCompileData data = queryData(node, context, ExpectedKind.ANY);
    if (data == null || data.body().getPreludeClauses().size() > 0 || data.variables().size() != 1) return Optional.empty();
    String var = data.variables().get(0);
    String values = "[" + var + " IN " + data.source().getExpression() + " | " + data.body().getExpression() + "]";
    String expression = "size(" + values + ") = size(" + distinct(values, context) + ")";
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.BOOLEAN, data.source(), data.body()));
  }

  private Optional<CypherExpression> compileSortedBy(RewritePatternNode node, CompileContext context) {
    QueryCompileData data = queryData(node, context, ExpectedKind.ANY);
    if (data == null || data.variables().size() != 1) return Optional.empty();
    String var = data.variables().get(0);
    String sorted = context.nextName("sorted");
    String key = context.nextName("key");
    List<String> prelude = new ArrayList<>(data.source().getPreludeClauses());
    prelude.add("CALL {");
    prelude.add("  WITH *");
    prelude.add("  UNWIND " + data.source().getExpression() + " AS " + var);
    prelude.addAll(indent(data.body().getPreludeClauses(), "  "));
    prelude.add("  WITH *, " + data.body().getExpression() + " AS " + key);
    prelude.add("  ORDER BY " + key);
    prelude.add("  RETURN collect(" + var + ") AS " + sorted);
    prelude.add("}");
    return Optional.of(new CypherExpression(
        sorted,
        ExpressionKind.COLLECTION,
        prelude,
        data.source().getCoveredNodes() + data.body().getCoveredNodes() + 1,
        data.source().getCost() + data.body().getCost() + 1
    ));
  }

  private Optional<CypherExpression> compileLet(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (!(node.getAstNode() instanceof ASTLet let) || node.getChildren().size() < let.bindings.size() + 1) return Optional.empty();
    CompileContext scoped = context;
    for (int i = 0; i < let.bindings.size(); i++) {
      ASTLetBinding binding = let.bindings.get(i);
      Optional<CypherExpression> value = compileExpression(node.getChildren().get(i), scoped, ExpectedKind.ANY);
      if (value.isEmpty()) return Optional.empty();
      scoped = scoped.withExpressionBinding(binding.variable.name, value.get(), binding.variable.typeName);
    }
    return compileExpression(node.getChildren().get(node.getChildren().size() - 1), scoped, expected);
  }

  private Optional<CypherExpression> compileConditional(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (node.getChildren().size() < 3) return Optional.empty();
    Optional<CypherExpression> condition = compileExpression(node.getChildren().get(0), context, ExpectedKind.BOOLEAN);
    Optional<CypherExpression> thenBranch = compileExpression(node.getChildren().get(1), context, expected == ExpectedKind.ANY ? ExpectedKind.ANY : expected);
    Optional<CypherExpression> elseBranch = compileExpression(node.getChildren().get(2), context, expected == ExpectedKind.ANY ? ExpectedKind.ANY : expected);
    if (condition.isEmpty() || thenBranch.isEmpty() || elseBranch.isEmpty()) return Optional.empty();
    ExpressionKind kind = thenBranch.get().getKind() == elseBranch.get().getKind() ? thenBranch.get().getKind() : ExpressionKind.VALUE;
    String expression = "CASE WHEN " + condition.get().getExpression()
        + " THEN " + thenBranch.get().getExpression()
        + " ELSE " + elseBranch.get().getExpression()
        + " END";
    return Optional.of(CypherExpression.combine(expression, kind, condition.get(), thenBranch.get(), elseBranch.get()));
  }

  private QueryCompileData queryData(RewritePatternNode node, CompileContext context, ExpectedKind bodyExpected) {
    if (node.getChildren().size() < 2) return null;
    RewriteNodeSupport.QueryShape query = RewriteNodeSupport.queryShape(node);
    if (query.operation().isBlank()) return null;

    Optional<CypherExpression> source = compileExpression(node.getChildren().get(0), context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return null;

    CompileContext scoped = RewriteNodeSupport.enterQueryScope(query, node.getChildren().get(0), context);
    Optional<CypherExpression> body = compileExpression(node.getChildren().get(1), scoped, bodyExpected);
    if (body.isEmpty()) return null;

    return new QueryCompileData(
        query.operation().toLowerCase(Locale.ROOT),
        query.variables(),
        source.get(),
        body.get()
    );
  }

  private Optional<CypherExpression> compileLegacy(
      RewritePatternNode node,
      CompileContext context,
      ExpectedKind expected
  ) {
    if (node.getAstNode() == null) return Optional.empty();
    return legacyPlanner.plan(node.getAstNode(), context, expected).map(PlanCandidate::expression);
  }

  private String compare(CypherExpression left, CypherExpression right, String op) {
    if (">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op)) {
      return CypherSupport.numeric(left.getExpression()) + " " + op + " " + CypherSupport.numeric(right.getExpression());
    }
    if (left.getKind() == ExpressionKind.NODE || right.getKind() == ExpressionKind.NODE) {
      return left.getExpression() + " " + op + " " + right.getExpression();
    }
    return CypherSupport.normalizeString(left.getExpression())
        + " " + op + " "
        + CypherSupport.normalizeString(right.getExpression());
  }

  private String nestedQuantifier(String quantifier, List<String> variables, String collection, String body, int index) {
    String var = variables.get(index);
    String inner = index == variables.size() - 1
        ? body
        : nestedQuantifier(quantifier, variables, collection, body, index + 1);
    return quantifier + "(" + var + " IN " + collection + " WHERE " + inner + ")";
  }

  private String countMatches(List<String> variables, String collection, String body) {
    if (variables.size() == 1) {
      String var = variables.get(0);
      return "size([" + var + " IN " + collection + " WHERE " + body + "])";
    }
    return "size(" + nestedMatchList(variables, collection, body, 0) + ")";
  }

  private String nestedMatchList(List<String> variables, String collection, String body, int index) {
    String var = variables.get(index);
    if (index == variables.size() - 1) {
      return "[" + var + " IN " + collection + " WHERE " + body + " | 1]";
    }
    String acc = "acc" + index;
    String nested = nestedMatchList(variables, collection, body, index + 1);
    return "reduce(" + acc + " = [], " + var + " IN " + collection + " | " + acc + " + " + nested + ")";
  }

  private String distinct(String collection, CompileContext context) {
    String item = context.nextName("item");
    String existing = context.nextName("existing");
    return "reduce(acc = [], " + item + " IN " + collection
        + " | CASE WHEN any(" + existing + " IN acc WHERE " + sameValue(existing, item)
        + ") THEN acc ELSE acc + [" + item + "] END)";
  }

  private String sameValue(String left, String right) {
    return "(" + left + " = " + right + " OR " + CypherSupport.normalizeString(left) + " = " + CypherSupport.normalizeString(right) + ")";
  }

  private List<String> indent(List<String> lines, String prefix) {
    List<String> result = new ArrayList<>();
    for (String line : lines) {
      result.add(prefix + line);
    }
    return result;
  }

  private CompileContext contextWithSelf(ASTContext ast, CompileContext context) {
    CompileContext scoped = context.withRootVariable("self", ast.className);
    if (ast.contextVariableName != null && !ast.contextVariableName.isBlank()) {
      scoped = scoped.withExpressionBinding(ast.contextVariableName, new CypherExpression("self", ExpressionKind.NODE), ast.className);
    }
    return scoped;
  }

  private record QueryCompileData(String operation, List<String> variables, CypherExpression source, CypherExpression body) {}
}
