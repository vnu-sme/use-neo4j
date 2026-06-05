package org.uet.dse.neo4j.oclgraphdb.rewrite.pass;

import java.util.List;
import java.util.Optional;
import org.uet.dse.neo4j.oclgraphdb.PropertyDescriptor;
import org.uet.dse.neo4j.oclgraphdb.rewrite.*;
import org.uet.dse.neo4j.oclite.ast.ASTProperty;

public class SemanticPropertyTreePass implements RewritePass {
  @Override
  public String name() {
    return "rewrite-semantic-property";
  }

  @Override
  public RewritePassResult apply(RewritePatternNode root, RewriteContext context, int round) {
    return RewriteTreeRewriter.bottomUp(root, context, round, node -> rewrite(node, context, round));
  }

  private RewritePatternNode rewrite(RewritePatternNode node, RewriteContext context, int round) {
    if (node.getKind() != RewritePatternKind.PROPERTY_ACCESS || !(node.getAstNode() instanceof ASTProperty property)) {
      return node;
    }
    if (node.getChildren().isEmpty()) return node;

    Optional<String> sourceType = RewriteNodeSupport.inferNodeType(node.getChildren().get(0), context.compileContext());
    if (sourceType.isEmpty()) return node;

    Optional<PropertyDescriptor> attribute = context.compileContext().resolver().resolveAttribute(sourceType.get(), property.name);
    Optional<PropertyDescriptor> navigation = context.compileContext().resolver().resolveNavigation(sourceType.get(), property.name);

    if (attribute.isPresent() && navigation.isPresent()) {
      return context.nodeFactory().builder(RewritePatternKind.AMBIGUOUS_PROPERTY, node.getAstNode(), round)
          .label(sourceType.get() + "." + property.name)
          .children(node.getChildren())
          .semantic(RewriteSemantic.unknown().toBuilder().exact(false).build())
          .metadata(node.getMetadata())
          .metadata("sourceType", sourceType.get())
          .metadata("property", property.name)
          .score(900 + node.getChildren().get(0).getScore() / 10)
          .build();
    }

    if (attribute.isPresent()) {
      PropertyDescriptor descriptor = attribute.get();
      RewriteSemantic semantic = descriptor.isCollection()
          ? RewriteSemantic.collection(descriptor.getValueType(), RewriteCollectionKind.UNKNOWN)
          : isBooleanType(descriptor.getValueType()) ? RewriteSemantic.bool() : RewriteSemantic.value(descriptor.getValueType());
      RewritePatternKind kind = !descriptor.isCollection() && isBooleanType(descriptor.getValueType())
          ? RewritePatternKind.BOOLEAN_ATTRIBUTE_ACCESS
          : RewritePatternKind.ATTRIBUTE_ACCESS;
      return context.nodeFactory().builder(kind, node.getAstNode(), round)
          .label(sourceType.get() + "." + property.name)
          .children(List.of(node.getChildren().get(0)))
          .semantic(semantic)
          .metadata(node.getMetadata())
          .metadata("sourceType", sourceType.get())
          .metadata("ownerClass", descriptor.getOwnerClassName())
          .metadata("property", property.name)
          .metadata("valueType", descriptor.getValueType())
          .metadata("collection", descriptor.isCollection())
          .score(1500 + node.getChildren().get(0).getScore() / 10)
          .build();
    }

    if (navigation.isPresent()) {
      PropertyDescriptor descriptor = navigation.get();
      String targetClass = descriptor.getTargetClassName().orElse("");
      RewriteSemantic semantic = descriptor.isCollection()
          ? RewriteSemantic.collection(targetClass, RewriteCollectionKind.COLLECTION)
          : RewriteSemantic.node(targetClass, RewriteCardinality.ZERO_OR_ONE);
      return context.nodeFactory().builder(RewritePatternKind.NAVIGATION_ACCESS, node.getAstNode(), round)
          .label(sourceType.get() + "." + property.name)
          .children(List.of(node.getChildren().get(0)))
          .semantic(semantic)
          .metadata(node.getMetadata())
          .metadata("sourceType", sourceType.get())
          .metadata("ownerClass", descriptor.getOwnerClassName())
          .metadata("role", property.name)
          .metadata("targetClass", targetClass)
          .metadata("collection", descriptor.isCollection())
          .score(1500 + node.getChildren().get(0).getScore() / 10)
          .build();
    }

    return node;
  }

  private boolean isBooleanType(String valueType) {
    if (valueType == null) return false;
    String normalized = valueType.trim().toLowerCase();
    return "boolean".equals(normalized) || "bool".equals(normalized);
  }
}
