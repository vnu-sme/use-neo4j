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
import org.uet.dse.neo4j.oclite.ast.ASTExpression;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTQualifiedNavigation;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

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
        || sourcePattern.getChildren().get(0) == null) {
      return Optional.empty();
    }
    Optional<SourceNavigation> navigation = sourceNavigation(sourcePattern.getChildren().get(0));
    if (navigation.isEmpty()) return Optional.empty();

    Optional<CypherExpression> sourceNode = compiler.compileLegacy(navigation.get().source(), context, ExpectedKind.NODE);
    if (sourceNode.isEmpty()) return Optional.empty();
    Optional<String> qualifier = qualifierExpression(navigation.get().qualifier(), compiler, context);
    if (navigation.get().qualifier().isPresent() && qualifier.isEmpty()) return Optional.empty();

    List<String> clauses = new ArrayList<>(sourceNode.get().getPreludeClauses());
    clauses.addAll(qualifier
        .map(value -> CypherSupport.qualifiedNavigationMatch(sourceNode.get().getExpression(), navigation.get().role(), value, targetVar, context))
        .orElseGet(() -> CypherSupport.navigationMatch(sourceNode.get().getExpression(), navigation.get().role(), targetVar, context)));
    return Optional.of(clauses);
  }

  private static Optional<SourceNavigation> sourceNavigation(PatternInstance navigation) {
    if (navigation.getAstNode() instanceof ASTProperty property) {
      return Optional.of(new SourceNavigation(property.source, property.name, Optional.empty()));
    }
    if (navigation.getAstNode() instanceof ASTQualifiedNavigation property) {
      return Optional.of(new SourceNavigation(property.source, property.roleName, Optional.of(property.qualifier)));
    }
    if (navigation.getAstNode() instanceof ASTVar property) {
      String baseVar = navigation.getMetadata().get("baseVar");
      if (baseVar == null || baseVar.isBlank()) return Optional.empty();
      return Optional.of(new SourceNavigation(new ASTVar(baseVar), property.name, Optional.empty()));
    }
    return Optional.empty();
  }

  private static Optional<String> qualifierExpression(
      Optional<ASTExpression> qualifier,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (qualifier.isEmpty()) return Optional.empty();
    if (qualifier.get() instanceof ASTVar var
        && !context.isLocalVariable(var.name)
        && !context.hasExpressionBinding(var.name)
        && !context.hasObjectBinding(var.name)
        && !context.resolver().isClass(var.name)) {
      return Optional.of(context.addParam("qualifier", var.name));
    }
    return compiler.compileLegacy(qualifier.get(), context, ExpectedKind.ANY)
        .map(CypherExpression::getExpression);
  }

  private static Optional<List<String>> filterMatchClauses(
      PatternInstance filterPattern,
      String targetVar,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    Optional<QueryLike> query = queryLike(filterPattern.getAstNode());
    if (query.isEmpty()) return Optional.empty();
    if (filterPattern.getChildren().isEmpty()) return Optional.empty();

    String filterVar = query.get().iteratorName();
    Optional<List<String>> sourceClauses = matchClauses(filterPattern.getChildren().get(0), filterVar, compiler, context);
    if (sourceClauses.isEmpty()) return Optional.empty();

    CompileContext scoped = AstPatternSupport.inferCollectionElementType(query.get().source(), context)
        .map(type -> context.enterScope(filterVar, type))
        .orElseGet(() -> context.enterScope(filterVar));
    Optional<CypherExpression> body = filterPattern.getChildren().size() > 1
        ? compiler.compileExpression(filterPattern.getChildren().get(1), scoped, ExpectedKind.BOOLEAN)
        : compiler.compileLegacy(query.get().body(), scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return Optional.empty();

    String predicate = "reject".equalsIgnoreCase(query.get().operation())
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

  private record SourceNavigation(ASTExpression source, String role, Optional<ASTExpression> qualifier) {}
}
