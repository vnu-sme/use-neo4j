package org.uet.dse.neo4j.oclgraphdb;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.neo4j.oclite.Neo4jRepository;

public class Neo4jSemanticResolver implements SemanticResolver {
  private final String modelName;
  private final Neo4jRepository repository;
  private final Map<String, Optional<PropertyDescriptor>> attributeCache = new HashMap<>();
  private final Map<String, Optional<PropertyDescriptor>> navigationCache = new HashMap<>();
  private final Map<String, Optional<String>> objectClassCache = new HashMap<>();

  public Neo4jSemanticResolver(String modelName) {
    this.modelName = modelName;
    this.repository = new Neo4jRepository(modelName);
  }

  @Override
  public boolean isClass(String name) {
    return repository.checkClassExistsInDb(name);
  }

  @Override
  public boolean isObject(String name) {
    return repository.isVariableExist(name);
  }

  @Override
  public Optional<String> objectClass(String objectId) {
    return objectClassCache.computeIfAbsent(objectId, this::lookupObjectClass);
  }

  private Optional<String> lookupObjectClass(String objectId) {
    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      String cypher =
          "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(cls) " +
          "MATCH (obj {use_id: $objectId})-[:ObjectInstanceOf]->(cls) " +
          "RETURN cls.name AS className LIMIT 1";
      Map<String, Object> params = Map.of("modelName", modelName, "objectId", objectId);
      CypherQueryLogger.log("semantic-object-class", cypher, params);
      Result result = session.run(cypher, params);
      return result.hasNext() ? Optional.of(result.next().get("className").asString()) : Optional.empty();
    }
  }

  @Override
  public boolean isRelationship(String sourceClassName, String roleName) {
    return resolveNavigation(sourceClassName, roleName).isPresent();
  }

  @Override
  public Optional<String> relationshipTargetClass(String sourceClassName, String roleName) {
    return resolveNavigation(sourceClassName, roleName).flatMap(PropertyDescriptor::getTargetClassName);
  }

  @Override
  public Optional<PropertyDescriptor> resolveProperty(String sourceClassName, String propertyName) {
    Optional<PropertyDescriptor> attribute = resolveAttribute(sourceClassName, propertyName);
    Optional<PropertyDescriptor> navigation = resolveNavigation(sourceClassName, propertyName);
    if (attribute.isPresent() && navigation.isPresent()) {
      return Optional.of(PropertyDescriptor.ambiguous(sourceClassName, propertyName));
    }
    return attribute.or(() -> navigation);
  }

  @Override
  public Optional<PropertyDescriptor> resolveAttribute(String sourceClassName, String propertyName) {
    return attributeCache.computeIfAbsent(cacheKey(sourceClassName, propertyName), key -> lookupAttribute(sourceClassName, propertyName));
  }

  @Override
  public Optional<PropertyDescriptor> resolveNavigation(String sourceClassName, String roleName) {
    return navigationCache.computeIfAbsent(cacheKey(sourceClassName, roleName), key -> lookupNavigation(sourceClassName, roleName));
  }

  private Optional<PropertyDescriptor> lookupAttribute(String sourceClassName, String propertyName) {
    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      String cypher =
          "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta) " +
          "WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
          "MATCH (source {name: $sourceClass})-[:InstanceOf]->(meta) " +
          "MATCH (source)-[:Extends*0..]->(declaring) " +
          "MATCH (declaring)-[:HasAttribute]->(attr) " +
          "WHERE attr.attrName = $propertyName OR attr.name = $qualifiedName OR attr.name ENDS WITH $suffix " +
          "RETURN declaring.name AS ownerClass, " +
          "       coalesce(attr.attrName, $propertyName) AS propertyName, " +
          "       coalesce(attr.type, '') AS valueType, " +
          "       coalesce(attr.isCollection, false) AS isCollection " +
          "ORDER BY CASE WHEN declaring = source THEN 0 ELSE 1 END, coalesce(attr.index, 999999) " +
          "LIMIT 1";
      Map<String, Object> params = Map.of(
          "modelName", modelName,
          "sourceClass", sourceClassName,
          "propertyName", propertyName,
          "qualifiedName", sourceClassName + "_" + propertyName,
          "suffix", "_" + propertyName
      );
      CypherQueryLogger.log("semantic-attribute", cypher, params);
      Result result = session.run(cypher, params);
      if (!result.hasNext()) return Optional.empty();

      Record record = result.next();
      return Optional.of(PropertyDescriptor.attribute(
          stringValue(record, "ownerClass"),
          stringValue(record, "propertyName"),
          stringValue(record, "valueType"),
          booleanValue(record, "isCollection")
      ));
    }
  }

  private Optional<PropertyDescriptor> lookupNavigation(String sourceClassName, String roleName) {
    try (Session session = Neo4jDriverManager.getInstance().openSession()) {
      String cypher =
          "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta) " +
          "WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass'] " +
          "MATCH (source {name: $sourceClass})-[:InstanceOf]->(meta) " +
          "MATCH (source)-[:Extends*0..]->(declaring) " +
          "MATCH (declaring)-[r]-(target) " +
          "WHERE type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates'] " +
          "  AND ( " +
          "    (startNode(r) = declaring AND (r.targerClassrole = $role OR r.associationName = $role)) " +
          "    OR " +
          "    (endNode(r) = declaring AND (r.sourceClassrole = $role OR r.associationName = $role)) " +
          "  ) " +
          "RETURN declaring.name AS ownerClass, " +
          "       target.name AS targetClass, " +
          "       CASE WHEN startNode(r) = declaring THEN r.targetMultiplicity ELSE r.sourceMultiplicity END AS multiplicity " +
          "ORDER BY CASE WHEN declaring = source THEN 0 ELSE 1 END " +
          "LIMIT 1";
      Map<String, Object> params = Map.of(
          "modelName", modelName,
          "sourceClass", sourceClassName,
          "role", roleName
      );
      CypherQueryLogger.log("semantic-relationship-target-class", cypher, params);
      Result result = session.run(cypher, params);
      if (!result.hasNext()) return Optional.empty();

      Record record = result.next();
      return Optional.of(PropertyDescriptor.navigation(
          stringValue(record, "ownerClass"),
          roleName,
          stringValue(record, "targetClass"),
          isManyMultiplicity(stringValue(record, "multiplicity"))
      ));
    }
  }

  private String cacheKey(String sourceClassName, String propertyName) {
    return sourceClassName + "\u0000" + propertyName;
  }

  private String stringValue(Record record, String key) {
    Value value = record.get(key);
    return value == null || value.isNull() ? "" : value.asString();
  }

  private boolean booleanValue(Record record, String key) {
    Value value = record.get(key);
    return value != null && !value.isNull() && value.asBoolean(false);
  }

  private boolean isManyMultiplicity(String multiplicity) {
    if (isBlank(multiplicity)) return true;
    String normalized = multiplicity.trim().toLowerCase();
    if (normalized.contains("*") || normalized.contains("many")) return true;
    if (!normalized.contains("..")) return !normalized.equals("1");
    String upper = normalized.substring(normalized.indexOf("..") + 2).trim();
    return !upper.equals("1");
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
