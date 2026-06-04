package org.uet.dse.neo4j.oclgraphdb;

import java.util.Optional;

/**
 * to decide type of the token in AST, for example d.age, compiler can't know if age is an attribute or a navigator through association
 */
public interface SemanticResolver {
  boolean isClass(String name);

  boolean isObject(String name);

  Optional<String> objectClass(String objectId);

  boolean isRelationship(String sourceClassName, String roleName);

  Optional<String> relationshipTargetClass(String sourceClassName, String roleName);

  Optional<PropertyDescriptor> resolveProperty(String sourceClassName, String propertyName);

  Optional<PropertyDescriptor> resolveAttribute(String sourceClassName, String propertyName);

  Optional<PropertyDescriptor> resolveNavigation(String sourceClassName, String roleName);
}
