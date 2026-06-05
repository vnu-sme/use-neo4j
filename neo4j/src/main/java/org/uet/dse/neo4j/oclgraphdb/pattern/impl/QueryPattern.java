package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTQuery;
import org.uet.dse.neo4j.oclite.ast.ASTVarDecl;

public class QueryPattern implements OclPattern {
  @Override
  public String name() {
    return "query";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTQuery;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTQuery query = (ASTQuery) node;
    Optional<PlanCandidate> source = planner.plan(query.source, context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return List.of();

    List<ASTVarDecl> variables = variables(query, context);
    CompileContext scoped = enterVariables(context, variables, PatternCypherUtils.collectionElementType(query.source, context).orElse(null));
    ExpectedKind bodyExpected = bodyExpected(query.operation);
    Optional<PlanCandidate> body = planner.plan(query.body, scoped, bodyExpected);
    if (body.isEmpty()) return List.of();
    if (!body.get().expression().getPreludeClauses().isEmpty()) return List.of();

    String op = query.operation.toLowerCase(Locale.ROOT);
    String collection = source.get().expression().getExpression();
    String bodyExpression = body.get().expression().getExpression();
    String expression;
    ExpressionKind kind;
    List<String> prelude = new ArrayList<>();

    switch (op) {
      case "exists" -> {
        expression = nestedQuantifier("any", variables, collection, bodyExpression);
        kind = ExpressionKind.BOOLEAN;
      }
      case "forall" -> {
        expression = nestedQuantifier("all", variables, collection, bodyExpression);
        kind = ExpressionKind.BOOLEAN;
      }
      case "one" -> {
        expression = countMatches(variables, collection, bodyExpression) + " = 1";
        kind = ExpressionKind.BOOLEAN;
      }
      case "any" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        expression = "head([" + var + " IN " + collection + " WHERE " + bodyExpression + " | " + var + "])";
        kind = ExpressionKind.NODE;
      }
      case "select" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        expression = "[" + var + " IN " + collection + " WHERE " + bodyExpression + "]";
        kind = ExpressionKind.COLLECTION;
      }
      case "reject" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        expression = "[" + var + " IN " + collection + " WHERE NOT (" + bodyExpression + ")]";
        kind = ExpressionKind.COLLECTION;
      }
      case "collect" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        expression = "[" + var + " IN " + collection + " | " + bodyExpression + "]";
        kind = ExpressionKind.COLLECTION;
      }
      case "collectnested" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        expression = "reduce(acc = [], item IN [" + var + " IN " + collection + " | " + bodyExpression + "] | acc + item)";
        kind = ExpressionKind.COLLECTION;
      }
      case "isunique" -> {
        if (variables.size() != 1) return List.of();
        String var = variables.get(0).name;
        String values = "[" + var + " IN " + collection + " | " + bodyExpression + "]";
        expression = "size(" + values + ") = size(" + distinct(values, context) + ")";
        kind = ExpressionKind.BOOLEAN;
      }
      case "sortedby" -> {
        if (variables.size() != 1) return List.of();
        String sorted = context.nextName("sorted");
        String key = context.nextName("key");
        String var = variables.get(0).name;
        prelude.add("CALL {");
        prelude.add("  WITH *");
        prelude.add("  UNWIND " + collection + " AS " + var);
        prelude.add("  WITH *, " + bodyExpression + " AS " + key);
        prelude.add("  ORDER BY " + key);
        prelude.add("  RETURN collect(" + var + ") AS " + sorted);
        prelude.add("}");
        expression = sorted;
        kind = ExpressionKind.COLLECTION;
      }
      default -> {
        return List.of();
      }
    }

    CypherExpression cypher = CypherExpression.combine(expression, kind, source.get().expression(), body.get().expression())
        .withPrelude(prelude);
    return List.of(PlanCandidate.exact(query.operation, cypher, 940));
  }

  private List<ASTVarDecl> variables(ASTQuery query, CompileContext context) {
    if (!query.variables.isEmpty()) return query.variables;
    return List.of(new ASTVarDecl("it", null));
  }

  private CompileContext enterVariables(CompileContext context, List<ASTVarDecl> variables, String inferredType) {
    CompileContext scoped = context;
    for (ASTVarDecl variable : variables) {
      scoped = scoped.enterScope(variable.name, variable.hasType() ? variable.typeName : inferredType);
    }
    return scoped;
  }

  private ExpectedKind bodyExpected(String operation) {
    return switch (operation.toLowerCase(Locale.ROOT)) {
      case "select", "reject", "forall", "exists", "one", "any" -> ExpectedKind.BOOLEAN;
      default -> ExpectedKind.ANY;
    };
  }

  private String nestedQuantifier(String quantifier, List<ASTVarDecl> variables, String collection, String body) {
    return nestedQuantifier(quantifier, variables, collection, body, 0);
  }

  private String nestedQuantifier(String quantifier, List<ASTVarDecl> variables, String collection, String body, int index) {
    String var = variables.get(index).name;
    String inner = index == variables.size() - 1
        ? body
        : nestedQuantifier(quantifier, variables, collection, body, index + 1);
    return quantifier + "(" + var + " IN " + collection + " WHERE " + inner + ")";
  }

  private String countMatches(List<ASTVarDecl> variables, String collection, String body) {
    if (variables.size() == 1) {
      String var = variables.get(0).name;
      return "size([" + var + " IN " + collection + " WHERE " + body + "])";
    }
    return "size(" + nestedMatchList(variables, collection, body, 0) + ")";
  }

  private String nestedMatchList(List<ASTVarDecl> variables, String collection, String body, int index) {
    String var = variables.get(index).name;
    if (index == variables.size() - 1) {
      return "[" + var + " IN " + collection + " WHERE " + body + " | 1]";
    }
    String acc = "acc" + index;
    String nested = nestedMatchList(variables, collection, body, index + 1);
    return "reduce(" + acc + " = [], " + var + " IN " + collection + " | " + acc + " + " + nested + ")";
  }

  private String distinct(String collection, CompileContext context) {
    String item = context.nextName("item");
    String existing = context.nextName("existing");
    return "reduce(acc = [], " + item + " IN " + collection
        + " | CASE WHEN any(" + existing + " IN acc WHERE " + sameValue(existing, item)
        + ") THEN acc ELSE acc + [" + item + "] END)";
  }

  private String sameValue(String left, String right) {
    return "(" + left + " = " + right + " OR " + CypherSupport.normalizeString(left) + " = " + CypherSupport.normalizeString(right) + ")";
  }
}
