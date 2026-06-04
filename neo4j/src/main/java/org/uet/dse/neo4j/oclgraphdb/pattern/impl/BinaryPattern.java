package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class BinaryPattern implements OclPattern {
  @Override
  public String name() {
    return "binary";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTBinary;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTBinary binary = (ASTBinary) node;
    String op = binary.op;

    if ("=".equals(op) || "<>".equals(op)) {
      return matchEquality(binary, context, planner, op);
    }

    ExpectedKind childExpected = isLogical(op) ? ExpectedKind.BOOLEAN : ExpectedKind.VALUE;
    Optional<PlanCandidate> left = planner.plan(binary.left, context, childExpected);
    Optional<PlanCandidate> right = planner.plan(binary.right, context, childExpected);
    if (left.isEmpty() || right.isEmpty()) return List.of();

    String expression = switch (op) {
      case "and" -> "(" + left.get().expression().getExpression() + " AND " + right.get().expression().getExpression() + ")";
      case "or" -> "(" + left.get().expression().getExpression() + " OR " + right.get().expression().getExpression() + ")";
      case "implies" -> "((NOT (" + left.get().expression().getExpression() + ")) OR " + right.get().expression().getExpression() + ")";
      case ">", "<", ">=", "<=" -> CypherSupport.numeric(left.get().expression().getExpression()) + " " + op + " " + CypherSupport.numeric(right.get().expression().getExpression());
      case "+" -> CypherSupport.numeric(left.get().expression().getExpression()) + " + " + CypherSupport.numeric(right.get().expression().getExpression());
      case "-" -> CypherSupport.numeric(left.get().expression().getExpression()) + " - " + CypherSupport.numeric(right.get().expression().getExpression());
      case "*" -> CypherSupport.numeric(left.get().expression().getExpression()) + " * " + CypherSupport.numeric(right.get().expression().getExpression());
      case "/" -> CypherSupport.numeric(left.get().expression().getExpression()) + " / " + CypherSupport.numeric(right.get().expression().getExpression());
      default -> null;
    };
    if (expression == null) return List.of();

    ExpressionKind kind = isPredicate(op) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
    CypherExpression expr = CypherExpression.combine(expression, kind, left.get().expression(), right.get().expression());
    return List.of(PlanCandidate.exact(name(), expr, 980));
  }

  private List<PlanCandidate> matchEquality(ASTBinary binary, CompileContext context, PatternPlanner planner, String op) {
    List<PlanCandidate> results = new ArrayList<>();
    List<PlanCandidate> leftCandidates = planner.planMany(binary.left, context, ExpectedKind.ANY);
    List<PlanCandidate> rightCandidates = planner.planMany(binary.right, context, ExpectedKind.ANY);

    for (PlanCandidate left : leftCandidates) {
      for (PlanCandidate right : rightCandidates) {
        if (!canCompare(left.expression(), right.expression())) continue;
        String expression = compare(left.expression(), right.expression(), op);
        CypherExpression expr = CypherExpression.combine(expression, ExpressionKind.BOOLEAN, left.expression(), right.expression());
        results.add(PlanCandidate.exact(name(), expr, 980));
      }
    }
    return results;
  }

  private boolean isLogical(String op) {
    return "and".equals(op) || "or".equals(op) || "implies".equals(op);
  }

  private boolean isPredicate(String op) {
    return isLogical(op) || "=".equals(op) || "<>".equals(op) || ">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op);
  }

  private String compare(CypherExpression left, CypherExpression right, String op) {
    String leftExpr = left.getExpression();
    String rightExpr = right.getExpression();
    if (left.getKind() == ExpressionKind.NODE || right.getKind() == ExpressionKind.NODE) {
      return leftExpr + " " + op + " " + rightExpr;
    }
    return CypherSupport.normalizeString(leftExpr) + " " + op + " " + CypherSupport.normalizeString(rightExpr);
  }

  private boolean canCompare(CypherExpression left, CypherExpression right) {
    if (left.getKind() == ExpressionKind.COLLECTION || right.getKind() == ExpressionKind.COLLECTION) {
      return left.getKind() == right.getKind();
    }
    if (left.getKind() == ExpressionKind.NODE || right.getKind() == ExpressionKind.NODE) {
      return left.getKind() == right.getKind();
    }
    return left.getKind() != ExpressionKind.TYPE && right.getKind() != ExpressionKind.TYPE;
  }
}
