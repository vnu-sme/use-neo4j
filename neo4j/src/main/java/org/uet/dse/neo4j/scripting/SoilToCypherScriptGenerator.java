package org.uet.dse.neo4j.scripting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Offline SOIL-to-Cypher generator for Neo4j object-state creation.
 *
 * Usage:
 *   java org.uet.dse.neo4j.scripting.SoilToCypherScriptGenerator MedicalSystem input.soil output.cypher
 *
 * The generated Cypher assumes the model metamodel has already been pushed to Neo4j.
 */
public class SoilToCypherScriptGenerator {
  private static final Pattern CREATE_OBJECT =
      Pattern.compile("^!create\\s+(\\w+)\\s*:\\s*(\\w+)\\s*$");
  private static final Pattern CREATE_LINK_OBJECT =
      Pattern.compile("^!create\\s+(\\w+)\\s*:\\s*(\\w+)\\s+between\\s*\\(([^)]*)\\)\\s*$");
  private static final Pattern SET_ATTRIBUTE =
      Pattern.compile("^!set\\s+(\\w+)\\.(\\w+)\\s*:=\\s*(.+)$");
  private static final Pattern INSERT_LINK =
      Pattern.compile("^!insert\\s*\\(([^)]*)\\)\\s+into\\s+(\\w+)\\s*$");

  private final String modelName;
  private final Map<String, String> objectTypes = new HashMap<>();
  private final List<CreateObject> creates = new ArrayList<>();
  private final List<CreateLinkObject> linkObjectCreates = new ArrayList<>();
  private final List<SetAttribute> attributeSets = new ArrayList<>();
  private final List<InsertLink> links = new ArrayList<>();

  public SoilToCypherScriptGenerator(String modelName) {
    this.modelName = modelName;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("Usage: SoilToCypherScriptGenerator <modelName> <input.soil> <output.cypher>");
      System.exit(1);
    }

