package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.*;

public class MethodCallPattern implements OclPattern {
  @Override
  public String name() {
    return "method-call";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTMethodCall;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTMethodCall call = (ASTMethodCall) node;
    String method = call.methodName;

    if ("allInstances".equalsIgnoreCase(method) || "allInstance".equalsIgnoreCase(method)) {
      return planner.plan(call.source, context, ExpectedKind.TYPE)
          .map(type -> {
            String className = type.expression().getExpression();
            CypherExpression expr = CypherSupport.allInstancesExpression(className, context);
            return List.of(PlanCandidate.exact("allInstances", expr, 1000));
          })
          .orElse(List.of());
    }

    if (isCompareAlias(method)) {
      if (call.args.size() != 1) return List.of();
      Optional<PlanCandidate> source = planner.plan(call.source, context, ExpectedKind.ANY);
      Optional<PlanCandidate> arg = planner.plan(call.args.get(0), context, ExpectedKind.ANY);
      if (source.isEmpty() || arg.isEmpty()) return List.of();
      String expression = compareAlias(source.get().expression(), method, arg.get().expression());
      CypherExpression expr = CypherExpression.combine(expression, ExpressionKind.BOOLEAN, source.get().expression(), arg.get().expression());
      return List.of(PlanCandidate.exact("method-compare-" + method, expr, 960));
    }

    if (isDateTimeOperation(method)) {
      Optional<PlanCandidate> candidate = dateTimeOperation(call, context, planner);
      if (candidate.isPresent()) return List.of(candidate.get());
    }

    if ("isDefined".equals(method) || "isUndefined".equals(method)) {
      Optional<PlanCandidate> sourceCandidate = planner.plan(call.source, context, ExpectedKind.COLLECTION)
          .or(() -> planner.plan(call.source, context, ExpectedKind.ANY));
      return sourceCandidate
          .map(source -> {
            String condition = source.expression().getKind() == ExpressionKind.COLLECTION
                ? "size(" + source.expression().getExpression() + ") > 0"
                : source.expression().getExpression() + " IS NOT NULL";
            if ("isUndefined".equals(method)) condition = "NOT (" + condition + ")";
            CypherExpression expr = CypherExpression.combine(condition, ExpressionKind.BOOLEAN, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 930));
          })
          .orElse(List.of());
    }

