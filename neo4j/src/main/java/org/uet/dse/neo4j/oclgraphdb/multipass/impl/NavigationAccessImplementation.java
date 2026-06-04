package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;

public class NavigationAccessImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.NAVIGATION_ACCESS
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
    if (!(navigation.getAstNode() instanceof ASTProperty property)) return Optional.empty();
    Optional<CypherExpression> source = compiler.compileLegacy(property.source, context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = Boolean.parseBoolean(navigation.getMetadata().getOrDefault("collection", "true"));
    String nav = CypherSupport.navigation(source.get().getExpression(), property.name, context);
    String expression = collection ? nav : "head(" + nav + ")";
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.NODE;
    return Optional.of(CypherExpression.combine(expression, kind, source.get()));
  }
}
