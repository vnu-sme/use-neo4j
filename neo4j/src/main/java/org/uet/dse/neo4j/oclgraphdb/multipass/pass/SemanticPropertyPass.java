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

public class SemanticPropertyPass implements PatternPass {
  @Override
  public String name() {
    return "semantic-property";
  }

  @Override
  public int apply(PatternGraph graph, MultiPassPatternContext context, int passNumber) {
    int added = 0;
    for (ASTNode node : AstPatternSupport.flatten(graph.getRoot())) {
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
}