    if ("oclIsTypeOf".equals(method) || "oclIsKindOf".equals(method)) {
      String typeName = typeNameArg(call.args);
      if (typeName == null) return List.of();
      return planner.plan(call.source, context, ExpectedKind.NODE)
          .map(source -> {
            String typeParam = context.addParam("typeName", typeName);
            String predicate = typePredicate(source.expression().getExpression(), typeParam, "oclIsKindOf".equals(method), context);
            CypherExpression expr = CypherExpression.combine(predicate, ExpressionKind.BOOLEAN, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 920));
          })
          .orElse(List.of());
    }

    if ("oclAsType".equals(method)) {
      String typeName = typeNameArg(call.args);
      if (typeName == null) return List.of();
      return planner.plan(call.source, context, ExpectedKind.NODE)
          .map(source -> {
            String typeParam = context.addParam("typeName", typeName);
            String nodeExpr = source.expression().getExpression();
            String expression = "CASE WHEN " + typePredicate(nodeExpr, typeParam, true, context) + " THEN " + nodeExpr + " ELSE null END";
            CypherExpression expr = CypherExpression.combine(expression, ExpressionKind.NODE, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 900));
          })
          .orElse(List.of());
    }

    if ("selectByType".equals(method) || "selectByKind".equals(method)) {
      String typeName = typeNameArg(call.args);
      if (typeName == null) return List.of();
      return planner.plan(call.source, context, ExpectedKind.COLLECTION)
          .map(source -> {
            String typeParam = context.addParam("typeName", typeName);
            String item = context.nextName("item");
            String expression = "[" + item + " IN " + source.expression().getExpression()
                + " WHERE " + typePredicate(item, typeParam, "selectByKind".equals(method), context) + "]";
            CypherExpression expr = CypherExpression.combine(expression, ExpressionKind.COLLECTION, source.expression());
            return List.of(PlanCandidate.exact(method, expr, 900));
          })
          .orElse(List.of());
    }

    if (call.args.isEmpty()) {
      ASTProperty synthetic = new ASTProperty(call.source, method);
      return PropertyPattern.INSTANCE.match(synthetic, context, expected, planner);
    }

    return List.of();
  }

  private String typeNameArg(List<ASTExpression> args) {
    if (args.size() != 1) return null;
    ASTExpression arg = args.get(0);
    if (arg instanceof ASTTypeLiteral) return ((ASTTypeLiteral) arg).typeName;
    if (arg instanceof ASTVar) return ((ASTVar) arg).name;
    if (arg instanceof ASTStringLiteral) return ((ASTStringLiteral) arg).value;
    return null;
  }

  private boolean isCompareAlias(String method) {
    return "equals".equals(method) || "after".equals(method) || "before".equals(method);
  }

  private boolean isDateTimeOperation(String method) {
    return "today".equals(method)
        || "durationInDays".equals(method)
        || "durationInHours".equals(method)
        || "dayOfTheWeek".equals(method)
        || "dayOfWeek".equals(method)
        || "getMonth".equals(method)
        || "last".equals(method)
        || "hours".equals(method);
  }

  private Optional<PlanCandidate> dateTimeOperation(
      ASTMethodCall call,
      CompileContext context,
      PatternPlanner planner
  ) {
    String method = call.methodName;
    if ("today".equals(method) && call.args.isEmpty()) {
      CypherExpression expr = new CypherExpression("date()", ExpressionKind.VALUE);
      return Optional.of(PlanCandidate.exact("date-today", expr, 930));
    }

    Optional<PlanCandidate> source = planner.plan(call.source, context, ExpectedKind.ANY);
    if (source.isEmpty()) return Optional.empty();
    String sourceExpr = source.get().expression().getExpression();

    if ("durationInDays".equals(method) && call.args.size() == 1) {
      Optional<PlanCandidate> end = planner.plan(call.args.get(0), context, ExpectedKind.ANY);
      if (end.isEmpty()) return Optional.empty();
      CypherExpression expr = CypherExpression.combine(
          CypherSupport.durationDays(sourceExpr, end.get().expression().getExpression()),
          ExpressionKind.VALUE,
          source.get().expression(),
          end.get().expression()
      );
      return Optional.of(PlanCandidate.exact("date-duration-days", expr, 940));
    }

    if ("durationInHours".equals(method) && call.args.size() == 1) {
      Optional<PlanCandidate> end = planner.plan(call.args.get(0), context, ExpectedKind.ANY);
      if (end.isEmpty()) return Optional.empty();
      CypherExpression expr = CypherExpression.combine(
          CypherSupport.durationHours(sourceExpr, end.get().expression().getExpression()),
          ExpressionKind.VALUE,
          source.get().expression(),
          end.get().expression()
      );
      return Optional.of(PlanCandidate.exact("date-duration-hours", expr, 940));
    }

    if (("dayOfTheWeek".equals(method) || "dayOfWeek".equals(method)) && call.args.isEmpty()) {
      CypherExpression expr = CypherExpression.combine(CypherSupport.dateValue(sourceExpr) + ".dayOfWeek", ExpressionKind.VALUE, source.get().expression());
      return Optional.of(PlanCandidate.exact("date-day-of-week", expr, 930));
    }

    if ("getMonth".equals(method) && call.args.isEmpty()) {
      CypherExpression expr = CypherExpression.combine(CypherSupport.monthStart(sourceExpr), ExpressionKind.VALUE, source.get().expression());
      return Optional.of(PlanCandidate.exact("date-month-start", expr, 930));
    }

    if ("last".equals(method) && call.args.size() == 1) {
      Integer weekday = weekday(call.args.get(0));
      if (weekday == null) return Optional.empty();
      CypherExpression expr = CypherExpression.combine(CypherSupport.lastWeekdayOfMonth(sourceExpr, weekday), ExpressionKind.VALUE, source.get().expression());
      return Optional.of(PlanCandidate.exact("date-last-weekday", expr, 930));
    }

    if ("hours".equals(method) && call.args.isEmpty()) {
      CypherExpression expr = CypherExpression.combine(CypherSupport.numeric(sourceExpr), ExpressionKind.VALUE, source.get().expression());
      return Optional.of(PlanCandidate.exact("date-hours", expr, 860));
    }

    return Optional.empty();
  }

  private String compareAlias(CypherExpression left, String method, CypherExpression right) {
    return switch (method) {
      case "after" -> CypherSupport.normalizeString(left.getExpression()) + " > " + CypherSupport.normalizeString(right.getExpression());
      case "before" -> CypherSupport.normalizeString(left.getExpression()) + " < " + CypherSupport.normalizeString(right.getExpression());
      case "equals" -> CypherSupport.normalizeString(left.getExpression()) + " = " + CypherSupport.normalizeString(right.getExpression());
      default -> "";
    };
  }

  private Integer weekday(ASTExpression expression) {
    String raw = null;
    if (expression instanceof ASTStringLiteral literal) raw = literal.value;
    if (expression instanceof ASTVar var) raw = var.name;
    if (raw == null) return null;
    return switch (raw.trim().toLowerCase()) {
      case "monday" -> 1;
      case "tuesday" -> 2;
      case "wednesday" -> 3;
      case "thursday" -> 4;
      case "friday" -> 5;
      case "saturday" -> 6;
      case "sunday" -> 7;
      default -> null;
    };
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
