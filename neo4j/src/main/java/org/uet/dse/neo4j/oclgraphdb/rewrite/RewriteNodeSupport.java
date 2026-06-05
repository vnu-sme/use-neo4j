package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclite.ast.*;

public final class RewriteNodeSupport {
  private RewriteNodeSupport() {}

  public static boolean isComparison(String op) {
    return "=".equals(op) || "<>".equals(op) || ">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op);
  }

  public static boolean isLogical(String op) {
    return "and".equals(op) || "or".equals(op) || "xor".equals(op) || "implies".equals(op);
  }

  public static boolean isValueLike(RewritePatternNode node) {
    return node != null
        && (node.getSemantic().isValueLike()
        || node.getKind() == RewritePatternKind.ATTRIBUTE_ACCESS
        || node.getKind() == RewritePatternKind.NAVIGATION_ACCESS
        || node.getKind() == RewritePatternKind.LITERAL
        || node.getKind() == RewritePatternKind.VARIABLE);
  }

  public static boolean isBooleanLike(RewritePatternNode node) {
    return node != null
        && (node.getSemantic().isBooleanLike()
        || node.getKind() == RewritePatternKind.COMPARE
        || node.getKind() == RewritePatternKind.ATTRIBUTE_COMPARE
        || node.getKind() == RewritePatternKind.NAVIGATION_COMPARE
        || node.getKind() == RewritePatternKind.LOGICAL_COMPOSITION
        || node.getKind() == RewritePatternKind.NEGATION
        || node.getKind() == RewritePatternKind.QUANTIFIER);
  }

  public static boolean isCollectionLike(RewritePatternNode node) {
    return node != null
        && (node.getSemantic().isCollectionLike()
        || node.getKind() == RewritePatternKind.FILTER
        || node.getKind() == RewritePatternKind.COLLECT
        || node.getKind() == RewritePatternKind.SORTED
        || node.getKind() == RewritePatternKind.CLOSURE);
  }

  public static Optional<String> inferNodeType(RewritePatternNode node, CompileContext context) {
    if (node == null) return Optional.empty();
    if (!node.getSemantic().getNodeType().isBlank()) {
      return Optional.of(node.getSemantic().getNodeType());
    }
    if (node.getAstNode() instanceof ASTVar var) {
      Optional<String> variableType = context.variableType(var.name);
      if (variableType.isPresent()) return variableType;
      return context.resolver().objectClass(var.name);
    }
    if (node.getKind() == RewritePatternKind.NAVIGATION_ACCESS && !node.metadata("targetClass").isBlank()) {
      return Optional.of(node.metadata("targetClass"));
    }
    if (node.getAstNode() instanceof ASTProperty property && !node.getChildren().isEmpty()) {
      return inferNodeType(node.getChildren().get(0), context)
          .flatMap(type -> context.resolver().resolveNavigation(type, property.name))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    if (node.getAstNode() instanceof ASTQualifiedNavigation navigation && !node.getChildren().isEmpty()) {
      return inferNodeType(node.getChildren().get(0), context)
          .flatMap(type -> context.resolver().resolveNavigation(type, navigation.roleName))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    if (node.getAstNode() instanceof ASTMethodCall call && call.args.isEmpty() && !node.getChildren().isEmpty()) {
      return inferNodeType(node.getChildren().get(0), context)
          .flatMap(type -> context.resolver().resolveNavigation(type, call.methodName))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    return Optional.empty();
  }

  public static Optional<String> inferCollectionElementType(RewritePatternNode node, CompileContext context) {
    if (node == null) return Optional.empty();
    if (!node.getSemantic().getNodeType().isBlank()) {
      return Optional.of(node.getSemantic().getNodeType());
    }
    if (node.getAstNode() instanceof ASTMethodCall call && isAllInstancesName(call.methodName)) {
      return className(call.source, context);
    }
    if (node.getAstNode() instanceof ASTProperty property && isAllInstancesName(property.name)) {
      return className(property.source, context);
    }
    if ((node.getKind() == RewritePatternKind.FILTER || node.getKind() == RewritePatternKind.SORTED)
        && !node.getChildren().isEmpty()) {
      return inferCollectionElementType(node.getChildren().get(0), context);
    }
    return inferNodeType(node, context);
  }

  public static Optional<String> className(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var && context.resolver().isClass(var.name)) {
      return Optional.of(var.name);
    }
    return Optional.empty();
  }

  private static boolean isAllInstancesName(String name) {
    return "allInstances".equalsIgnoreCase(name) || "allInstance".equalsIgnoreCase(name);
  }

  public static QueryShape queryShape(RewritePatternNode node) {
    ASTNode ast = node.getAstNode();
    if (ast instanceof ASTIterator iterator) {
      return new QueryShape(iterator.source, iterator.body, iterator.operation, List.of(iterator.iteratorName), List.of(""));
    }
    if (ast instanceof ASTQuery query) {
      List<String> names = query.variables.isEmpty()
          ? List.of("it")
          : query.variables.stream().map(v -> v.name).toList();
      List<String> types = query.variables.isEmpty()
          ? List.of("")
          : query.variables.stream().map(v -> v.typeName == null ? "" : v.typeName).toList();
      return new QueryShape(query.source, query.body, query.operation, names, types);
    }
    return new QueryShape(null, null, "", List.of(), List.of());
  }

  public static CompileContext enterQueryScope(QueryShape query, RewritePatternNode sourceNode, CompileContext context) {
    CompileContext scoped = context;
    String inferredType = inferCollectionElementType(sourceNode, context).orElse(null);
    for (int i = 0; i < query.variables().size(); i++) {
      String type = i < query.variableTypes().size() && !query.variableTypes().get(i).isBlank()
          ? query.variableTypes().get(i)
          : inferredType;
      scoped = scoped.enterScope(query.variables().get(i), type);
    }
    return scoped;
  }

  public static List<RewritePatternNode> withoutNulls(List<RewritePatternNode> nodes) {
    List<RewritePatternNode> result = new ArrayList<>();
    for (RewritePatternNode node : nodes) {
      if (node != null) result.add(node);
    }
    return result;
  }

  public record QueryShape(ASTNode sourceAst, ASTNode bodyAst, String operation, List<String> variables, List<String> variableTypes) {}
}
