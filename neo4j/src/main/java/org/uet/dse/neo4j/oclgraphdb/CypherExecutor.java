package org.uet.dse.neo4j.oclgraphdb;

import java.util.List;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherPlan;

public class CypherExecutor {
  public Object executeScalar(CypherPlan plan) {
    CypherQueryLogger.log("execute-scalar", plan.toCypher(), plan.getParams());
    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      Result result = session.run(plan.toCypher(), plan.getParams());
      if (!result.hasNext()) return null;
      Record record = result.next();
      if (record.size() == 0) return null;
      return record.get(0).asObject();
    }
  }

  public ValidationSummary executeValidation(CypherPlan plan) {
    CypherQueryLogger.log("execute-validation", plan.toCypher(), plan.getParams());
    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      Result result = session.run(plan.toCypher(), plan.getParams());
      if (!result.hasNext()) return new ValidationSummary(0, List.of());

      Record record = result.next();
      int total = record.get("total").asInt(0);
      List<String> violations = record.get("violations").asList(v -> v.isNull() ? null : v.asString());
      violations = violations.stream().filter(v -> v != null && !v.isBlank()).toList();
      return new ValidationSummary(total, violations);
    }
  }

  public record ValidationSummary(int total, List<String> violations) {
    public String format(String className, String invariantName) {
      if (total == 0) {
        return "No instances of class '" + className + "' found to validate.";
      }
      if (violations.isEmpty()) {
        return "SUCCESS: All " + total + " instances of '" + className + "' satisfy the invariant.";
      }

      StringBuilder report = new StringBuilder();
      report.append("FAILURE: ").append(violations.size()).append("/").append(total).append(" violations found.\n");
      report.append("--- Invariant: ").append(invariantName).append(" ---\n");
      for (String violation : violations) {
        report.append("  [!] Violation: ").append(violation).append("\n");
      }
      return report.toString();
    }
  }
}
