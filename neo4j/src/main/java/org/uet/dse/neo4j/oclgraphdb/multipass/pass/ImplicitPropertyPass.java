package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.CompileContext;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.*;

public class ImplicitPropertyPass implements PatternPass {
  @Override
  public String name() {
    return "implicit-property";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    return visit(graph.getRoot(), graph, context.compileContext(), passNumber);
  }

  private int visit(ASTNode node, PatternGraph graph, CompileContext context, int passNumber) {
    if (node == null) return 0;
    int added = 0;

    if (node instanceof ASTVar var) {
      added += addImplicitProperty(graph, var, context, passNumber);
      return added;
    }

    if (node instanceof ASTIterator iterator) {
      added += visit(iterator.source, graph, context, passNumber);
      CompileContext scoped = AstPatternSupport.inferCollectionElementType(iterator.source, context)
          .map(type -> context.enterScope(iterator.iteratorName, type))
          .orElseGet(() -> context.enterScope(iterator.iteratorName));
      added += visit(iterator.body, graph, scoped, passNumber);
      return added;
    }

    if (node instanceof ASTQuery query) {
      added += visit(query.source, graph, context, passNumber);
      CompileContext scoped = queryScope(query, context);
      added += visit(query.body, graph, scoped, passNumber);
      return added;
    }

    for (ASTNode child : childrenOf(node)) {
      added += visit(child, graph, context, passNumber);
    }
    return added;
  }

  private int addImplicitProperty(PatternGraph graph, ASTVar var, CompileContext context, int passNumber) {
    if (isBoundName(var.name, context)) return 0;

    Optional<ImplicitBase> base = findImplicitBase(var.name, context);
    if (base.isEmpty()) return 0;

    MultiPassPatternKind kind = "self".equals(base.get().baseVariable())
        ? MultiPassPatternKind.IMPLICIT_SELF_PROPERTY_ACCESS
        : MultiPassPatternKind.IMPLICIT_ITERATOR_PROPERTY_ACCESS;
    PatternInstance.Builder builder = PatternInstance.builder(kind, var)
        .label(base.get().baseVariable() + "." + var.name)
        .outputKind(base.get().outputKind())
        .score("self".equals(base.get().baseVariable()) ? 1580 : 1660)
        .exactSemantic(true)
        .metadata("baseVar", base.get().baseVariable())
        .metadata("sourceType", base.get().sourceType())
        .metadata("property", var.name)
        .metadata("propertyKind", base.get().propertyKind())
        .metadata("collection", base.get().collection());
    base.get().valueType().ifPresent(value -> builder.metadata("valueType", value));
    base.get().targetClass().ifPresent(value -> builder.metadata("targetClass", value));
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private Optional<ImplicitBase> findImplicitBase(String propertyName, CompileContext context) {
    for (String variable : context.visibleVariables()) {
      Optional<String> type = context.variableType(variable);
      if (type.isEmpty()) continue;

      Optional<PropertyDescriptor> attribute = context.resolver().resolveAttribute(type.get(), propertyName);
      if (attribute.isPresent()) {
        PropertyDescriptor descriptor = attribute.get();
        ExpressionKind outputKind = descriptor.isCollection()
            ? ExpressionKind.COLLECTION
            : isBooleanType(descriptor.getValueType()) ? ExpressionKind.BOOLEAN : ExpressionKind.VALUE;
        return Optional.of(new ImplicitBase(
            variable,
            type.get(),
            "attribute",
            descriptor.isCollection(),
            outputKind,
            Optional.ofNullable(descriptor.getValueType()),
            Optional.empty()
        ));
      }

      Optional<PropertyDescriptor> navigation = context.resolver().resolveNavigation(type.get(), propertyName);
      if (navigation.isPresent()) {
        PropertyDescriptor descriptor = navigation.get();
        return Optional.of(new ImplicitBase(
            variable,
            type.get(),
            "navigation",
            descriptor.isCollection(),
            descriptor.isCollection() ? ExpressionKind.COLLECTION : ExpressionKind.NODE,
            Optional.empty(),
            descriptor.getTargetClassName()
        ));
      }
    }
    return Optional.empty();
  }

  private CompileContext queryScope(ASTQuery query, CompileContext context) {
    String inferredType = AstPatternSupport.inferCollectionElementType(query.source, context).orElse(null);
    if (query.variables.isEmpty()) {
      return context.enterScope(query.primaryVariableName(), inferredType);
    }

    CompileContext scoped = context;
    for (ASTVarDecl variable : query.variables) {
      scoped = scoped.enterScope(variable.name, variable.hasType() ? variable.typeName : inferredType);
    }
    return scoped;
  }

  private boolean isBoundName(String name, CompileContext context) {
    return context.hasExpressionBinding(name)
        || context.isLocalVariable(name)
        || context.hasObjectBinding(name)
        || context.resolver().isClass(name)
        || context.resolver().objectClass(name).isPresent();
  }

  private boolean isBooleanType(String valueType) {
    if (valueType == null) return false;
    String normalized = valueType.trim().toLowerCase();
    return "boolean".equals(normalized) || "bool".equals(normalized);
  }

  private List<ASTNode> childrenOf(ASTNode node) {
    if (node instanceof ASTContext context) return List.of(context.expression);
    if (node instanceof ASTBinary binary) return List.of(binary.left, binary.right);
    if (node instanceof ASTProperty property) return List.of(property.source);
    if (node instanceof ASTQualifiedNavigation navigation) return List.of(navigation.source, navigation.qualifier);
    if (node instanceof ASTMethodCall methodCall) return concat(methodCall.source, methodCall.args);
    if (node instanceof ASTCollectionOp collectionOp) return concat(collectionOp.source, collectionOp.args);
    if (node instanceof ASTIterate iterate) return List.of(iterate.source, iterate.accumulator.value, iterate.body);
    if (node instanceof ASTLet let) {
      java.util.ArrayList<ASTNode> children = new java.util.ArrayList<>();
      for (ASTLetBinding binding : let.bindings) children.add(binding.value);
      children.add(let.body);
      return children;
    }
    if (node instanceof ASTIf ifNode) return List.of(ifNode.condition, ifNode.thenExpression, ifNode.elseExpression);
    if (node instanceof ASTUnary unary) return List.of(unary.expression);
    if (node instanceof ASTCollectionLiteral literal) return List.copyOf(literal.items);
    if (node instanceof ASTRange range) return List.of(range.start, range.end);
    if (node instanceof ASTNot not) return List.of(not.expression);
    return List.of();
  }

  private List<ASTNode> concat(ASTNode first, List<? extends ASTNode> rest) {
    java.util.ArrayList<ASTNode> children = new java.util.ArrayList<>();
    children.add(first);
    children.addAll(rest);
    return children;
  }

  private record ImplicitBase(
      String baseVariable,
      String sourceType,
      String propertyKind,
      boolean collection,
      ExpressionKind outputKind,
      Optional<String> valueType,
      Optional<String> targetClass
  ) {}
}
