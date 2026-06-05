package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTExpression;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTQualifiedNavigation;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public class NavigationAccessImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.NAVIGATION_ACCESS
        || pattern.getKind() == MultiPassPatternKind.QUALIFIED_NAVIGATION
        || pattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    PatternInstance navigation = pattern;
    if (pattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE && !pattern.getChildren().isEmpty()) {
      navigation = pattern.getChildren().get(0);
    }
    Optional<SourceNavigation> data = sourceNavigation(navigation);
    if (data.isEmpty()) return Optional.empty();

    Optional<CypherExpression> source = compiler.compileLegacy(data.get().source(), context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = pattern.getKind() == MultiPassPatternKind.NAVIGATION_SOURCE
        || Boolean.parseBoolean(navigation.getMetadata().getOrDefault("collection", "true"));
    Optional<String> qualifier = qualifierExpression(data.get().qualifier(), compiler, context);
    if (data.get().qualifier().isPresent() && qualifier.isEmpty()) return Optional.empty();
    String nav = qualifier
        .map(value -> CypherSupport.qualifiedNavigation(source.get().getExpression(), data.get().role(), value, context))
        .orElseGet(() -> CypherSupport.navigation(source.get().getExpression(), data.get().role(), context));
    String expression = collection ? nav : "head(" + nav + ")";
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.NODE;
    return Optional.of(CypherExpression.combine(expression, kind, source.get()));
  }

  private Optional<SourceNavigation> sourceNavigation(PatternInstance navigation) {
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

  private Optional<String> qualifierExpression(
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

  private record SourceNavigation(ASTExpression source, String role, Optional<ASTExpression> qualifier) {}
}
