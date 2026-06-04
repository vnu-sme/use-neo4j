package org.uet.dse.neo4j.oclgraphdb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CypherPlan {
  private final List<String> clauses;
  private final Map<String, Object> params;

  public CypherPlan(List<String> clauses, Map<String, Object> params) {
    this.clauses = List.copyOf(clauses);
    this.params = params;
  }

  public static CypherPlan scalar(CypherExpression expression, CompileContext context) {
    List<String> clauses = new ArrayList<>();
    clauses.addAll(expression.getPreludeClauses());
    clauses.add("RETURN " + expression.getExpression() + " AS result");
    return new CypherPlan(clauses, context.params());
  }

  public static CypherPlan validation(String className, CypherExpression predicate, CompileContext context) {
    String classParam = context.addParam("contextClass", className);
    List<String> clauses = new ArrayList<>();
    clauses.add("MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(target {name: " + classParam + "})");
    clauses.add("MATCH (cls)-[:Extends*0..]->(target)");
    clauses.add("OPTIONAL MATCH (self)-[:ObjectInstanceOf]->(cls)");
    clauses.add("WITH [instance IN collect(DISTINCT self) WHERE instance IS NOT NULL] AS instances");
    clauses.add("CALL {");
    clauses.add("  WITH instances");
    clauses.add("  UNWIND instances AS self");
    clauses.addAll(indent(predicate.getPreludeClauses(), "  "));
    clauses.add("  WITH *");
    clauses.add("  WHERE NOT (" + predicate.getExpression() + ")");
    clauses.add("  RETURN collect(self.use_id) AS violations");
    clauses.add("}");
    clauses.add("RETURN size(instances) AS total, violations");
    return new CypherPlan(clauses, context.params());
  }

  public String toCypher() {
    return String.join("\n", clauses);
  }

  public Map<String, Object> getParams() {
    return params;
  }

  private static List<String> indent(List<String> clauses, String prefix) {
    List<String> result = new ArrayList<>();
    for (String clause : clauses) result.add(prefix + clause);
    return result;
  }
}
