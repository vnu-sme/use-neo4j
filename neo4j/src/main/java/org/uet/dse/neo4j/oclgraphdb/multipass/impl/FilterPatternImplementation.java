package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

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

public class FilterPatternImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.FILTER;
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

    Optional<CypherExpression> source = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(query.get().source(), context)
        .map(type -> context.enterScope(query.get().iteratorName(), type))
        .orElseGet(() -> context.enterScope(query.get().iteratorName()));
    Optional<CypherExpression> body = pattern.getChildren().size() > 1
        ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(query.get().body(), scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String predicate = "reject".equalsIgnoreCase(query.get().operation())
        ? "NOT (" + body.get().getExpression() + ")"
        : body.get().getExpression();
    String expression = "[" + query.get().iteratorName() + " IN " + source.get().getExpression() + " WHERE " + predicate + "]";
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.COLLECTION, source.get(), body.get()));
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
