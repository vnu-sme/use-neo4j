package org.uet.dse.neo4j.oclgraphdb.rewrite;

import org.uet.dse.neo4j.oclgraphdb.plan.ExpressionKind;

public class RewriteSemantic {
  private final ExpressionKind outputKind;
  private final RewriteCollectionKind collectionKind;
  private final RewriteCardinality cardinality;
  private final RewriteNullSemantics nullSemantics;
  private final String nodeType;
  private final String valueType;
  private final boolean exact;
  private final boolean preservesOrder;

  private RewriteSemantic(
      ExpressionKind outputKind,
      RewriteCollectionKind collectionKind,
      RewriteCardinality cardinality,
      RewriteNullSemantics nullSemantics,
      String nodeType,
      String valueType,
      boolean exact,
      boolean preservesOrder
  ) {
    this.outputKind = outputKind;
    this.collectionKind = collectionKind;
    this.cardinality = cardinality;
    this.nullSemantics = nullSemantics;
    this.nodeType = nodeType == null ? "" : nodeType;
    this.valueType = valueType == null ? "" : valueType;
    this.exact = exact;
    this.preservesOrder = preservesOrder;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static RewriteSemantic unknown() {
    return builder().build();
  }

  public static RewriteSemantic value(String valueType) {
    return builder().outputKind(ExpressionKind.VALUE).valueType(valueType).cardinality(RewriteCardinality.ONE).exact(true).build();
  }

  public static RewriteSemantic bool() {
    return builder().outputKind(ExpressionKind.BOOLEAN).valueType("Boolean").cardinality(RewriteCardinality.ONE).exact(true).build();
  }

  public static RewriteSemantic node(String nodeType, RewriteCardinality cardinality) {
    return builder().outputKind(ExpressionKind.NODE).nodeType(nodeType).cardinality(cardinality).exact(true).build();
  }

  public static RewriteSemantic collection(String elementType, RewriteCollectionKind kind) {
    return builder()
        .outputKind(ExpressionKind.COLLECTION)
        .collectionKind(kind)
        .nodeType(elementType)
        .cardinality(RewriteCardinality.MANY)
        .exact(true)
        .build();
  }

  public static RewriteSemantic type(String typeName) {
    return builder().outputKind(ExpressionKind.TYPE).valueType(typeName).cardinality(RewriteCardinality.ONE).exact(true).build();
  }

  public ExpressionKind getOutputKind() {
    return outputKind;
  }

  public RewriteCollectionKind getCollectionKind() {
    return collectionKind;
  }

  public RewriteCardinality getCardinality() {
    return cardinality;
  }

  public RewriteNullSemantics getNullSemantics() {
    return nullSemantics;
  }

  public String getNodeType() {
    return nodeType;
  }

  public String getValueType() {
    return valueType;
  }

  public boolean isExact() {
    return exact;
  }

  public boolean isPreservesOrder() {
    return preservesOrder;
  }

  public boolean isBooleanLike() {
    return outputKind == ExpressionKind.BOOLEAN;
  }

  public boolean isValueLike() {
    return outputKind == ExpressionKind.VALUE || outputKind == ExpressionKind.NODE || outputKind == ExpressionKind.BOOLEAN;
  }

  public boolean isCollectionLike() {
    return outputKind == ExpressionKind.COLLECTION;
  }

  public String signature() {
    return outputKind + "|"
        + collectionKind + "|"
        + cardinality + "|"
        + nullSemantics + "|"
        + nodeType + "|"
        + valueType + "|"
        + exact + "|"
        + preservesOrder;
  }

  public Builder toBuilder() {
    return builder()
        .outputKind(outputKind)
        .collectionKind(collectionKind)
        .cardinality(cardinality)
        .nullSemantics(nullSemantics)
        .nodeType(nodeType)
        .valueType(valueType)
        .exact(exact)
        .preservesOrder(preservesOrder);
  }

  public static class Builder {
    private ExpressionKind outputKind;
    private RewriteCollectionKind collectionKind = RewriteCollectionKind.UNKNOWN;
    private RewriteCardinality cardinality = RewriteCardinality.UNKNOWN;
    private RewriteNullSemantics nullSemantics = RewriteNullSemantics.UNKNOWN;
    private String nodeType = "";
    private String valueType = "";
    private boolean exact;
    private boolean preservesOrder = true;

    public Builder outputKind(ExpressionKind outputKind) {
      this.outputKind = outputKind;
      return this;
    }

    public Builder collectionKind(RewriteCollectionKind collectionKind) {
      this.collectionKind = collectionKind == null ? RewriteCollectionKind.UNKNOWN : collectionKind;
      return this;
    }

    public Builder cardinality(RewriteCardinality cardinality) {
      this.cardinality = cardinality == null ? RewriteCardinality.UNKNOWN : cardinality;
      return this;
    }

    public Builder nullSemantics(RewriteNullSemantics nullSemantics) {
      this.nullSemantics = nullSemantics == null ? RewriteNullSemantics.UNKNOWN : nullSemantics;
      return this;
    }

    public Builder nodeType(String nodeType) {
      this.nodeType = nodeType == null ? "" : nodeType;
      return this;
    }

    public Builder valueType(String valueType) {
      this.valueType = valueType == null ? "" : valueType;
      return this;
    }

    public Builder exact(boolean exact) {
      this.exact = exact;
      return this;
    }

    public Builder preservesOrder(boolean preservesOrder) {
      this.preservesOrder = preservesOrder;
      return this;
    }

    public RewriteSemantic build() {
      return new RewriteSemantic(outputKind, collectionKind, cardinality, nullSemantics, nodeType, valueType, exact, preservesOrder);
    }
  }
}
