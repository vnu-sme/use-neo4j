package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.CypherExpression;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpectedKind;
import org.uet.dse.neo4j.oclgraphdb.plan.PlanCandidate;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTMethodCall;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

final class PatternCypherUtils {
  private PatternCypherUtils() {}

  static Optional<String> allInstancesClassName(ASTNode source, CompileContext context) {
    if (source instanceof ASTMethodCall call && "allInstances".equalsIgnoreCase(call.methodName)) {
      return className(call.source, context);
    }
    if (source instanceof ASTProperty property && "allInstances".equalsIgnoreCase(property.name)) {
      return className(property.source, context);
    }
    return Optional.empty();
  }

  static Optional<String> className(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var && context.resolver().isClass(var.name)) {
      return Optional.of(var.name);
    }
    return Optional.empty();
  }

  static Optional<String> sourceType(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var) {
      Optional<String> type = context.variableType(var.name);
      if (type.isPresent()) return type;
      return context.resolver().objectClass(var.name);
    }
    if (node instanceof ASTProperty property) {
      return sourceType(property.source, context)
          .flatMap(type -> context.resolver().resolveNavigation(type, property.name))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    if (node instanceof ASTMethodCall call && call.args.isEmpty()) {
      return sourceType(call.source, context)
          .flatMap(type -> context.resolver().resolveNavigation(type, call.methodName))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    return Optional.empty();
  }

  static Optional<String> collectionElementType(ASTNode node, CompileContext context) {
    Optional<String> allInstancesType = allInstancesClassName(node, context);
    if (allInstancesType.isPresent()) return allInstancesType;
    if (node instanceof ASTIterator iterator && isSelectOrReject(iterator)) {
      return collectionElementType(iterator.source, context);
    }
    return sourceType(node, context);
  }

  static Optional<BoundNode> bindNode(ASTNode node, CompileContext context, PatternPlanner planner) {
    Optional<PlanCandidate> source = planner.plan(node, context, ExpectedKind.NODE);
    if (source.isEmpty()) return Optional.empty();

    CypherExpression expression = source.get().expression();
    List<String> prelude = new ArrayList<>(expression.getPreludeClauses());
    String nodeExpression = expression.getExpression();
    if (isSimpleVariable(nodeExpression)) {
      return Optional.of(new BoundNode(nodeExpression, prelude));
    }

    String alias = context.nextName("node");
    prelude.add("WITH *, " + nodeExpression + " AS " + alias);
    return Optional.of(new BoundNode(alias, prelude));
  }

  static List<String> bodyFilter(CypherExpression body, String predicate) {
    List<String> clauses = new ArrayList<>(body.getPreludeClauses());
    clauses.add("WITH *");
    clauses.add("WHERE " + predicate);
    return clauses;
  }

  static boolean isSelectOrReject(ASTIterator iterator) {
    String operation = iterator.operation.toLowerCase();
    return "select".equals(operation) || "reject".equals(operation);
  }

  private static boolean isSimpleVariable(String expression) {
    return expression.matches("[A-Za-z_][A-Za-z0-9_]*");
  }

  record BoundNode(String name, List<String> preludeClauses) {}
}
