package org.uet.dse.neo4j.logging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.neo4j.driver.Value;
import org.uet.dse.neo4j.manager.WorkLogManager;

public final class CypherQueryLogger {
  private CypherQueryLogger() {}

  public static void log(String label, String cypher) {
    log(label, cypher, Map.of());
  }

  public static void log(String label, String cypher, Value params) {
    log(label, cypher, params == null || params.isNull() ? Map.of() : params.asMap());
  }

  public static void log(String label, String cypher, Map<String, Object> params) {
    String logMessage = formatLog(label, cypher, params);
    System.out.println(logMessage);
    WorkLogManager.getInstance().log("CYPHER_EXECUTE", logMessage);
  }

  private static String formatLog(String label, String cypher, Map<String, Object> params) {
    return "\n--- Cypher [" + label + "] ---\n"
        + "-- template --\n"
        + cypher + "\n"
        + "-- params --\n"
        + formatParams(params) + "\n"
        + "-- rendered --\n"
        + render(cypher, params) + "\n"
        + "--- end Cypher [" + label + "] ---\n";
  }

  public static String render(String cypher, Map<String, Object> params) {
    if (params == null || params.isEmpty()) return cypher;

    String rendered = cypher;
    List<String> keys = new ArrayList<>(params.keySet());
    keys.sort(Comparator.comparingInt(String::length).reversed());
    for (String key : keys) {
      Pattern pattern = Pattern.compile("\\$" + Pattern.quote(key) + "(?![A-Za-z0-9_])");
      Matcher matcher = pattern.matcher(rendered);
      rendered = matcher.replaceAll(Matcher.quoteReplacement(toCypherLiteral(params.get(key))));
    }
    return rendered;
  }

  private static String formatParams(Map<String, Object> params) {
    if (params == null || params.isEmpty()) return "{}";

    StringJoiner joiner = new StringJoiner(", ", "{", "}");
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      joiner.add(entry.getKey() + ": " + toCypherLiteral(entry.getValue()));
    }
    return joiner.toString();
  }

  private static String toCypherLiteral(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Iterable<?> iterable) {
      StringJoiner joiner = new StringJoiner(", ", "[", "]");
      for (Object item : iterable) {
        joiner.add(toCypherLiteral(item));
      }
      return joiner.toString();
    }
    if (value instanceof Map<?, ?> map) {
      StringJoiner joiner = new StringJoiner(", ", "{", "}");
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        joiner.add(entry.getKey() + ": " + toCypherLiteral(entry.getValue()));
      }
      return joiner.toString();
    }
    return "'" + value.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
  }
}
