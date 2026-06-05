package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTQualifiedNavigation;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public class QualifiedNavigationPattern implements OclPattern {
  @Override
  public String name() {
    return "qualified-navigation";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTQualifiedNavigation;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTQualifiedNavigation navigation = (ASTQualifiedNavigation) node;
    Optional<PlanCandidate> source = planner.plan(navigation.source, context, ExpectedKind.NODE);
    Optional<String> qualifier = qualifierExpression(navigation, context, planner);
    if (source.isEmpty() || qualifier.isEmpty()) return List.of();

    Optional<String> sourceType = PatternCypherUtils.sourceType(navigation.source, context);
    Optional<PropertyDescriptor> descriptor = sourceType.flatMap(type -> context.resolver().resolveNavigation(type, navigation.roleName));
    boolean unknownMultiplicity = descriptor.isEmpty();
    boolean many = descriptor.map(PropertyDescriptor::isCollection).orElse(false);

    String nav = CypherSupport.qualifiedNavigation(
        source.get().expression().getExpression(),
        navigation.roleName,
        qualifier.get(),
        context
    );
    List<PlanCandidate> result = new ArrayList<>();
    if ((unknownMultiplicity || !many) && (expected == ExpectedKind.ANY || expected == ExpectedKind.NODE)) {
      CypherExpression expr = CypherExpression.combine("head(" + nav + ")", ExpressionKind.NODE, source.get().expression());
      result.add(PlanCandidate.exact("qualified-single-navigation", expr, expected == ExpectedKind.NODE ? 1040 : 900));
    }
    if ((unknownMultiplicity || many) && (expected == ExpectedKind.ANY || expected == ExpectedKind.COLLECTION)) {
      CypherExpression expr = CypherExpression.combine(nav, ExpressionKind.COLLECTION, source.get().expression());
      result.add(PlanCandidate.exact("qualified-navigation", expr, expected == ExpectedKind.COLLECTION ? 1040 : 900));
    }
    return result;
  }

  private Optional<String> qualifierExpression(
      ASTQualifiedNavigation navigation,
      CompileContext context,
      PatternPlanner planner
  ) {
    if (navigation.qualifier instanceof ASTVar var
        && !context.isLocalVariable(var.name)
        && !context.hasExpressionBinding(var.name)
        && !context.hasObjectBinding(var.name)
        && !context.resolver().isClass(var.name)) {
      return Optional.of(context.addParam("qualifier", var.name));
    }
    return planner.plan(navigation.qualifier, context, ExpectedKind.ANY)
        .map(candidate -> candidate.expression().getExpression());
  }
}
