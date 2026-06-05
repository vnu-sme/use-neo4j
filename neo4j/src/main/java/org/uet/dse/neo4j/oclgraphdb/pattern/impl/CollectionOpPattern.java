package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.*;

public class CollectionOpPattern implements OclPattern {
  @Override
  public String name() {
    return "collection-op";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTCollectionOp;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTCollectionOp op = (ASTCollectionOp) node;
    Optional<PlanCandidate> source = planner.plan(op.source, context, ExpectedKind.COLLECTION);
    if (source.isEmpty()) return List.of();

    String sourceExpr = source.get().expression().getExpression();
    String expression;
    ExpressionKind kind;
    switch (op.opName) {
      case "size" -> {
        expression = "size(" + sourceExpr + ")";
        kind = ExpressionKind.VALUE;
      }
      case "isEmpty" -> {
        expression = "size(" + sourceExpr + ") = 0";
        kind = ExpressionKind.BOOLEAN;
      }
      case "isNotEmpty", "notEmpty" -> {
        expression = "size(" + sourceExpr + ") > 0";
        kind = ExpressionKind.BOOLEAN;
      }
      case "selectByType", "selectByKind" -> {
        String typeName = typeNameArg(op.args);
        if (typeName == null) return List.of();
        String typeParam = context.addParam("typeName", typeName);
        String item = context.nextName("item");
        expression = "[" + item + " IN " + sourceExpr + " WHERE " + typePredicate(item, typeParam, "selectByKind".equals(op.opName), context) + "]";
        kind = ExpressionKind.COLLECTION;
      }
      case "includes", "contains", "excludes", "count" -> {
        if (op.args.size() != 1) return List.of();
        Optional<PlanCandidate> arg = selectMembershipArgument(planner.planMany(op.args.get(0), context, ExpectedKind.ANY));
        if (arg.isEmpty()) return List.of();
        String item = context.nextName("item");
        String compare = arg.get().expression().getKind() == ExpressionKind.NODE
            ? item + " = " + arg.get().expression().getExpression()
            : sameValue(item, arg.get().expression().getExpression());
        if ("includes".equals(op.opName) || "contains".equals(op.opName)) {
          expression = "any(" + item + " IN " + sourceExpr + " WHERE " + compare + ")";
          kind = ExpressionKind.BOOLEAN;
        } else if ("excludes".equals(op.opName)) {
          expression = "none(" + item + " IN " + sourceExpr + " WHERE " + compare + ")";
          kind = ExpressionKind.BOOLEAN;
        } else {
          expression = "size([" + item + " IN " + sourceExpr + " WHERE " + compare + "])";
          kind = ExpressionKind.VALUE;
        }
        CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression(), arg.get().expression());
        return List.of(PlanCandidate.exact(op.opName, expr, 930));
      }
      case "includesAll", "excludesAll", "union", "intersection" -> {
        if (op.args.size() != 1) return List.of();
        Optional<PlanCandidate> arg = planner.plan(op.args.get(0), context, ExpectedKind.ANY);
        if (arg.isEmpty()) return List.of();

        String targetCollection = asCollection(arg.get().expression());
        String item = context.nextName("item");
        String target = context.nextName("target");
        String compare = sameValue(item, target);

        if ("includesAll".equals(op.opName)) {
          expression = "all(" + target + " IN " + targetCollection + " WHERE any(" + item + " IN " + sourceExpr + " WHERE " + compare + "))";
          kind = ExpressionKind.BOOLEAN;
        } else if ("excludesAll".equals(op.opName)) {
          expression = "none(" + target + " IN " + targetCollection + " WHERE any(" + item + " IN " + sourceExpr + " WHERE " + compare + "))";
          kind = ExpressionKind.BOOLEAN;
        } else if ("union".equals(op.opName)) {
          String candidate = context.nextName("candidate");
          String existing = context.nextName("existing");
          expression = "reduce(acc = " + sourceExpr + ", " + candidate + " IN " + targetCollection
              + " | CASE WHEN any(" + existing + " IN acc WHERE " + sameValue(existing, candidate)
              + ") THEN acc ELSE acc + [" + candidate + "] END)";
          kind = ExpressionKind.COLLECTION;
        } else {
          String existing = context.nextName("existing");
          expression = "reduce(acc = [], " + item + " IN " + sourceExpr
              + " | CASE WHEN any(" + target + " IN " + targetCollection + " WHERE " + sameValue(item, target)
              + ") AND none(" + existing + " IN acc WHERE " + sameValue(existing, item)
              + ") THEN acc + [" + item + "] ELSE acc END)";
          kind = ExpressionKind.COLLECTION;
        }

        CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression(), arg.get().expression());
        return List.of(PlanCandidate.exact(op.opName, expr, 900));
      }
      case "including", "excluding" -> {
        if (op.args.size() != 1) return List.of();
        Optional<PlanCandidate> arg = planner.plan(op.args.get(0), context, ExpectedKind.ANY);
        if (arg.isEmpty()) return List.of();
        String existing = context.nextName("existing");
        String itemExpr = arg.get().expression().getExpression();
        if ("including".equals(op.opName)) {
          expression = "CASE WHEN any(" + existing + " IN " + sourceExpr + " WHERE " + sameValue(existing, itemExpr)
              + ") THEN " + sourceExpr + " ELSE " + sourceExpr + " + [" + itemExpr + "] END";
        } else {
          String item = context.nextName("item");
          expression = "[" + item + " IN " + sourceExpr + " WHERE NOT (" + sameValue(item, itemExpr) + ")]";
        }
        kind = ExpressionKind.COLLECTION;
        CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression(), arg.get().expression());
        return List.of(PlanCandidate.exact(op.opName, expr, 900));
      }
      case "at" -> {
        if (op.args.size() != 1) return List.of();
        Optional<PlanCandidate> arg = planner.plan(op.args.get(0), context, ExpectedKind.VALUE);
        if (arg.isEmpty()) return List.of();
        expression = sourceExpr + "[toInteger(" + CypherSupport.numeric(arg.get().expression().getExpression()) + ") - 1]";
        kind = ExpressionKind.VALUE;
        CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression(), arg.get().expression());
        return List.of(PlanCandidate.exact(op.opName, expr, 900));
      }
      case "first" -> {
        expression = "head(" + sourceExpr + ")";
        kind = ExpressionKind.VALUE;
      }
      case "last" -> {
        expression = "last(" + sourceExpr + ")";
        kind = ExpressionKind.VALUE;
      }
      case "asSet" -> {
        String item = context.nextName("item");
        String existing = context.nextName("existing");
        expression = "reduce(acc = [], " + item + " IN " + sourceExpr
            + " | CASE WHEN any(" + existing + " IN acc WHERE " + sameValue(existing, item)
            + ") THEN acc ELSE acc + [" + item + "] END)";
        kind = ExpressionKind.COLLECTION;
      }
      case "asSequence", "asBag", "asOrderedSet" -> {
        expression = sourceExpr;
        kind = ExpressionKind.COLLECTION;
      }
      case "sum" -> {
        String item = context.nextName("item");
        expression = "reduce(total = 0.0, " + item + " IN " + sourceExpr + " | total + " + CypherSupport.numeric(item) + ")";
        kind = ExpressionKind.VALUE;
      }
      case "avg", "average" -> {
        String item = context.nextName("item");
        String values = "[" + item + " IN " + sourceExpr + " | " + CypherSupport.numeric(item) + "]";
        expression = "CASE WHEN size(" + values + ") = 0 THEN null ELSE reduce(total = 0.0, value IN " + values + " | total + value) / size(" + values + ") END";
        kind = ExpressionKind.VALUE;
      }
      case "max", "min" -> {
        String item = context.nextName("item");
        String best = context.nextName("best");
        String comparison = "max".equals(op.opName) ? ">" : "<";
        expression = "reduce(" + best + " = null, " + item + " IN " + sourceExpr
            + " | CASE WHEN " + best + " IS NULL OR " + CypherSupport.numeric(item) + " " + comparison + " " + CypherSupport.numeric(best)
            + " THEN " + item + " ELSE " + best + " END)";
        kind = ExpressionKind.VALUE;
      }
      default -> {
        return List.of();
      }
    }

    CypherExpression expr = CypherExpression.combine(expression, kind, source.get().expression());
    return List.of(PlanCandidate.exact(op.opName, expr, 930));
  }

  private Optional<PlanCandidate> selectMembershipArgument(List<PlanCandidate> candidates) {
    Optional<PlanCandidate> nodeCandidate = candidates.stream()
        .filter(c -> c.expression().getKind() == ExpressionKind.NODE)
        .findFirst();
    if (nodeCandidate.isPresent()) return nodeCandidate;

    return candidates.stream()
        .filter(c -> c.expression().getKind() != ExpressionKind.COLLECTION && c.expression().getKind() != ExpressionKind.TYPE)
        .findFirst();
  }

  private String asCollection(CypherExpression expression) {
    if (expression.getKind() == ExpressionKind.COLLECTION) {
      return expression.getExpression();
    }
    return "CASE WHEN " + expression.getExpression() + " IS NULL THEN [] ELSE [" + expression.getExpression() + "] END";
  }

  private String sameValue(String left, String right) {
    return "(" + left + " = " + right + " OR " + CypherSupport.normalizeString(left) + " = " + CypherSupport.normalizeString(right) + ")";
  }

  private String typeNameArg(List<ASTExpression> args) {
    if (args.size() != 1) return null;
    ASTExpression arg = args.get(0);
    if (arg instanceof ASTTypeLiteral) return ((ASTTypeLiteral) arg).typeName;
    if (arg instanceof ASTVar) return ((ASTVar) arg).name;
    if (arg instanceof ASTStringLiteral) return ((ASTStringLiteral) arg).value;
    return null;
  }

  private String typePredicate(String nodeExpr, String typeParam, boolean kindOf, CompileContext context) {
    String cls = context.nextName("cls");
    String parent = context.nextName("parent");
    String predicate = "EXISTS { MATCH (" + nodeExpr + ")-[:ObjectInstanceOf]->(" + cls + ") ";
    if (kindOf) {
      predicate += "WHERE " + cls + ".name = " + typeParam
          + " OR EXISTS { MATCH (" + cls + ")-[:Extends*1..]->(" + parent + " {name: " + typeParam + "}) }";
    } else {
      predicate += "WHERE " + cls + ".name = " + typeParam;
    }
    return predicate + " }";
  }
}
