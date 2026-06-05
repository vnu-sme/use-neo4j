package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTNode;

public class TerminalCompositionPass implements PatternPass {
  @Override
  public String name() {
    return "terminal-compose";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (!(node instanceof ASTCollectionOp collectionOp)) continue;
      if (!isTerminal(collectionOp.opName)) continue;

      Optional<PatternInstance> source = graph.bestAny(
          collectionOp.source,
          MultiPassPatternKind.FILTER,
          MultiPassPatternKind.ALL_INSTANCES_SOURCE,
          MultiPassPatternKind.NAVIGATION_SOURCE,
          MultiPassPatternKind.CYPHER_CANDIDATE
      );
      if (source.isEmpty()) continue;

      PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.FILTERED_TERMINAL, collectionOp)
          .label("->" + collectionOp.opName + "()")
          .outputKind("size".equals(collectionOp.opName) ? ExpressionKind.VALUE : ExpressionKind.BOOLEAN)
          .score(2300 + source.get().getScore() / 10)
          .exactSemantic(source.get().isExactSemantic())
          .children(List.of(source.get()))
          .metadata("terminal", collectionOp.opName);
      if (graph.add(builder, passNumber).isPresent()) added++;
    }
    return added;
  }

  private boolean isTerminal(String operation) {
    return "size".equals(operation)
        || "isEmpty".equals(operation)
        || "isNotEmpty".equals(operation)
        || "notEmpty".equals(operation);
  }
}
