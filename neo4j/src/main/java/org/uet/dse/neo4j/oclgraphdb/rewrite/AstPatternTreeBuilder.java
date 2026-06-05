package org.uet.dse.neo4j.oclgraphdb.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.*;

public class AstPatternTreeBuilder {
  private static final int PRIMITIVE_PASS = 0;
  private final RewritePatternNodeFactory factory;

  public AstPatternTreeBuilder(RewritePatternNodeFactory factory) {
    this.factory = factory;
  }

  public RewritePatternNode build(ASTNode node) {
    if (node == null) {
      return factory.builder(RewritePatternKind.UNSUPPORTED, null, PRIMITIVE_PASS)
          .label("<null>")
          .semantic(RewriteSemantic.unknown())
          .score(0)
          .build();
    }

    if (node instanceof ASTContext context) {
      return node(RewritePatternKind.CONTEXT, node, "context " + context.className + "::" + context.invName)
          .children(List.of(build(context.expression)))
          .metadata("class", context.className)
          .metadata("invariant", context.invName)
          .metadata("contextVariable", context.contextVariableName)
          .semantic(RewriteSemantic.bool().toBuilder().exact(false).build())
          .score(500)
          .build();
    }

    if (node instanceof ASTBinary binary) {
      RewriteSemantic semantic = binarySemantic(binary.op);
      return node(RewritePatternKind.BINARY, node, binary.op)
          .children(List.of(build(binary.left), build(binary.right)))
          .metadata("operator", binary.op)
          .semantic(semantic)
          .score(500)
          .build();
    }

    if (node instanceof ASTProperty property) {
      return node(RewritePatternKind.PROPERTY_ACCESS, node, "." + property.name)
          .children(List.of(build(property.source)))
          .metadata("property", property.name)
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTQualifiedNavigation navigation) {
      return node(RewritePatternKind.PROPERTY_ACCESS, node, "." + navigation.roleName + "[...]")
          .children(List.of(build(navigation.source), build(navigation.qualifier)))
          .metadata("property", navigation.roleName)
          .metadata("qualified", true)
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTMethodCall methodCall) {
      List<RewritePatternNode> children = new ArrayList<>();
      children.add(build(methodCall.source));
      methodCall.args.forEach(arg -> children.add(build(arg)));
      return node(RewritePatternKind.METHOD_CALL, node, "." + methodCall.methodName + "()")
          .children(children)
          .metadata("method", methodCall.methodName)
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTCollectionOp collectionOp) {
      List<RewritePatternNode> children = new ArrayList<>();
      children.add(build(collectionOp.source));
      collectionOp.args.forEach(arg -> children.add(build(arg)));
      return node(RewritePatternKind.COLLECTION_OPERATION, node, "->" + collectionOp.opName + "()")
          .children(children)
          .metadata("operation", collectionOp.opName)
          .semantic(collectionOperationSemantic(collectionOp.opName))
          .score(500)
          .build();
    }

    if (node instanceof ASTIterator iterator) {
      return node(RewritePatternKind.ITERATOR, node, "->" + iterator.operation + "(" + iterator.iteratorName + " | ...)")
          .children(List.of(build(iterator.source), build(iterator.body)))
          .metadata("operation", iterator.operation)
          .metadata("iterator", iterator.iteratorName)
          .semantic(iteratorSemantic(iterator.operation))
          .score(500)
          .build();
    }

    if (node instanceof ASTQuery query) {
      return node(RewritePatternKind.QUERY, node, "->" + query.operation + "(" + query.primaryVariableName() + " | ...)")
          .children(List.of(build(query.source), build(query.body)))
          .metadata("operation", query.operation)
          .metadata("iterators", query.variables.stream().map(v -> v.name).collect(Collectors.joining(",")))
          .metadata("iteratorTypes", query.variables.stream().map(v -> v.typeName == null ? "" : v.typeName).collect(Collectors.joining(",")))
          .semantic(iteratorSemantic(query.operation))
          .score(500)
          .build();
    }

    if (node instanceof ASTIterate iterate) {
      return node(RewritePatternKind.ITERATE, node, "->iterate(" + iterate.iterator.name + "; " + iterate.accumulator.variable.name + " | ...)")
          .children(List.of(build(iterate.source), build(iterate.accumulator.value), build(iterate.body)))
          .metadata("iterator", iterate.iterator.name)
          .metadata("iteratorType", iterate.iterator.typeName)
          .metadata("accumulator", iterate.accumulator.variable.name)
          .metadata("accumulatorType", iterate.accumulator.variable.typeName)
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTLet let) {
      List<RewritePatternNode> children = new ArrayList<>();
      for (ASTLetBinding binding : let.bindings) {
        children.add(build(binding.value));
      }
      children.add(build(let.body));
      return node(RewritePatternKind.LET, node, "let")
          .children(children)
          .metadata("bindings", let.bindings.stream().map(b -> b.variable.name).collect(Collectors.joining(",")))
          .metadata("bindingTypes", let.bindings.stream().map(b -> b.variable.typeName == null ? "" : b.variable.typeName).collect(Collectors.joining(",")))
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTIf ifNode) {
      return node(RewritePatternKind.IF, node, "if")
          .children(List.of(build(ifNode.condition), build(ifNode.thenExpression), build(ifNode.elseExpression)))
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (node instanceof ASTUnary unary) {
      return node(RewritePatternKind.UNARY, node, unary.operator)
          .children(List.of(build(unary.expression)))
          .metadata("operator", unary.operator)
          .semantic("not".equals(unary.operator) ? RewriteSemantic.bool() : RewriteSemantic.value("Real"))
          .score(500)
          .build();
    }

    if (node instanceof ASTNot not) {
      return node(RewritePatternKind.NOT, node, "not")
          .children(List.of(build(not.expression)))
          .semantic(RewriteSemantic.bool())
          .score(500)
          .build();
    }

    if (node instanceof ASTCollectionLiteral literal) {
      List<RewritePatternNode> children = literal.items.stream().map(this::build).toList();
      return node(RewritePatternKind.COLLECTION_LITERAL, node, literal.kind + "{}")
          .children(children)
          .metadata("collectionKind", literal.kind)
          .semantic(RewriteSemantic.collection("", collectionKind(literal.kind)))
          .score(500)
          .build();
    }

    if (node instanceof ASTRange range) {
      return node(RewritePatternKind.RANGE, node, "..")
          .children(List.of(build(range.start), build(range.end)))
          .semantic(RewriteSemantic.collection("Integer", RewriteCollectionKind.SEQUENCE))
          .score(500)
          .build();
    }

    if (node instanceof ASTVar var) {
      return node(RewritePatternKind.VARIABLE, node, var.name)
          .metadata("name", var.name)
          .semantic(RewriteSemantic.unknown())
          .score(500)
          .build();
    }

    if (isLiteral(node)) {
      return node(RewritePatternKind.LITERAL, node, labelLiteral(node))
          .semantic(literalSemantic(node))
          .score(500)
          .build();
    }

    return node(RewritePatternKind.UNSUPPORTED, node, node.getClass().getSimpleName())
        .semantic(RewriteSemantic.unknown())
        .score(0)
        .build();
  }

  private RewritePatternNode.Builder node(RewritePatternKind kind, ASTNode astNode, String label) {
    return factory.builder(kind, astNode, PRIMITIVE_PASS).label(label);
  }

  private RewriteSemantic binarySemantic(String op) {
    if ("and".equals(op) || "or".equals(op) || "xor".equals(op) || "implies".equals(op)
        || "=".equals(op) || "<>".equals(op) || ">".equals(op) || "<".equals(op)
        || ">=".equals(op) || "<=".equals(op)) {
      return RewriteSemantic.bool().toBuilder().exact(false).build();
    }
    return RewriteSemantic.value("Real").toBuilder().exact(false).build();
  }

  private RewriteSemantic collectionOperationSemantic(String op) {
    return switch (op) {
      case "size", "count", "sum", "avg", "average", "max", "min" -> RewriteSemantic.value("Real");
      case "isEmpty", "isNotEmpty", "notEmpty", "includes", "contains", "excludes", "includesAll", "excludesAll" -> RewriteSemantic.bool();
      case "first", "last", "at" -> RewriteSemantic.unknown().toBuilder().outputKind(ExpressionKind.VALUE).build();
      default -> RewriteSemantic.collection("", RewriteCollectionKind.UNKNOWN).toBuilder().exact(false).build();
    };
  }

  private RewriteSemantic iteratorSemantic(String op) {
    return switch (op.toLowerCase()) {
      case "forall", "exists", "one", "isunique" -> RewriteSemantic.bool();
      case "any" -> RewriteSemantic.node("", RewriteCardinality.ZERO_OR_ONE);
      default -> RewriteSemantic.collection("", RewriteCollectionKind.UNKNOWN).toBuilder().exact(false).build();
    };
  }

  private RewriteCollectionKind collectionKind(String kind) {
    return switch (kind) {
      case "Set" -> RewriteCollectionKind.SET;
      case "OrderedSet" -> RewriteCollectionKind.ORDERED_SET;
      case "Sequence" -> RewriteCollectionKind.SEQUENCE;
      case "Bag" -> RewriteCollectionKind.BAG;
      default -> RewriteCollectionKind.UNKNOWN;
    };
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

  private RewriteSemantic literalSemantic(ASTNode node) {
    if (node instanceof ASTBooleanLiteral) return RewriteSemantic.bool();
    if (node instanceof ASTIntegerLiteral) return RewriteSemantic.value("Integer");
    if (node instanceof ASTRealLiteral) return RewriteSemantic.value("Real");
    if (node instanceof ASTTypeLiteral type) return RewriteSemantic.type(type.typeName);
    if (node instanceof ASTNullLiteral || node instanceof ASTUndefinedLiteral) {
      return RewriteSemantic.unknown().toBuilder().nullSemantics(RewriteNullSemantics.NULL_AS_UNDEFINED).exact(true).build();
    }
    return RewriteSemantic.value("String");
  }

  private String labelLiteral(ASTNode node) {
    if (node instanceof ASTIntegerLiteral literal) return Long.toString(literal.value);
    if (node instanceof ASTRealLiteral literal) return Double.toString(literal.value);
    if (node instanceof ASTStringLiteral literal) return "'" + literal.value + "'";
    if (node instanceof ASTBooleanLiteral literal) return Boolean.toString(literal.value);
    if (node instanceof ASTEnumLiteral literal) return literal.value();
    if (node instanceof ASTTypeLiteral literal) return literal.typeName;
    if (node instanceof ASTUndefinedLiteral) return "undefined";
    return "null";
  }
}
