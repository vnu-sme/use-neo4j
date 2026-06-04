package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;

public class PropertyPattern implements OclPattern {
  public static final PropertyPattern INSTANCE = new PropertyPattern();

  @Override
  public String name() {
    return "property";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTProperty;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTProperty prop = (ASTProperty) node;
    List<PlanCandidate> result = new ArrayList<>();

    planner.plan(prop.source, context, ExpectedKind.TYPE).ifPresent(source -> {
      if ("allInstances".equalsIgnoreCase(prop.name)) {
        String className = source.expression().getExpression();
        result.add(PlanCandidate.exact("allInstances-property", CypherSupport.allInstancesExpression(className, context), 1000));
      }
    });

    planner.plan(prop.source, context, ExpectedKind.NODE).ifPresent(source -> {
      Optional<String> sourceType = PatternCypherUtils.sourceType(prop.source, context);
      if (sourceType.isPresent()) {
        appendResolvedNodePropertyCandidates(result, source.expression(), sourceType.get(), prop.name, context, expected);
      } else {
        appendHeuristicNodePropertyCandidates(result, source.expression(), prop.name, context, expected);
      }
    });

    planner.plan(prop.source, context, ExpectedKind.COLLECTION).ifPresent(source -> {
      Optional<String> elementType = PatternCypherUtils.sourceType(prop.source, context);
      if (elementType.isPresent()) {
        appendResolvedCollectionPropertyCandidates(result, source.expression(), elementType.get(), prop.name, context, expected);
      } else {
        appendHeuristicCollectionPropertyCandidates(result, source.expression(), prop.name, context, expected);
      }
    });

    return result;
  }

  private void appendResolvedNodePropertyCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String sourceType,
      String propertyName,
      CompileContext context,
      ExpectedKind expected
  ) {
    Optional<PropertyDescriptor> attribute = context.resolver().resolveAttribute(sourceType, propertyName);
    Optional<PropertyDescriptor> navigation = context.resolver().resolveNavigation(sourceType, propertyName);

    if (attribute.isPresent()) {
      if (attribute.get().isCollection()) {
        if (acceptsCollection(expected)) {
          appendAttributeCollectionCandidate(result, source, propertyName, context, expected, true);
        }
      } else {
        appendAttributeValueCandidates(result, source, propertyName, context, expected, true);
      }
    }
    if (navigation.isPresent()) {
      appendNavigationCandidates(result, source, propertyName, navigation.get(), context, expected, true);
    }
  }

  private void appendResolvedCollectionPropertyCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String elementType,
      String propertyName,
      CompileContext context,
      ExpectedKind expected
  ) {
    Optional<PropertyDescriptor> attribute = context.resolver().resolveAttribute(elementType, propertyName);
    Optional<PropertyDescriptor> navigation = context.resolver().resolveNavigation(elementType, propertyName);

    if (attribute.isPresent() && acceptsCollection(expected)) {
      appendCollectionAttributeCandidate(result, source, propertyName, context, expected, true);
    }
    if (navigation.isPresent() && acceptsCollection(expected)) {
      appendCollectionNavigationCandidate(result, source, propertyName, context, expected, true);
    }
  }

  private void appendHeuristicNodePropertyCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected
  ) {
    appendAttributeValueCandidates(result, source, propertyName, context, expected, false);
    if (acceptsCollection(expected)) {
      appendAttributeCollectionCandidate(result, source, propertyName, context, expected, false);
    }
    appendNavigationCandidates(result, source, propertyName, null, context, expected, false);
  }

  private void appendHeuristicCollectionPropertyCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected
  ) {
    appendCollectionAttributeCandidate(result, source, propertyName, context, expected, false);
    appendCollectionNavigationCandidate(result, source, propertyName, context, expected, false);
  }

  private void appendAttributeValueCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected,
      boolean resolved
  ) {
    String attr = CypherSupport.attributeValue(source.getExpression(), propertyName, context);

    if (expected == ExpectedKind.ANY || expected == ExpectedKind.VALUE) {
      CypherExpression expr = CypherExpression.combine(attr, ExpressionKind.VALUE, source);
      result.add(PlanCandidate.exact(resolved ? "resolved-attribute-property" : "attribute-property", expr, expected == ExpectedKind.VALUE ? 1040 : 900));
    }

    if (expected == ExpectedKind.ANY || expected == ExpectedKind.BOOLEAN) {
      CypherExpression boolExpr = CypherExpression.combine(CypherSupport.booleanValue(attr), ExpressionKind.BOOLEAN, source);
      result.add(PlanCandidate.exact(resolved ? "resolved-boolean-attribute-property" : "boolean-attribute-property", boolExpr, expected == ExpectedKind.BOOLEAN ? 1030 : 780));
    }
  }

  private void appendNavigationCandidates(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      PropertyDescriptor descriptor,
      CompileContext context,
      ExpectedKind expected,
      boolean resolved
  ) {
    boolean unknownMultiplicity = descriptor == null;
    boolean many = descriptor != null && descriptor.isCollection();
    String nav = CypherSupport.navigation(source.getExpression(), propertyName, context);

    if ((unknownMultiplicity || !many) && (expected == ExpectedKind.ANY || expected == ExpectedKind.NODE)) {
      CypherExpression navNodeExpr = CypherExpression.combine("head(" + nav + ")", ExpressionKind.NODE, source);
      result.add(PlanCandidate.exact(resolved ? "resolved-single-navigation-property" : "single-navigation-property", navNodeExpr, expected == ExpectedKind.NODE ? 1040 : 900));
    }

    if ((unknownMultiplicity || many) && (expected == ExpectedKind.ANY || expected == ExpectedKind.COLLECTION)) {
      CypherExpression collectionExpr = CypherExpression.combine(nav, ExpressionKind.COLLECTION, source);
      result.add(PlanCandidate.exact(resolved ? "resolved-navigation-property" : "collection-navigation-property", collectionExpr, expected == ExpectedKind.COLLECTION ? 1040 : 860));
    }
  }

  private void appendAttributeCollectionCandidate(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected,
      boolean resolved
  ) {
    String attrValues = CypherSupport.attributeValues(source.getExpression(), propertyName, context);
    CypherExpression expr = CypherExpression.combine(attrValues, ExpressionKind.COLLECTION, source);
    result.add(PlanCandidate.exact(resolved ? "resolved-attribute-collection-property" : "attribute-collection-property", expr, expected == ExpectedKind.COLLECTION ? 1030 : 830));
  }

  private void appendCollectionAttributeCandidate(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected,
      boolean resolved
  ) {
    String item = context.nextName("item");
    String candidate = context.nextName("candidate");
    String attrValues = CypherSupport.attributeValues(item, propertyName, context);
    String exprText = "reduce(acc = [], " + item + " IN " + source.getExpression()
        + " | acc + [" + candidate + " IN " + attrValues + " WHERE NOT " + candidate + " IN acc])";
    CypherExpression expr = CypherExpression.combine(exprText, ExpressionKind.COLLECTION, source);
    result.add(PlanCandidate.exact(resolved ? "resolved-collection-attribute-property" : "collection-attribute-property", expr, expected == ExpectedKind.COLLECTION ? 1010 : 820));
  }

  private void appendCollectionNavigationCandidate(
      List<PlanCandidate> result,
      CypherExpression source,
      String propertyName,
      CompileContext context,
      ExpectedKind expected,
      boolean resolved
  ) {
    String item = context.nextName("item");
    String candidate = context.nextName("candidate");
    String nav = CypherSupport.navigation(item, propertyName, context);
    String navText = "reduce(acc = [], " + item + " IN " + source.getExpression()
        + " | acc + [" + candidate + " IN " + nav + " WHERE NOT " + candidate + " IN acc])";
    CypherExpression navExpr = CypherExpression.combine(navText, ExpressionKind.COLLECTION, source);
    result.add(PlanCandidate.exact(resolved ? "resolved-collection-navigation-property" : "collection-navigation-property", navExpr, expected == ExpectedKind.COLLECTION ? 1040 : 850));
  }

  private boolean acceptsCollection(ExpectedKind expected) {
    return expected == ExpectedKind.ANY || expected == ExpectedKind.COLLECTION;
  }
}
