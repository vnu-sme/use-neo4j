package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclite.ast.ASTBinary;
import org.uet.dse.neo4j.oclite.ast.ASTBooleanLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTCollectionOp;
import org.uet.dse.neo4j.oclite.ast.ASTContext;
import org.uet.dse.neo4j.oclite.ast.ASTExpression;
import org.uet.dse.neo4j.oclite.ast.ASTIntegerLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTIterator;
import org.uet.dse.neo4j.oclite.ast.ASTMethodCall;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTNot;
import org.uet.dse.neo4j.oclite.ast.ASTNullLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTRealLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTStringLiteral;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public final class AstPatternSupport {
  private AstPatternSupport() {}

  public static List<ASTNode> flatten(ASTNode root) {
    List<ASTNode> nodes = new ArrayList<>();
    collect(root, nodes);
    return nodes;
  }

  public static int count(ASTNode root) {
    return flatten(root).size();
  }

  public static int maxDepth(ASTNode root) {
    return maxDepth(root, 1);
  }

  public static String labelOf(ASTNode node) {
    if (node == null) return "<null>";
    if (node instanceof ASTContext context) return "context " + context.className + " inv " + context.invName;
    if (node instanceof ASTBinary binary) return "binary '" + binary.op + "'";
    if (node instanceof ASTProperty property) return "property ." + property.name;
    if (node instanceof ASTMethodCall methodCall) return "method ." + methodCall.methodName + "()";
    if (node instanceof ASTCollectionOp collectionOp) return "collection ->" + collectionOp.opName + "()";
    if (node instanceof ASTIterator iterator) return "iterator ->" + iterator.operation + "(" + iterator.iteratorName + " | ...)";
    if (node instanceof ASTNot) return "not";
    if (node instanceof ASTVar var) return "var " + var.name;
    if (node instanceof ASTIntegerLiteral literal) return "integer " + literal.value;
    if (node instanceof ASTRealLiteral literal) return "real " + literal.value;
    if (node instanceof ASTStringLiteral literal) return "string '" + literal.value + "'";
    if (node instanceof ASTBooleanLiteral literal) return "boolean " + literal.value;
    if (node instanceof ASTNullLiteral) return "null";
    return node.getClass().getSimpleName();
  }

  public static Optional<String> inferCollectionElementType(ASTNode node, CompileContext context) {
    Optional<String> allInstancesType = allInstancesClassName(node, context);
    if (allInstancesType.isPresent()) return allInstancesType;
    if (node instanceof ASTIterator iterator && isSelectOrReject(iterator.operation)) {
      return inferCollectionElementType(iterator.source, context);
    }
    return inferNodeType(node, context);
  }

  public static Optional<String> inferNodeType(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var) {
      Optional<String> variableType = context.variableType(var.name);
      if (variableType.isPresent()) return variableType;
      return context.resolver().objectClass(var.name);
    }
    if (node instanceof ASTProperty property) {
      return inferNodeType(property.source, context)
          .flatMap(type -> context.resolver().resolveNavigation(type, property.name))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    if (node instanceof ASTMethodCall methodCall && methodCall.args.isEmpty()) {
      return inferNodeType(methodCall.source, context)
          .flatMap(type -> context.resolver().resolveNavigation(type, methodCall.methodName))
          .flatMap(descriptor -> descriptor.getTargetClassName());
    }
    return Optional.empty();
  }

  public static Optional<String> allInstancesClassName(ASTNode node, CompileContext context) {
    if (node instanceof ASTProperty property && "allInstances".equalsIgnoreCase(property.name)) {
      return className(property.source, context);
    }
    if (node instanceof ASTMethodCall methodCall && "allInstances".equalsIgnoreCase(methodCall.methodName)) {
      return className(methodCall.source, context);
    }
    return Optional.empty();
  }

  public static boolean isSelectOrReject(String operation) {
    return "select".equalsIgnoreCase(operation) || "reject".equalsIgnoreCase(operation);
  }

  public static boolean isComparison(String op) {
    return "=".equals(op) || "<>".equals(op) || ">".equals(op) || "<".equals(op) || ">=".equals(op) || "<=".equals(op);
  }

  public static boolean isLogical(String op) {
    return "and".equals(op) || "or".equals(op) || "implies".equals(op);
  }

  private static Optional<String> className(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var && context.resolver().isClass(var.name)) {
      return Optional.of(var.name);
    }
    return Optional.empty();
  }

  private static void collect(ASTNode node, List<ASTNode> result) {
    if (node == null) return;
    result.add(node);
    for (ASTNode child : childrenOf(node)) {
      collect(child, result);
    }
  }

  private static int maxDepth(ASTNode node, int depth) {
    if (node == null) return depth - 1;
    int max = depth;
    for (ASTNode child : childrenOf(node)) {
      max = Math.max(max, maxDepth(child, depth + 1));
    }
    return max;
  }

  private static List<ASTNode> childrenOf(ASTNode node) {
    List<ASTNode> children = new ArrayList<>();
    if (node instanceof ASTContext context) {
      children.add(context.expression);
    } else if (node instanceof ASTBinary binary) {
      children.add(binary.left);
      children.add(binary.right);
    } else if (node instanceof ASTProperty property) {
      children.add(property.source);
    } else if (node instanceof ASTMethodCall methodCall) {
      children.add(methodCall.source);
      children.addAll(methodCall.args);
    } else if (node instanceof ASTCollectionOp collectionOp) {
      children.add(collectionOp.source);
      children.addAll(collectionOp.args);
    } else if (node instanceof ASTIterator iterator) {
      children.add(iterator.source);
      children.add(iterator.body);
    } else if (node instanceof ASTNot not) {
      children.add(not.expression);
    }
    return children.stream().filter(child -> child instanceof ASTExpression || child instanceof ASTContext).toList();
  }
}
