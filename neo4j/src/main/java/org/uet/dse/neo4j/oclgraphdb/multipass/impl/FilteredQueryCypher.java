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

final class FilteredQueryCypher {
  private FilteredQueryCypher() {}

  static Optional<CypherExpression> countMatches(
      PatternInstance filter,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    Optional<FilterData> data = filterData(filter, compiler, context);
    if (data.isEmpty()) return Optional.empty();

    String countVar = context.nextName("count");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(data.get().matchClauses(), "  "));
    prelude.addAll(CollectionSourceCypher.indent(data.get().body().getPreludeClauses(), "  "));
    prelude.add("  WITH *");
    prelude.add("  WHERE " + data.get().predicate());
    prelude.add("  RETURN count(DISTINCT " + data.get().iteratorName() + ") AS " + countVar);
    prelude.add("}");

    CypherExpression body = data.get().body();
    return Optional.of(new CypherExpression(
        countVar,
        ExpressionKind.VALUE,
        prelude,
        body.getCoveredNodes() + 3,
        body.getCost() + 1
    ));
  }

  static Optional<CypherExpression> existsMatches(
      PatternInstance filter,
      boolean desiredExists,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    Optional<FilterData> data = filterData(filter, compiler, context);
    if (data.isEmpty()) return Optional.empty();

    List<String> clauses = new ArrayList<>();
    clauses.add("EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(data.get().matchClauses(), "  "));
    clauses.addAll(CollectionSourceCypher.indent(data.get().body().getPreludeClauses(), "  "));
    clauses.add("  WITH *");
    clauses.add("  WHERE " + data.get().predicate());
    clauses.add("}");
    String existsExpression = String.join("\n", clauses);
    String expression = desiredExists ? existsExpression : "NOT (" + existsExpression + ")";
    CypherExpression body = data.get().body();
    return Optional.of(new CypherExpression(
        expression,
        ExpressionKind.BOOLEAN,
        List.of(),
        body.getCoveredNodes() + 3,
        body.getCost() + 1
    ));
  }

  private static Optional<FilterData> filterData(
      PatternInstance filter,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (filter.getKind() != MultiPassPatternKind.FILTER) return Optional.empty();
    Optional<QueryLike> query = queryLike(filter.getAstNode());
    if (query.isEmpty() || filter.getChildren().isEmpty()) return Optional.empty();

    PatternInstance source = filter.getChildren().get(0);
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(source, query.get().iteratorName(), compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(query.get().source(), context)
        .map(type -> context.enterScope(query.get().iteratorName(), type))
        .orElseGet(() -> context.enterScope(query.get().iteratorName()));
    Optional<CypherExpression> body = filter.getChildren().size() > 1
        ? compiler.compileExpression(filter.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(query.get().body(), scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String predicate = "reject".equalsIgnoreCase(query.get().operation())
        ? "NOT (" + body.get().getExpression() + ")"
        : body.get().getExpression();
    return Optional.of(new FilterData(query.get().iteratorName(), matchClauses.get(), body.get(), predicate));
  }

  private static Optional<QueryLike> queryLike(ASTNode node) {
    if (node instanceof ASTIterator iterator) {
      return Optional.of(new QueryLike(iterator.source, iterator.body, iterator.operation, iterator.iteratorName));
    }
    if (node instanceof ASTQuery query && query.variables.size() <= 1) {
      return Optional.of(new QueryLike(query.source, query.body, query.operation, query.primaryVariableName()));
    }
    return Optional.empty();
  }

  private record QueryLike(ASTNode source, ASTNode body, String operation, String iteratorName) {}

  private record FilterData(
      String iteratorName,
      List<String> matchClauses,
      CypherExpression body,
      String predicate
  ) {}
}
