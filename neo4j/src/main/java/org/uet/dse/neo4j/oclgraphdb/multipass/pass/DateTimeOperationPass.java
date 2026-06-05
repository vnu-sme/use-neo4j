package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTMethodCall;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class DateTimeOperationPass implements PatternPass {
  @Override
  public String name() {
    return "date-time-operation";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (!(node instanceof ASTMethodCall methodCall) || !isDateTimeOperation(methodCall.methodName)) continue;
      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.DATE_TIME_OPERATION_MAPPING, methodCall)
          .label(methodCall.methodName)
          .outputKind(outputKind(methodCall.methodName))
          .score(1750)
          .exactSemantic(true)
          .metadata("method", methodCall.methodName);
      if (graph.add(builder, passNumber).isPresent()) added++;
    }
    return added;
  }

  private boolean isDateTimeOperation(String method) {
    return "today".equals(method)
        || "equals".equals(method)
        || "after".equals(method)
        || "before".equals(method)
        || "durationInDays".equals(method)
        || "durationInHours".equals(method)
        || "dayOfTheWeek".equals(method)
        || "dayOfWeek".equals(method)
        || "getMonth".equals(method)
        || "last".equals(method)
        || "hours".equals(method);
  }

  private ExpressionKind outputKind(String method) {
    return "equals".equals(method) || "after".equals(method) || "before".equals(method)
        ? ExpressionKind.BOOLEAN
        : ExpressionKind.VALUE;
  }
}