    SoilToCypherScriptGenerator generator = new SoilToCypherScriptGenerator(args[0]);
    generator.parse(Path.of(args[1]));
    Files.writeString(Path.of(args[2]), generator.generateCypher());
    System.out.println("Generated Cypher: " + args[2]);
  }

  public void parse(Path soilFile) throws IOException {
    int lineNo = 0;
    for (String rawLine : Files.readAllLines(soilFile)) {
      lineNo++;
      String line = stripComment(rawLine).trim();
      if (line.isEmpty() || line.equals("check")) continue;

      Matcher linkObject = CREATE_LINK_OBJECT.matcher(line);
      Matcher object = CREATE_OBJECT.matcher(line);
      Matcher set = SET_ATTRIBUTE.matcher(line);
      Matcher insert = INSERT_LINK.matcher(line);

      if (linkObject.matches()) {
        String name = linkObject.group(1);
        String className = linkObject.group(2);
        List<String> participants = parseIdentifierList(linkObject.group(3));
        objectTypes.put(name, className);
        creates.add(new CreateObject(name, className));
        linkObjectCreates.add(new CreateLinkObject(name, className, participants));
      } else if (object.matches()) {
        String name = object.group(1);
        String className = object.group(2);
        objectTypes.put(name, className);
        creates.add(new CreateObject(name, className));
      } else if (set.matches()) {
        attributeSets.add(new SetAttribute(set.group(1), set.group(2), parseValue(set.group(3).trim())));
      } else if (insert.matches()) {
        links.add(new InsertLink(insert.group(2), parseIdentifierList(insert.group(1))));
      } else {
        throw new IllegalArgumentException("Unsupported SOIL at line " + lineNo + ": " + rawLine);
      }
    }
  }

  public String generateCypher() {
    StringBuilder sb = new StringBuilder();
    sb.append("// Generated from SOIL for model ").append(cypherString(modelName)).append("\n");
    sb.append("// Run after syncing the USE model/metamodel to Neo4j.\n\n");

    for (CreateObject create : creates) {
      sb.append(createObjectCypher(create.name, create.className)).append("\n");
    }

    for (SetAttribute set : attributeSets) {
      sb.append(setAttributeCypher(set)).append("\n");
    }

    for (InsertLink link : links) {
      sb.append(link.participants.size() == 2
          ? binaryLinkCypher(link)
          : ternaryLinkCypher(link.associationName, link.participants));
      sb.append("\n");
    }

    for (CreateLinkObject linkObject : linkObjectCreates) {
      sb.append(linkObjectSpokesCypher(linkObject)).append("\n");
    }

    sb.append("MERGE (v:ModelVersion {id: 'CURRENT'}) SET v.objectTimestamp = timestamp();\n");
    return sb.toString();
  }

  private String createObjectCypher(String objectName, String className) {
    String obj = cypherString(objectName);
    String cls = cypherString(className);
    return """
        // create object %s : %s
        MATCH (m:ManageModel {name: %s})-[:DefineMetamodels]->(meta:MetaNode)
        WHERE meta.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass']
        MATCH (cls:`%s` {name: %s})-[:InstanceOf]->(meta)
        MERGE (obj:`%s`:Object {use_id: %s})
        SET obj.name = %s, obj.isCorrespondence = false
        MERGE (obj)-[:ObjectInstanceOf]->(cls);
        """.formatted(objectName, className, cypherString(modelName), className, cls, className, obj, obj);
  }

  private String setAttributeCypher(SetAttribute set) {
    String obj = cypherString(set.objectName);
    String attr = cypherString(set.attributeName);
    String attrValueId = cypherString(set.objectName + "_" + set.attributeName);
    StoredValue stored = toStoredValue(set.value);

    StringBuilder sb = new StringBuilder();
    sb.append("""
        // set %s.%s
        MATCH (obj {use_id: %s})-[:ObjectInstanceOf]->(cls)
        MATCH path = (cls)-[:Extends*0..]->(owner)
        MATCH (owner)-[:HasAttribute]->(attrDef:Attribute)
        WHERE attrDef.attrName = %s OR attrDef.name ENDS WITH '_' + %s
        WITH obj, attrDef, length(path) AS depth
        ORDER BY depth ASC
        WITH obj, collect(attrDef)[0] AS attrDef
        OPTIONAL MATCH (obj)-[:ObjectHasAttribute]->(old:AttributeValue {name: %s})
        OPTIONAL MATCH (old)-[:HasNestedCollectionValue*1..10]->(nested)
        DETACH DELETE old, nested
        WITH obj, attrDef
        CREATE (val:AttributeValue {name: %s})
        SET val.type = attrDef.type,
            val.value = %s,
            val.isCollection = coalesce(attrDef.isCollection, false),
            val.collectionType = coalesce(attrDef.collectionType, 'None'),
            val.isNestedCollection = coalesce(attrDef.isNestedCollection, false)
        CREATE (obj)-[:ObjectHasAttribute]->(val)
        CREATE (val)-[:InstanceOf]->(attrDef);
        """.formatted(set.objectName, set.attributeName, obj, attr, attr, attrValueId, attrValueId, cypherString(stored.value)));

    appendReferenceEdges(sb, attrValueId, stored.referenceIds);
    appendNestedCollections(sb, set.objectName + "_" + set.attributeName, set.value);
    return sb.toString();
  }

  private void appendReferenceEdges(StringBuilder sb, String attrValueId, List<String> refs) {
    for (int i = 0; i < refs.size(); i++) {
      String target = cypherString(refs.get(i));
      sb.append("""
          MATCH (val:AttributeValue {name: %s}), (target {use_id: %s})
          MERGE (val)-[hr:HasReferenceValue {index: %d}]->(target)
          MERGE (val)-[or:objectReference {index: %d}]->(target);
          """.formatted(attrValueId, target, i, i));
    }
  }

  private void appendNestedCollections(StringBuilder sb, String rootId, SoilValue value) {
    if (!(value instanceof CollectionValue collection) || !collection.hasNestedCollection()) return;
    appendNestedCollectionLevel(sb, rootId, collection);
  }

  private void appendNestedCollectionLevel(StringBuilder sb, String parentId, CollectionValue collection) {
    List<String> primitiveLeaves = new ArrayList<>();
    for (int i = 0; i < collection.items.size(); i++) {
      SoilValue item = collection.items.get(i);
      if (item instanceof CollectionValue child) {
        String childId = "nested_" + UUID.randomUUID().toString().replace("-", "");
        sb.append("""
            MATCH (parent {name: %s})
            CREATE (child:NestedCollectionValue {name: %s, collectionType: %s, isObjRef: %s})
            CREATE (parent)-[:HasNestedCollectionValue {index: %d}]->(child);
            """.formatted(
            cypherString(parentId),
            cypherString(childId),
            cypherString(child.kind),
            child.hasObjectReference(objectTypes.keySet()),
            i
        ));
        appendNestedCollectionLevel(sb, childId, child);
      } else if (item instanceof ScalarValue scalar && objectTypes.containsKey(scalar.raw)) {
        sb.append("""
            MATCH (parent {name: %s}), (target {use_id: %s})
            MERGE (parent)-[:HasReferenceValue {index: %d}]->(target);
            """.formatted(cypherString(parentId), cypherString(scalar.raw), i));
      } else {
        primitiveLeaves.add(toPrimitiveStorage(item));
      }
    }

    if (!primitiveLeaves.isEmpty()) {
      sb.append("""
          MATCH (parent {name: %s})
          SET parent.value = %s;
          """.formatted(cypherString(parentId), cypherString(String.join(" | ", primitiveLeaves))));
    }
  }

  private String binaryLinkCypher(InsertLink link) {
    String src = cypherString(link.participants.get(0));
    String tgt = cypherString(link.participants.get(1));
    String assoc = cypherString(link.associationName);
    return """
        // insert (%s, %s) into %s
        MATCH (m:ManageModel {name: %s})-[:DefineMetamodels]->(metaN:MetaNode)
        WHERE metaN.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass']
        MATCH (obj1 {use_id: %s})-[:ObjectInstanceOf]->(cls1)-[:InstanceOf]->(metaN)
        MATCH (obj2 {use_id: %s})-[:ObjectInstanceOf]->(cls2)-[:InstanceOf]->(metaN)
        MATCH (metaS)-[r]->(metaT)
        WHERE r.associationName = %s
          AND ((metaS = cls1 AND metaT = cls2) OR (metaS = cls2 AND metaT = cls1))
        WITH obj1, obj2, cls1, cls2, r, metaS, metaT, type(r) AS metaType
        WITH CASE WHEN metaS = cls1 THEN obj1 ELSE obj2 END AS sObj,
             CASE WHEN metaT = cls2 THEN obj2 ELSE obj1 END AS tObj,
             r, metaType
        FOREACH (_ IN CASE WHEN metaType = 'ComposeOf' THEN [1] ELSE [] END |
          MERGE (sObj)-[l:LinkComposeOf {name: r.associationName}]->(tObj)
          SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole)
        FOREACH (_ IN CASE WHEN metaType = 'Aggregates' THEN [1] ELSE [] END |
          MERGE (sObj)-[l:LinkAggregates {name: r.associationName}]->(tObj)
          SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole)
        FOREACH (_ IN CASE WHEN metaType = 'AssociateWith' THEN [1] ELSE [] END |
          MERGE (sObj)-[l:LinkAssociateWith {name: r.associationName}]->(tObj)
          SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole);
        """.formatted(
        link.participants.get(0), link.participants.get(1), link.associationName,
        cypherString(modelName), src, tgt, assoc
    );
  }

  private String ternaryLinkCypher(String associationName, List<String> participants) {
    String hubId = associationName + "_" + Math.abs(participants.hashCode());
    StringBuilder sb = new StringBuilder();
    sb.append("""
        // insert %s into %s
        MERGE (hub:LinkHub:`%s` {use_id: %s, name: %s})
        """.formatted(participants, associationName, associationName, cypherString(hubId), cypherString(associationName)));

    for (int i = 0; i < participants.size(); i++) {
      sb.append("""
          MATCH (obj {use_id: %s})-[:ObjectInstanceOf]->(cls)
          MATCH (hub:LinkHub {use_id: %s})
          OPTIONAL MATCH (cls)-[metaRel]->(assocHub {name: %s})
          WHERE coalesce(metaRel.isTernary, false) = true AND metaRel.index = %d
          MERGE (obj)-[r:LinkAssociateWith]->(hub)
          SET r.role = coalesce(metaRel.sourceClassrole, ''),
              r.isTernary = true,
              r.index = %d;
          """.formatted(cypherString(participants.get(i)), cypherString(hubId), cypherString(associationName), i, i));
    }
    return sb.toString();
  }

  private String linkObjectSpokesCypher(CreateLinkObject linkObject) {
    StringBuilder sb = new StringBuilder();
    sb.append("// link object participants for ").append(linkObject.name).append("\n");
    for (int i = 0; i < linkObject.participants.size(); i++) {
      String participant = linkObject.participants.get(i);
      sb.append("""
          MATCH (lo {use_id: %s})-[:ObjectInstanceOf]->(ac {name: %s})
          MATCH (p {use_id: %s})
          OPTIONAL MATCH (src)-[s:sourceAssoClass]->(ac)
          OPTIONAL MATCH (ac)-[t:targetAssoClass]->(target)
          WITH lo, p, CASE WHEN %d = 0 THEN coalesce(s.role, '') ELSE coalesce(t.role, '') END AS role
          MERGE (lo)-[r:LinkAssociateWith]->(p)
          SET r.role = role, r.isLinkObjectPart = true, r.index = %d;
          """.formatted(
          cypherString(linkObject.name),
          cypherString(linkObject.className),
          cypherString(participant),
          i,
          i
      ));
    }
    return sb.toString();
  }

  private StoredValue toStoredValue(SoilValue value) {
    if (value instanceof CollectionValue collection) {
      if (collection.items.isEmpty()) return new StoredValue("COLLECTION_EMPTY", List.of());
      if (collection.hasNestedCollection()) return new StoredValue("NESTED_COLLECTION", List.of());

      List<String> refs = collection.items.stream()
          .filter(ScalarValue.class::isInstance)
          .map(ScalarValue.class::cast)
          .map(s -> s.raw)
          .filter(objectTypes::containsKey)
          .toList();

      if (!refs.isEmpty() && refs.size() == collection.items.size()) {
        return new StoredValue("COLLECTION_DATA", refs);
      }

      String joined = collection.items.stream().map(this::toPrimitiveStorage).collect(Collectors.joining(" | "));
      return new StoredValue(joined, List.of());
    }

    if (value instanceof ScalarValue scalar) {
      if (objectTypes.containsKey(scalar.raw)) return new StoredValue("Object", List.of(scalar.raw));
      return new StoredValue(toPrimitiveStorage(scalar), List.of());
    }

    return new StoredValue("Undefined", List.of());
  }

  private String toPrimitiveStorage(SoilValue value) {
    if (value instanceof ScalarValue scalar) {
      if (scalar.raw.contains("::")) return "#" + scalar.raw.substring(scalar.raw.indexOf("::") + 2);
      if (scalar.quoted) return "'" + scalar.raw.replace("'", "\\'") + "'";
      return scalar.raw;
    }
    if (value instanceof CollectionValue collection) {
      return collection.items.stream().map(this::toPrimitiveStorage).collect(Collectors.joining(" | "));
    }
    return "Undefined";
  }

  private SoilValue parseValue(String text) {
    int brace = text.indexOf('{');
    if (brace > 0 && text.endsWith("}")) {
      String kind = text.substring(0, brace).trim();
      String body = text.substring(brace + 1, text.length() - 1);
      return new CollectionValue(kind, splitTopLevel(body).stream().map(this::parseValue).toList());
    }

    if (text.startsWith("'") && text.endsWith("'")) {
      return new ScalarValue(text.substring(1, text.length() - 1), true);
    }

    return new ScalarValue(text.trim(), false);
  }

  private List<String> parseIdentifierList(String body) {
    return splitTopLevel(body).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private List<String> splitTopLevel(String body) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean inString = false;
    int start = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\'') inString = !inString;
      if (!inString) {
        if (c == '{') depth++;
        if (c == '}') depth--;
        if (c == ',' && depth == 0) {
          parts.add(body.substring(start, i).trim());
          start = i + 1;
        }
      }
    }
    String tail = body.substring(start).trim();
    if (!tail.isEmpty()) parts.add(tail);
    return parts;
  }

  private String stripComment(String line) {
    int idx = line.indexOf("//");
    return idx >= 0 ? line.substring(0, idx) : line;
  }

  private String cypherString(String raw) {
    return "'" + raw.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  private record CreateObject(String name, String className) {}
  private record CreateLinkObject(String name, String className, List<String> participants) {}
  private record SetAttribute(String objectName, String attributeName, SoilValue value) {}
  private record InsertLink(String associationName, List<String> participants) {}
  private record StoredValue(String value, List<String> referenceIds) {}

  private sealed interface SoilValue permits ScalarValue, CollectionValue {}

  private record ScalarValue(String raw, boolean quoted) implements SoilValue {}

  private record CollectionValue(String kind, List<SoilValue> items) implements SoilValue {
    boolean hasNestedCollection() {
      return items.stream().anyMatch(CollectionValue.class::isInstance);
    }

    boolean hasObjectReference(Set<String> knownObjects) {
      for (SoilValue item : items) {
        if (item instanceof ScalarValue scalar && knownObjects.contains(scalar.raw)) return true;
        if (item instanceof CollectionValue collection && collection.hasObjectReference(knownObjects)) return true;
      }
      return false;
    }
  }
}
