package org.uet.dse.neo4j.oclgraphdb.multipass.pass;

import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
import org.uet.dse.neo4j.oclgraphdb.multipass.AstPatternSupport;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternContext;
import org.uet.dse.neo4j.oclgraphdb.multipass.MultiPassPatternKind;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternGraph;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternInstance;
import org.uet.dse.neo4j.oclgraphdb.multipass.PatternPass;
import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;
import org.uet.dse.neo4j.oclite.ast.ASTQualifiedNavigation;

public class SemanticPropertyPass implements PatternPass {
  @Override
  public String name() {
    return "semantic-property";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
      if (node instanceof ASTQualifiedNavigation navigation) {
        added += addQualifiedNavigation(graph, navigation, context, passNumber);
        continue;
      }

      if (!(node instanceof ASTProperty property)) continue;

      Optional<String> sourceType;
      try {
        sourceType = AstPatternSupport.inferNodeType(property.source, context.compileContext());
      } catch (RuntimeException ex) {
        sourceType = Optional.empty();
      }
      if (sourceType.isEmpty()) continue;

      Optional<PropertyDescriptor> attribute = context.compileContext().resolver().resolveAttribute(sourceType.get(), property.name);
      Optional<PropertyDescriptor> navigation = context.compileContext().resolver().resolveNavigation(sourceType.get(), property.name);

      if (attribute.isPresent()) {
        PropertyDescriptor descriptor = attribute.get();
        boolean booleanAttribute = !descriptor.isCollection() && isBooleanType(descriptor.getValueType());
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.ATTRIBUTE_ACCESS, node)
            .label(sourceType.get() + "." + property.name)
            .outputKind(descriptor.isCollection() ? ExpressionKind.COLLECTION : ExpressionKind.VALUE)
            .score(1500)
            .exactSemantic(true)
            .metadata("sourceType", sourceType.get())
            .metadata("ownerClass", descriptor.getOwnerClassName())
            .metadata("property", property.name)
            .metadata("valueType", descriptor.getValueType())
            .metadata("collection", descriptor.isCollection());
        if (graph.add(builder, passNumber).isPresent()) added++;

        if (booleanAttribute) {
          PatternInstance.Builder booleanBuilder = PatternInstance.builder(MultiPassPatternKind.BOOLEAN_ATTRIBUTE_ACCESS, node)
              .label(sourceType.get() + "." + property.name)
              .outputKind(ExpressionKind.BOOLEAN)
              .score(1650)
              .exactSemantic(true)
              .metadata("sourceType", sourceType.get())
              .metadata("ownerClass", descriptor.getOwnerClassName())
              .metadata("property", property.name)
              .metadata("valueType", descriptor.getValueType())
              .metadata("collection", false);
          if (graph.add(booleanBuilder, passNumber).isPresent()) added++;
        }
      }

      if (navigation.isPresent()) {
        PropertyDescriptor descriptor = navigation.get();
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.NAVIGATION_ACCESS, node)
            .label(sourceType.get() + "." + property.name)
            .outputKind(descriptor.isCollection() ? ExpressionKind.COLLECTION : ExpressionKind.NODE)
            .score(1500)
            .exactSemantic(true)
            .metadata("sourceType", sourceType.get())
            .metadata("ownerClass", descriptor.getOwnerClassName())
            .metadata("role", property.name)
            .metadata("targetClass", descriptor.getTargetClassName().orElse(""))
            .metadata("collection", descriptor.isCollection());
        if (graph.add(builder, passNumber).isPresent()) added++;
      }

      if (attribute.isPresent() && navigation.isPresent()) {
        PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.AMBIGUOUS_PROPERTY, node)
            .label(sourceType.get() + "." + property.name)
            .score(900)
            .metadata("sourceType", sourceType.get())
            .metadata("property", property.name);
        if (graph.add(builder, passNumber).isPresent()) added++;
      }
    }
    return added;
  }

  private int addQualifiedNavigation(
      PatternGraph graph,
      ASTQualifiedNavigation navigation,
      MultiPassPatternContext context,
      int passNumber
  ) {
    Optional<String> sourceType;
    try {
      sourceType = AstPatternSupport.inferNodeType(navigation.source, context.compileContext());
    } catch (RuntimeException ex) {
      sourceType = Optional.empty();
    }
    if (sourceType.isEmpty()) return 0;

    Optional<PropertyDescriptor> descriptor = context.compileContext()
        .resolver()
        .resolveNavigation(sourceType.get(), navigation.roleName);
    if (descriptor.isEmpty()) return 0;

    PatternInstance.Builder builder = PatternInstance.builder(MultiPassPatternKind.QUALIFIED_NAVIGATION, navigation)
        .label(sourceType.get() + "." + navigation.roleName + "[qualified]")
        .outputKind(descriptor.get().isCollection() ? ExpressionKind.COLLECTION : ExpressionKind.NODE)
        .score(1580)
        .exactSemantic(true)
        .metadata("sourceType", sourceType.get())
        .metadata("ownerClass", descriptor.get().getOwnerClassName())
        .metadata("role", navigation.roleName)
        .metadata("qualified", true)
        .metadata("targetClass", descriptor.get().getTargetClassName().orElse(""))
        .metadata("collection", descriptor.get().isCollection());
    return graph.add(builder, passNumber).isPresent() ? 1 : 0;
  }

  private boolean isBooleanType(String valueType) {
    if (valueType == null) return false;
    String normalized = valueType.trim().toLowerCase();
    return "boolean".equals(normalized) || "bool".equals(normalized);
  }
}
