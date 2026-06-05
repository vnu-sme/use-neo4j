package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;

public class QuantifierPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.QUANTIFIER;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    Optional<QueryLike> query = queryLike(pattern.getAstNode());
    if (query.isEmpty()) return Optional.empty();
    if (pattern.getChildren().isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(query.get().source(), context)
        .map(type -> context.enterScope(query.get().iteratorName(), type))
        .orElseGet(() -> context.enterScope(query.get().iteratorName()));
    Optional<CypherExpression> body = pattern.getChildren().size() > 1
        ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(query.get().body(), scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String operation = query.get().operation().toLowerCase();
    Optional<CypherExpression> optimized = optimizedQuantifier(
        pattern.getChildren().get(0),
        query.get(),
        body.get(),
        operation,
        compiler,
        context
    );
    if (optimized.isPresent()) return optimized;

    Optional<CypherExpression> source = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return Optional.empty();
    String expression = switch (operation) {
      case "exists" -> "any(" + query.get().iteratorName() + " IN " + source.get().getExpression() + " WHERE " + body.get().getExpression() + ")";
      case "forall" -> "all(" + query.get().iteratorName() + " IN " + source.get().getExpression() + " WHERE " + body.get().getExpression() + ")";
      case "one" -> "size([" + query.get().iteratorName() + " IN " + source.get().getExpression() + " WHERE " + body.get().getExpression() + "]) = 1";
      case "any" -> "head([" + query.get().iteratorName() + " IN " + source.get().getExpression() + " WHERE " + body.get().getExpression() + " | " + query.get().iteratorName() + "])";
      default -> null;
    };
    if (expression == null) return Optional.empty();
    ExpressionKind kind = "any".equals(operation) ? ExpressionKind.NODE : ExpressionKind.BOOLEAN;
    return Optional.of(CypherExpression.combine(expression, kind, source.get(), body.get()));
  }

  private Optional<CypherExpression> optimizedQuantifier(
      PatternInstance sourcePattern,
      QueryLike query,
      CypherExpression body,
      String operation,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    return switch (operation) {
      case "exists", "forall" -> optimizedPredicate(sourcePattern, query, body, operation, compiler, context);
      case "one" -> optimizedCount(sourcePattern, query, body, compiler, context);
      case "any" -> optimizedAny(sourcePattern, query, body, compiler, context);
      default -> Optional.empty();
    };
  }

  private Optional<CypherExpression> optimizedPredicate(
      PatternInstance sourcePattern,
      QueryLike query,
      CypherExpression body,
      String operation,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(sourcePattern, query.iteratorName(), compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    List<String> clauses = new ArrayList<>();
    boolean forall = "forall".equals(operation);
    clauses.add(forall ? "NOT EXISTS {" : "EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    clauses.addAll(CollectionSourceCypher.indent(
        bodyFilter(body, forall ? "NOT (" + body.getExpression() + ")" : body.getExpression()),
        "  "
    ));
    clauses.add("}");
    return Optional.of(new CypherExpression(
        String.join("\n", clauses),
        ExpressionKind.BOOLEAN,
        List.of(),
        body.getCoveredNodes() + 2,
        body.getCost() + 1
    ));
  }

  private Optional<CypherExpression> optimizedCount(
      PatternInstance sourcePattern,
      QueryLike query,
      CypherExpression body,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (sourcePattern.getKind() != MultiPassPatternKind.ALL_INSTANCES_SOURCE) return Optional.empty();
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(sourcePattern, query.iteratorName(), compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    String countVar = context.nextName("count");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    prelude.addAll(CollectionSourceCypher.indent(bodyFilter(body, body.getExpression()), "  "));
    prelude.add("  RETURN count(DISTINCT " + query.iteratorName() + ") AS " + countVar);
    prelude.add("}");
    return Optional.of(new CypherExpression(
        countVar + " = 1",
        ExpressionKind.BOOLEAN,
        prelude,
        body.getCoveredNodes() + 2,
        body.getCost() + 1
    ));
  }

  private Optional<CypherExpression> optimizedAny(
      PatternInstance sourcePattern,
      QueryLike query,
      CypherExpression body,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (sourcePattern.getKind() != MultiPassPatternKind.ALL_INSTANCES_SOURCE) return Optional.empty();
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(sourcePattern, query.iteratorName(), compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    String resultVar = context.nextName("any");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    prelude.addAll(CollectionSourceCypher.indent(bodyFilter(body, body.getExpression()), "  "));
    prelude.add("  RETURN " + query.iteratorName() + " AS " + resultVar);
    prelude.add("  LIMIT 1");
    prelude.add("}");
    return Optional.of(new CypherExpression(
        resultVar,
        ExpressionKind.NODE,
        prelude,
        body.getCoveredNodes() + 2,
        body.getCost() + 1
    ));
  }

  private List<String> bodyFilter(CypherExpression body, String predicate) {
    List<String> clauses = new ArrayList<>(body.getPreludeClauses());
    clauses.add("WITH *");
    clauses.add("WHERE " + predicate);
    return clauses;
  }

  private Optional<QueryLike> queryLike(ASTNode node) {
    if (node instanceof ASTIterator iterator) {
      return Optional.of(new QueryLike(iterator.source, iterator.body, iterator.operation, iterator.iteratorName));
    }
    if (node instanceof ASTQuery query && query.variables.size() <= 1) {
      return Optional.of(new QueryLike(query.source, query.body, query.operation, query.primaryVariableName()));
    }
    return Optional.empty();
  }

  private record QueryLike(ASTNode source, ASTNode body, String operation, String iteratorName) {}
}
