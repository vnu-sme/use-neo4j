package org.uet.dse.neo4j.oclgraphdb.cypher;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public final class CypherSupport {
  private CypherSupport() {}

  public static String attributeValue(String nodeExpr, String attrName, CompileContext context) {
    String val = context.nextName("attr");
    String ref = context.nextName("ref");
    String suffix = context.addParam("attrSuffix", "_" + attrName);
    String refs = "[(" + val + ")-[:HasReferenceValue]->(" + ref + ") | " + ref + "]";
    return "head([(" + nodeExpr + ")-[:ObjectHasAttribute]->(" + val + ":AttributeValue) "
        + "WHERE " + val + ".name ENDS WITH " + suffix + " | "
        + "CASE WHEN size(" + refs + ") > 0 THEN " + refs + " ELSE " + val + ".value END])";
  }

  public static String attributeValues(String nodeExpr, String attrName, CompileContext context) {
    String val = context.nextName("attr");
    String ref = context.nextName("ref");
    String part = context.nextName("part");
    String suffix = context.addParam("attrSuffix", "_" + attrName);
    String refs = "[(" + val + ")-[:HasReferenceValue]->(" + ref + ") | " + ref + "]";
    String raw = "coalesce(toString(" + val + ".value), '')";
    String normalizedPart = "replace(replace(trim(" + part + "), \"'\", \"\"), \"#\", \"\")";
    String normalizedRaw = "replace(replace(" + raw + ", \"'\", \"\"), \"#\", \"\")";
    String primitiveValues = "[" + part + " IN split(" + raw + ", '|') "
        + "WHERE " + normalizedPart + " <> '' "
        + "AND " + normalizedPart + " <> 'Undefined' "
        + "AND " + normalizedPart + " <> 'COLLECTION_EMPTY' | " + normalizedPart + "]";

    return "coalesce(head([(" + nodeExpr + ")-[:ObjectHasAttribute]->(" + val + ":AttributeValue) "
        + "WHERE " + val + ".name ENDS WITH " + suffix + " | "
        + "CASE "
        + "WHEN size(" + refs + ") > 0 THEN " + refs + " "
        + "WHEN coalesce(" + val + ".isCollection, false) THEN " + primitiveValues + " "
        + "WHEN " + val + ".value IS NULL OR " + normalizedRaw + " IN ['Undefined', 'COLLECTION_EMPTY'] THEN [] "
        + "ELSE [" + val + ".value] END]), [])";
  }

  public static String navigation(String nodeExpr, String roleName, CompileContext context) {
    String rel = context.nextName("rel");
    String target = context.nextName("target");
    String role = context.addParam("role", roleName);
    return "[(" + nodeExpr + ")-[" + rel + "]-(" + target + ") "
        + "WHERE type(" + rel + ") STARTS WITH 'Link' AND ("
        + "(startNode(" + rel + ") = " + nodeExpr + " AND (" + rel + ".targetRole = " + role + " OR " + rel + ".name = " + role + ")) "
        + "OR (endNode(" + rel + ") = " + nodeExpr + " AND (" + rel + ".sourceRole = " + role + " OR " + rel + ".name = " + role + "))"
        + ") | " + target + "]";
  }

  public static List<String> navigationMatch(String nodeExpr, String roleName, String targetVar, CompileContext context) {
    String rel = context.nextName("rel");
    String role = context.addParam("role", roleName);
    return List.of(
        "MATCH (" + nodeExpr + ")-[" + rel + "]-(" + targetVar + ")",
        "WHERE type(" + rel + ") STARTS WITH 'Link' AND ("
            + "(startNode(" + rel + ") = " + nodeExpr + " AND (" + rel + ".targetRole = " + role + " OR " + rel + ".name = " + role + ")) "
            + "OR (endNode(" + rel + ") = " + nodeExpr + " AND (" + rel + ".sourceRole = " + role + " OR " + rel + ".name = " + role + "))"
            + ")"
    );
  }

  public static CypherExpression allInstancesExpression(String className, CompileContext context) {
    String coll = context.nextName("instances");
    String obj = context.nextName("obj");

    List<String> prelude = new java.util.ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(allInstancesMatch(className, obj, context));
    prelude.add("RETURN collect(DISTINCT " + obj + ") AS " + coll);
    prelude.add("}");
    return new CypherExpression(coll, ExpressionKind.COLLECTION, prelude, 2, 10);
  }

  public static List<String> allInstancesMatch(String className, String objectVar, CompileContext context) {
    String target = context.nextName("class");
    String cls = context.nextName("cls");
    String classParam = context.addParam("className", className);
    return List.of(
        "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(" + target + " {name: " + classParam + "})",
        "MATCH (" + cls + ")-[:Extends*0..]->(" + target + ")",
        "MATCH (" + objectVar + ")-[:ObjectInstanceOf]->(" + cls + ")"
    );
  }

  public static String normalizeString(String expr) {
    return "replace(replace(toString(" + expr + "), \"'\", \"\"), \"#\", \"\")";
  }

  public static String numeric(String expr) {
    return "toFloat(" + normalizeString(expr) + ")";
  }

  public static String booleanValue(String expr) {
    return "toBoolean(" + normalizeString(expr) + ")";
  }

  public static String literal(Object value, CompileContext context) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return context.addParam("lit", value);
    return context.addParam("lit", value.toString());
  }

  public static CypherExpression asBoolean(String expression, CypherExpression... children) {
    return CypherExpression.combine(expression, ExpressionKind.BOOLEAN, children);
  }
}
