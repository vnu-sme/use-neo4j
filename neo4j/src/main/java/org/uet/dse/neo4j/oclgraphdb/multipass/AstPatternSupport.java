package org.uet.dse.neo4j.oclgraphdb.multipass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclite.ast.*;

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
    if (node instanceof ASTQualifiedNavigation navigation) return "qualified navigation ." + navigation.roleName + "[...]";
    if (node instanceof ASTMethodCall methodCall) return "method ." + methodCall.methodName + "()";
    if (node instanceof ASTCollectionOp collectionOp) return "collection ->" + collectionOp.opName + "()";
    if (node instanceof ASTIterator iterator) return "iterator ->" + iterator.operation + "(" + iterator.iteratorName + " | ...)";
    if (node instanceof ASTQuery query) return "query ->" + query.operation + "(" + query.primaryVariableName() + " | ...)";
    if (node instanceof ASTIterate iterate) return "iterate ->iterate(" + iterate.iterator.name + "; " + iterate.accumulator.variable.name + " | ...)";
    if (node instanceof ASTLet) return "let";
    if (node instanceof ASTIf) return "if";
    if (node instanceof ASTUnary unary) return "unary '" + unary.operator + "'";
    if (node instanceof ASTCollectionLiteral literal) return literal.kind + " literal";
    if (node instanceof ASTRange) return "range";
    if (node instanceof ASTNot) return "not";
    if (node instanceof ASTVar var) return "var " + var.name;
    if (node instanceof ASTIntegerLiteral literal) return "integer " + literal.value;
    if (node instanceof ASTRealLiteral literal) return "real " + literal.value;
    if (node instanceof ASTStringLiteral literal) return "string '" + literal.value + "'";
    if (node instanceof ASTBooleanLiteral literal) return "boolean " + literal.value;
    if (node instanceof ASTNullLiteral) return "null";
    if (node instanceof ASTUndefinedLiteral) return "undefined";
    if (node instanceof ASTEnumLiteral literal) return "enum " + literal.value();
    if (node instanceof ASTTypeLiteral literal) return "type " + literal.typeName;
    return node.getClass().getSimpleName();
  }

  public static Optional<String> inferCollectionElementType(ASTNode node, CompileContext context) {
    Optional<String> allInstancesType = allInstancesClassName(node, context);
    if (allInstancesType.isPresent()) return allInstancesType;
    if (node instanceof ASTIterator iterator && isSelectOrReject(iterator.operation)) {
      return inferCollectionElementType(iterator.source, context);
    }
    if (node instanceof ASTQuery query && isSelectOrReject(query.operation)) {
      return inferCollectionElementType(query.source, context);
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
    if (node instanceof ASTQualifiedNavigation navigation) {
      return inferNodeType(navigation.source, context)
          .flatMap(type -> context.resolver().resolveNavigation(type, navigation.roleName))
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
    if (node instanceof ASTProperty property && isAllInstancesName(property.name)) {
      return className(property.source, context);
    }
    if (node instanceof ASTMethodCall methodCall && isAllInstancesName(methodCall.methodName)) {
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
    return "and".equals(op) || "or".equals(op) || "xor".equals(op) || "implies".equals(op);
  }

  private static Optional<String> className(ASTNode node, CompileContext context) {
    if (node instanceof ASTVar var && context.resolver().isClass(var.name)) {
      return Optional.of(var.name);
    }
    return Optional.empty();
  }

  private static boolean isAllInstancesName(String name) {
    return "allInstances".equalsIgnoreCase(name) || "allInstance".equalsIgnoreCase(name);
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
    } else if (node instanceof ASTQualifiedNavigation navigation) {
      children.add(navigation.source);
      children.add(navigation.qualifier);
    } else if (node instanceof ASTMethodCall methodCall) {
      children.add(methodCall.source);
      children.addAll(methodCall.args);
    } else if (node instanceof ASTCollectionOp collectionOp) {
      children.add(collectionOp.source);
      children.addAll(collectionOp.args);
    } else if (node instanceof ASTIterator iterator) {
      children.add(iterator.source);
      children.add(iterator.body);
    } else if (node instanceof ASTQuery query) {
      children.add(query.source);
      children.add(query.body);
    } else if (node instanceof ASTIterate iterate) {
      children.add(iterate.source);
      children.add(iterate.accumulator.value);
      children.add(iterate.body);
    } else if (node instanceof ASTLet let) {
      for (ASTLetBinding binding : let.bindings) {
        children.add(binding.value);
      }
      children.add(let.body);
    } else if (node instanceof ASTIf ifNode) {
      children.add(ifNode.condition);
      children.add(ifNode.thenExpression);
      children.add(ifNode.elseExpression);
    } else if (node instanceof ASTUnary unary) {
      children.add(unary.expression);
    } else if (node instanceof ASTCollectionLiteral literal) {
      children.addAll(literal.items);
    } else if (node instanceof ASTRange range) {
      children.add(range.start);
      children.add(range.end);
    } else if (node instanceof ASTNot not) {
      children.add(not.expression);
    }
    return children.stream().filter(child -> child instanceof ASTExpression || child instanceof ASTContext).toList();
  }
}
