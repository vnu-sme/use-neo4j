package org.uet.dse.neo4j.oclgraphdb;

import java.util.Optional;

public final class PropertyDescriptor {
  private final PropertyKind kind;
  private final String ownerClassName;
  private final String propertyName;
  private final String valueType;
  private final boolean collection;
  private final String targetClassName;

  private PropertyDescriptor(
      PropertyKind kind,
      String ownerClassName,
      String propertyName,
      String valueType,
      boolean collection,
      String targetClassName
  ) {
    this.kind = kind;
    this.ownerClassName = ownerClassName;
    this.propertyName = propertyName;
    this.valueType = valueType;
    this.collection = collection;
    this.targetClassName = targetClassName;
  }

  public static PropertyDescriptor attribute(
      String ownerClassName,
      String propertyName,
      String valueType,
      boolean collection
  ) {
    return new PropertyDescriptor(PropertyKind.ATTRIBUTE, ownerClassName, propertyName, valueType, collection, null);
  }

  public static PropertyDescriptor navigation(
      String ownerClassName,
      String propertyName,
      String targetClassName,
      boolean collection
  ) {
    return new PropertyDescriptor(PropertyKind.NAVIGATION, ownerClassName, propertyName, targetClassName, collection, targetClassName);
  }

  public static PropertyDescriptor ambiguous(String ownerClassName, String propertyName) {
    return new PropertyDescriptor(PropertyKind.AMBIGUOUS, ownerClassName, propertyName, "", false, null);
  }

  public static PropertyDescriptor unknown(String ownerClassName, String propertyName) {
    return new PropertyDescriptor(PropertyKind.UNKNOWN, ownerClassName, propertyName, "", false, null);
  }

  public PropertyKind getKind() {
    return kind;
  }

  public String getOwnerClassName() {
    return ownerClassName;
  }

  public String getPropertyName() {
    return propertyName;
  }

  public String getValueType() {
    return valueType;
  }

  public boolean isCollection() {
    return collection;
  }

  public Optional<String> getTargetClassName() {
    return Optional.ofNullable(targetClassName);
  }

  public boolean isAttribute() {
    return kind == PropertyKind.ATTRIBUTE;
  }

  public boolean isNavigation() {
    return kind == PropertyKind.NAVIGATION;
  }
}
