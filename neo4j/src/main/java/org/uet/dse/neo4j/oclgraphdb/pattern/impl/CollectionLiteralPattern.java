package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.cypher.CypherSupport;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTExpression;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class CollectionLiteralPattern implements OclPattern {
  @Override
  public String name() {
    return "collection-literal";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTCollectionLiteral;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTCollectionLiteral literal = (ASTCollectionLiteral) node;
    List<CypherExpression> children = new ArrayList<>();
    List<String> parts = new ArrayList<>();

    for (ASTExpression item : literal.items) {
      Optional<PlanCandidate> candidate = planner.plan(item, context, ExpectedKind.ANY);
      if (candidate.isEmpty()) return List.of();
      CypherExpression expression = candidate.get().expression();
      children.add(expression);
      if (expression.getKind() == ExpressionKind.COLLECTION) {
        parts.add(expression.getExpression());
      } else {
        parts.add("[" + expression.getExpression() + "]");
      }
    }

    String expression = parts.isEmpty() ? "[]" : String.join(" + ", parts);
    if ("Set".equals(literal.kind) || "OrderedSet".equals(literal.kind)) {
      String item = context.nextName("item");
      String existing = context.nextName("existing");
      expression = "reduce(acc = [], " + item + " IN " + expression
          + " | CASE WHEN any(" + existing + " IN acc WHERE " + sameValue(existing, item)
          + ") THEN acc ELSE acc + [" + item + "] END)";
    }

    CypherExpression cypher = CypherExpression.combine(expression, ExpressionKind.COLLECTION, children.toArray(new CypherExpression[0]));
    return List.of(PlanCandidate.exact(name(), cypher, 930));
  }

  private String sameValue(String left, String right) {
    return "(" + left + " = " + right + " OR " + CypherSupport.normalizeString(left) + " = " + CypherSupport.normalizeString(right) + ")";
  }
}
