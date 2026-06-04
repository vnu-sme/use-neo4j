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
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class AllInstancesSelectTerminalPattern implements OclPattern {
  @Override
  public String name() {
    return "all-instances-select-terminal";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTCollectionOp;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    ASTCollectionOp terminal = (ASTCollectionOp) node;
    if (!isSupportedTerminal(terminal.opName) || !(terminal.source instanceof ASTIterator iterator)) return List.of();
    if (!PatternCypherUtils.isSelectOrReject(iterator)) return List.of();

    Optional<String> className = PatternCypherUtils.allInstancesClassName(iterator.source, context);
    if (className.isEmpty()) return List.of();

    CompileContext scoped = context.enterScope(iterator.iteratorName, className.get());
    Optional<PlanCandidate> body = planner.plan(iterator.body, scoped, ExpectedKind.BOOLEAN);
    if (body.isEmpty()) return List.of();

    String countVar = context.nextName("count");
    List<String> prelude = new ArrayList<>();
    prelude.add("CALL {");
    prelude.addAll(CypherSupport.allInstancesMatch(className.get(), iterator.iteratorName, context));
    String predicate = "select".equalsIgnoreCase(iterator.operation)
        ? body.get().expression().getExpression()
        : "NOT (" + body.get().expression().getExpression() + ")";
    prelude.addAll(PatternCypherUtils.bodyFilter(body.get().expression(), predicate));
    prelude.add("RETURN count(DISTINCT " + iterator.iteratorName + ") AS " + countVar);
    prelude.add("}");

    CypherExpression expr = terminalExpression(terminal.opName, countVar, prelude, body.get().expression());
    return List.of(PlanCandidate.exact(name(), expr, 1450));
  }

  private boolean isSupportedTerminal(String opName) {
    return "size".equals(opName) || "isEmpty".equals(opName) || "isNotEmpty".equals(opName);
  }

  private CypherExpression terminalExpression(
      String opName,
      String countVar,
      List<String> prelude,
      CypherExpression body
  ) {
    return switch (opName) {
      case "size" -> new CypherExpression(countVar, ExpressionKind.VALUE, prelude, body.getCoveredNodes() + 3, body.getCost());
      case "isEmpty" -> new CypherExpression(countVar + " = 0", ExpressionKind.BOOLEAN, prelude, body.getCoveredNodes() + 3, body.getCost());
      case "isNotEmpty" -> new CypherExpression(countVar + " > 0", ExpressionKind.BOOLEAN, prelude, body.getCoveredNodes() + 3, body.getCost());
      default -> throw new IllegalArgumentException("Unsupported terminal: " + opName);
    };
  }
}
