package org.uet.dse.neo4j.oclgraphdb.multipass.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

final class CollectionSourceTerminalCypher {
  private CollectionSourceTerminalCypher() {}

  static Optional<CypherExpression> count(
      PatternInstance sourcePattern,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    String item = context.nextName("item");
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(sourcePattern, item, compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    String countVar = context.nextName("count");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    prelude.add("  RETURN count(DISTINCT " + item + ") AS " + countVar);
    prelude.add("}");
    return Optional.of(new CypherExpression(countVar, ExpressionKind.VALUE, prelude, 2, 1));
  }

  static Optional<CypherExpression> exists(
      PatternInstance sourcePattern,
      boolean desiredExists,
      MultiPassCypherCompiler compiler,
      CompileContext context
  ) {
    String item = context.nextName("item");
    Optional<List<String>> matchClauses = CollectionSourceCypher.matchClauses(sourcePattern, item, compiler, context);
    if (matchClauses.isEmpty()) return Optional.empty();

    List<String> clauses = new ArrayList<>();
    clauses.add("EXISTS {");
    clauses.addAll(CollectionSourceCypher.indent(matchClauses.get(), "  "));
    clauses.add("}");
    String exists = String.join("\n", clauses);
    String expression = desiredExists ? exists : "NOT (" + exists + ")";
    return Optional.of(new CypherExpression(expression, ExpressionKind.BOOLEAN, List.of(), 2, 1));
  }
}
