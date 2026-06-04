package org.uet.dse.neo4j.oclgraphdb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CypherQueryLogger {
  private CypherQueryLogger() {}

  public static void log(String label, String cypher, Map<String, Object> params) {
    System.out.println("\n--- OCLGraphDB Cypher [" + label + "] ---");
    System.out.println("-- template --");
    System.out.println(cypher);
    System.out.println("-- params --");
    System.out.println(formatParams(params));
    System.out.println("-- rendered --");
    System.out.println(render(cypher, params));
    System.out.println("--- end OCLGraphDB Cypher [" + label + "] ---\n");
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
