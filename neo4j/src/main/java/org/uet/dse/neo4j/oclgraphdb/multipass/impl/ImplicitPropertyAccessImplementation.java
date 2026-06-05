package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public class ImplicitPropertyAccessImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.IMPLICIT_ITERATOR_PROPERTY_ACCESS
        || pattern.getKind() == MultiPassPatternKind.IMPLICIT_SELF_PROPERTY_ACCESS;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTVar property)) return Optional.empty();
    String baseVar = pattern.getMetadata().get("baseVar");
    String propertyKind = pattern.getMetadata().getOrDefault("propertyKind", "");
    if (baseVar == null || baseVar.isBlank()) return Optional.empty();

    Optional<CypherExpression> source = compiler.compileLegacy(new ASTVar(baseVar), context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = Boolean.parseBoolean(pattern.getMetadata().getOrDefault("collection", "false"));
    if ("navigation".equals(propertyKind)) {
      String navigation = CypherSupport.navigation(source.get().getExpression(), property.name, context);
      String expression = collection ? navigation : "head(" + navigation + ")";
      ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.NODE;
      return Optional.of(CypherExpression.combine(expression, kind, source.get()));
    }

    String attribute = collection
        ? CypherSupport.attributeValues(source.get().getExpression(), property.name, context)
        : CypherSupport.attributeValue(source.get().getExpression(), property.name, context);
    if (pattern.getOutputKind() == ExpressionKind.BOOLEAN) {
      return Optional.of(CypherExpression.combine(CypherSupport.booleanValue(attribute), ExpressionKind.BOOLEAN, source.get()));
    }
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.VALUE;
    return Optional.of(CypherExpression.combine(attribute, kind, source.get()));
  }
}
