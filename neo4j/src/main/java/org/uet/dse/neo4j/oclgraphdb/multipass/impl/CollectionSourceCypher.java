package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;

final class CollectionSourceCypher {
  private CollectionSourceCypher() {}

  static Optional<List<String>> matchClauses(
      PatternInstance sourcePattern,
      String targetVar,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (sourcePattern.getKind() == MultiPassPatternKind.ALL_INSTANCES_SOURCE) {
      String className = sourcePattern.getMetadata().get("class");
      if (className == null || className.isBlank()) return Optional.empty();
      return Optional.of(CypherSupport.allInstancesMatch(className, targetVar, context));
    }

    if (sourcePattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE) {
      return navigationMatchClauses(sourcePattern, targetVar, compiler, context);
    }

    if (sourcePattern.getKind() == MultiPassPatternKind.FILTER) {
      return filterMatchClauses(sourcePattern, targetVar, compiler, context);
    }

    return Optional.empty();
  }

  static List<String> indent(List<String> lines, String prefix) {
    List<String> result = new ArrayList<>();
    for (String line : lines) result.add(prefix + line);
    return result;
  }

  private static Optional<List<String>> navigationMatchClauses(
      PatternInstance sourcePattern,
      String targetVar,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (sourcePattern.getChildren().isEmpty()
        || !(sourcePattern.getChildren().get(0).getAstNode() instanceof ASTProperty property)) {
      return Optional.empty();
    }

    Optional<CypherExpression> sourceNode = compiler.compileLegacy(property.source, context, ExpectedKind.NODE);
    if (sourceNode.isEmpty()) return Optional.empty();

    List<String> clauses = new ArrayList<>(sourceNode.get().getPreludeClauses());
    clauses.addAll(CypherSupport.navigationMatch(sourceNode.get().getExpression(), property.name, targetVar, context));
    return Optional.of(clauses);
  }

  private static Optional<List<String>> filterMatchClauses(
      PatternInstance filterPattern,
      String targetVar,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(filterPattern.getAstNode() instanceof ASTIterator iterator)) return Optional.empty();
    if (filterPattern.getChildren().isEmpty()) return Optional.empty();

    String filterVar = iterator.iteratorName;
    Optional<List<String>> sourceClauses = matchClauses(filterPattern.getChildren().get(0), filterVar, compiler, context);
    if (sourceClauses.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(iterator.source, context)
        .map(type -> context.enterScope(filterVar, type))
        .orElseGet(() -> context.enterScope(filterVar));
    Optional<CypherExpression> body = filterPattern.getChildren().size() > 1
        ? compiler.compileExpression(filterPattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(iterator.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String predicate = "reject".equalsIgnoreCase(iterator.operation)
        ? "NOT (" + body.get().getExpression() + ")"
        : body.get().getExpression();

    List<String> clauses = new ArrayList<>(sourceClauses.get());
    clauses.addAll(body.get().getPreludeClauses());
    clauses.add("WITH *");
    clauses.add("WHERE " + predicate);
    if (filterVar.equals(targetVar)) {
      clauses.add("WITH *");
    } else {
      clauses.add("WITH *, " + filterVar + " AS " + targetVar);
    }
    return Optional.of(clauses);
  }
}
