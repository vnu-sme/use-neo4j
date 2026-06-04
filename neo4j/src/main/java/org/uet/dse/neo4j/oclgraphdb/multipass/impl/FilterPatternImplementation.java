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
    if (!(pattern.getAstNode() instanceof ASTIterator iterator)) return Optional.empty();
    if (pattern.getChildren().isEmpty()) return Optional.empty();

    Optional<CypherExpression> source = compiler.compileExpression(pattern.getChildren().get(0), context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(iterator.source, context)
        .map(type -> context.enterScope(iterator.iteratorName, type))
        .orElseGet(() -> context.enterScope(iterator.iteratorName));
    Optional<CypherExpression> body = pattern.getChildren().size() > 1
        ? compiler.compileExpression(pattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(iterator.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String predicate = "reject".equalsIgnoreCase(iterator.operation)
        ? "NOT (" + body.get().getExpression() + ")"
        : body.get().getExpression();
    String expression = "[" + iterator.iteratorName + " IN " + source.get().getExpression() + " WHERE " + predicate + "]";
    return Optional.of(CypherExpression.combine(expression, ExpressionKind.COLLECTION, source.get(), body.get()));
  }
}
