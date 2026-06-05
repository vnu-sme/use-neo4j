package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.*;

public class PrimitivePatternPass implements PatternPass {
  @Override
  public String name() {
    return "primitive";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      added += addPrimitive(graph, node, passNumber);
      added += addLegacyCandidates(graph, node, context, passNumber);
    }
    return added;
  }

  private int addPrimitive(PatternGraph graph, ASTNode node, int passNumber) {
    PatternInstance.Builder builder = primitiveBuilder(node);
    if (builder == null) return 0;
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private PatternInstance.Builder primitiveBuilder(ASTNode node) {
    if (node instanceof ASTContext context) {
      return PatternInstance.builder(MultiPassPatternKind.CONTEXT, node)
          .label("context " + context.className)
          .metadata("class", context.className)
          .metadata("invariant", context.invName)
          .score(500)
          .exactSemantic(true);
    }
    if (node instanceof ASTVar var) {
      return PatternInstance.builder(MultiPassPatternKind.VARIABLE, node)
          .label(var.name)
          .score(500)
          .exactSemantic(true);
    }
    if (isLiteral(node)) {
      return PatternInstance.builder(MultiPassPatternKind.LITERAL, node)
          .label(AstPatternSupport.labelOf(node))
          .outputKind(node instanceof ASTBooleanLiteral ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE)
          .score(520)
          .exactSemantic(true);
    }
    if (node instanceof ASTProperty property) {
      return PatternInstance.builder(MultiPassPatternKind.PROPERTY_ACCESS, node)
          .label("." + property.name)
          .metadata("property", property.name)
          .score(560);
    }
    if (node instanceof ASTQualifiedNavigation navigation) {
      return PatternInstance.builder(MultiPassPatternKind.PROPERTY_ACCESS, node)
          .label("." + navigation.roleName + "[...]")
          .metadata("property", navigation.roleName)
          .metadata("qualified", true)
          .score(560);
    }
    if (node instanceof ASTMethodCall methodCall) {
      return PatternInstance.builder(MultiPassPatternKind.METHOD_CALL, node)
          .label("." + methodCall.methodName + "()")
          .metadata("method", methodCall.methodName)
          .score(540);
    }
    if (node instanceof ASTCollectionOp collectionOp) {
      return PatternInstance.builder(MultiPassPatternKind.COLLECTION_OPERATION, node)
          .label("->" + collectionOp.opName + "()")
          .metadata("operation", collectionOp.opName)
          .score(540);
    }
    if (node instanceof ASTIterator iterator) {
      return PatternInstance.builder(MultiPassPatternKind.ITERATOR, node)
          .label("->" + iterator.operation + "(" + iterator.iteratorName + " | ...)")
          .metadata("operation", iterator.operation)
          .metadata("iterator", iterator.iteratorName)
          .score(560);
    }
    if (node instanceof ASTQuery query) {
      return PatternInstance.builder(MultiPassPatternKind.QUERY, node)
          .label("->" + query.operation + "(" + query.primaryVariableName() + " | ...)")
          .metadata("operation", query.operation)
          .metadata("iterator", query.primaryVariableName())
          .score(560);
    }
    if (node instanceof ASTIterate iterate) {
      return PatternInstance.builder(MultiPassPatternKind.ITERATE, node)
          .label("->iterate(" + iterate.iterator.name + "; " + iterate.accumulator.variable.name + " | ...)")
          .metadata("iterator", iterate.iterator.name)
          .metadata("accumulator", iterate.accumulator.variable.name)
          .score(540);
    }
    if (node instanceof ASTLet) {
      return PatternInstance.builder(MultiPassPatternKind.LET, node)
          .label("let")
          .score(540);
    }
    if (node instanceof ASTIf) {
      return PatternInstance.builder(MultiPassPatternKind.IF, node)
          .label("if")
          .score(540);
    }
    if (node instanceof ASTUnary unary) {
      return PatternInstance.builder(MultiPassPatternKind.UNARY, node)
          .label(unary.operator)
          .outputKind("not".equals(unary.operator) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE)
          .score(540);
    }
    if (node instanceof ASTCollectionLiteral literal) {
      return PatternInstance.builder(MultiPassPatternKind.COLLECTION_LITERAL, node)
          .label(literal.kind + "{}")
          .outputKind(ExpressionKind.COLLECTION)
          .score(540);
    }
    if (node instanceof ASTRange) {
      return PatternInstance.builder(MultiPassPatternKind.RANGE, node)
          .label("..")
          .outputKind(ExpressionKind.COLLECTION)
          .score(540);
    }
    if (node instanceof ASTBinary binary) {
      return PatternInstance.builder(MultiPassPatternKind.BINARY, node)
          .label(binary.op)
          .metadata("operator", binary.op)
          .score(540);
    }
    if (node instanceof ASTNot) {
      return PatternInstance.builder(MultiPassPatternKind.NOT, node)
          .label("not")
          .outputKind(ExpressionKind.BOOLEAN)
          .score(540);
    }
    return null;
  }

  private int addLegacyCandidates(
      PatternGraph graph,
      ASTNode node,
      MultiPassPatternContext context,
      int passNumber
  ) {
    int added = 0;
    try {
      List<PlanCandidate> candidates = context.legacyPlanner().planMany(node, context.compileContext(), ExpectedKind.ANY);
      for (PlanCandidate candidate : candidates) {
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.CYPHER_CANDIDATE, node)
            .label(candidate.getPatternName())
            .outputKind(candidate.expression().getKind())
            .score(candidate.getScore())
            .exactSemantic(candidate.isExactSemantic())
            .metadata("coveredNodes", candidate.expression().getCoveredNodes())
            .metadata("cost", candidate.expression().getCost())
            .metadata("cypher", preview(candidate.expression().getExpression()));
        if (graph.add(builder, passNumber).isPresent()) added++;
      }
    } catch (RuntimeException ignored) {
      // Semantic lookups may fail while analyzing incomplete models; structural passes still remain useful.
    }
    return added;
  }

  private boolean isLiteral(ASTNode node) {
    return node instanceof ASTIntegerLiteral
        || node instanceof ASTRealLiteral
        || node instanceof ASTStringLiteral
        || node instanceof ASTBooleanLiteral
        || node instanceof ASTNullLiteral
        || node instanceof ASTUndefinedLiteral
        || node instanceof ASTEnumLiteral
        || node instanceof ASTTypeLiteral;
  }

  private String preview(String value) {
    if (value == null || value.isBlank()) return "";
    String normalized = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
  }
}
