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

public class AttributeAccessImplementation implements PatternImplementation {
  @Override
  public boolean supports(PatternInstance pattern) {
    return pattern.getKind() == MultiPassPatternKind.ATTRIBUTE_ACCESS
        || pattern.getKind() == MultiPassPatternKind.BOOLEAN_ATTRIBUTE_ACCESS;
  }

  @Override
  public Optional<CypherExpression> compile(
      PatternInstance pattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    if (!(pattern.getAstNode() instanceof ASTProperty property)) return Optional.empty();
    Optional<CypherExpression> source = compiler.compileLegacy(property.source, context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    boolean collection = Boolean.parseBoolean(pattern.getMetadata().getOrDefault("collection", "false"));
    String expression = collection
        ? CypherSupport.attributeValues(source.get().getExpression(), property.name, context)
        : CypherSupport.attributeValue(source.get().getExpression(), property.name, context);
    if (pattern.getKind() == MultiPassPatternKind.BOOLEAN_ATTRIBUTE_ACCESS) {
      return Optional.of(CypherExpression.combine(CypherSupport.booleanValue(expression), ExpressionKind.BOOLEAN, source.get()));
    }
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.VALUE;
    return Optional.of(CypherExpression.combine(expression, kind, source.get()));
  }
}
