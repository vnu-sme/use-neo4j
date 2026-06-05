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
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public class VariablePattern implements OclPattern {
  @Override
  public String name() {
    return "variable";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTVar;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    String name = ((ASTVar) node).name;

    if (context.hasExpressionBinding(name)) {
      return List.of(PlanCandidate.exact(name(), context.expressionBinding(name), 980));
    }

    if (context.hasObjectBinding(name)) {
      List<String> prelude = new ArrayList<>();
      String idParam = context.addParam("objectId", context.objectBinding(name));
      String cls = context.nextName("cls");
      prelude.add("MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(" + cls + ")");
      prelude.add("MATCH (" + name + " {use_id: " + idParam + "})-[:ObjectInstanceOf]->(" + cls + ")");
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE, prelude, 1, 1), 980));
    }

    if (context.isLocalVariable(name)) {
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE), 950));
    }

    if (context.resolver().isClass(name)) {
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.TYPE), 900));
    }

    if (context.resolver().isObject(name)) {
      List<String> prelude = new ArrayList<>();
      String idParam = context.addParam("objectId", name);
      String cls = context.nextName("cls");
      prelude.add("MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(" + cls + ")");
      prelude.add("MATCH (" + name + " {use_id: " + idParam + "})-[:ObjectInstanceOf]->(" + cls + ")");
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE, prelude, 1, 1), 850));
    }

    List<PlanCandidate> implicitProperties = implicitPropertyCandidates(name, context, expected, planner);
    if (!implicitProperties.isEmpty()) return implicitProperties;

    return List.of();
  }

  private List<PlanCandidate> implicitPropertyCandidates(
      String propertyName,
      CompileContext context,
      ExpectedKind expected,
      PatternPlanner planner
  ) {
    for (String baseVar : context.visibleVariables()) {
      Optional<String> type = context.variableType(baseVar);
      if (type.isEmpty()) continue;

      Optional<PlanCandidate> source = planner.plan(new ASTVar(baseVar), context, ExpectedKind.NODE);
      if (source.isEmpty()) continue;

      Optional<PropertyDescriptor> attribute = context.resolver().resolveAttribute(type.get(), propertyName);
      if (attribute.isPresent()) {
        return implicitAttribute(propertyName, source.get().expression(), attribute.get(), context, expected);
      }

      Optional<PropertyDescriptor> navigation = context.resolver().resolveNavigation(type.get(), propertyName);
      if (navigation.isPresent()) {
        return implicitNavigation(propertyName, source.get().expression(), navigation.get(), context, expected);
      }
    }
    return List.of();
  }

  private List<PlanCandidate> implicitAttribute(
      String propertyName,
      CypherExpression source,
      PropertyDescriptor descriptor,
      CompileContext context,
      ExpectedKind expected
  ) {
    String expression = descriptor.isCollection()
        ? CypherSupport.attributeValues(source.getExpression(), propertyName, context)
        : CypherSupport.attributeValue(source.getExpression(), propertyName, context);
    ExpressionKind kind = descriptor.isCollection() ? ExpressionKind.COLLECTION : ExpressionKind.VALUE;
    List<PlanCandidate> result = new ArrayList<>();
    if (expected.accepts(kind)) {
      result.add(PlanCandidate.exact("implicit-attribute-property", CypherExpression.combine(expression, kind, source), 910));
    }
    if (!descriptor.isCollection() && isBooleanType(descriptor.getValueType()) && expected.accepts(ExpressionKind.BOOLEAN)) {
      result.add(PlanCandidate.exact("implicit-boolean-attribute-property", CypherExpression.combine(CypherSupport.booleanValue(expression), ExpressionKind.BOOLEAN, source), 920));
    }
    return result;
  }

  private List<PlanCandidate> implicitNavigation(
      String propertyName,
      CypherExpression source,
      PropertyDescriptor descriptor,
      CompileContext context,
      ExpectedKind expected
  ) {
    String navigation = CypherSupport.navigation(source.getExpression(), propertyName, context);
    boolean collection = descriptor.isCollection();
    ExpressionKind kind = collection ? ExpressionKind.COLLECTION : ExpressionKind.NODE;
    if (!expected.accepts(kind)) return List.of();
    String expression = collection ? navigation : "head(" + navigation + ")";
    return List.of(PlanCandidate.exact("implicit-navigation-property", CypherExpression.combine(expression, kind, source), 910));
  }

  private boolean isBooleanType(String valueType) {
    if (valueType == null) return false;
    String normalized = valueType.trim().toLowerCase();
    return "boolean".equals(normalized) || "bool".equals(normalized);
  }
}
