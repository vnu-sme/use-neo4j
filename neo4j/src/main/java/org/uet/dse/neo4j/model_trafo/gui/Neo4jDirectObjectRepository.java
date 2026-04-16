package org.uet.dse.neo4j.model_trafo.gui;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.*;

public class Neo4jDirectObjectRepository {

    public void createRegularObject(String modelName, String className, String useId) {
        String query = String.format(
            "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(meta:MetaNode {name: 'NodeConcreteClass'})\n" +
            "MATCH (cls:`%s` {name: $clsName})-[:InstanceOf]->(meta)\n" +
            "CREATE (obj:`%s` {use_id: $useId, name: $useId, isCorrespondence: false})\n" +
            "MERGE (obj)-[:ObjectInstanceOf]->(cls)", 
            className, className
        );

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("modelName", modelName, "clsName", className, "useId", useId));
        }
    }


    public void createRegularLink(String srcOid, String tgtOid, String assocName) {
        String query =
            "MATCH (obj1 {use_id: $srcOid})-[:ObjectInstanceOf]->(cls1)\n" +
                "MATCH (obj2 {use_id: $tgtOid})-[:ObjectInstanceOf]->(cls2)\n" +
                "// find Association in Metamodel\n" +
                "MATCH (metaS)-[r]->(metaT) \n" +
                "WHERE r.associationName = $assocName \n" +
                "  AND ((metaS = cls1 AND metaT = cls2) OR (metaS = cls2 AND metaT = cls1))\n" +
                "WITH obj1, obj2, cls1, cls2, r, metaS, metaT, type(r) AS metaType\n" +
                "WITH CASE WHEN metaS = cls1 THEN obj1 ELSE obj2 END AS sObj,\n" +
                "     CASE WHEN metaT = cls2 THEN obj2 ELSE obj1 END AS tObj,\n" +
                "     r, metaType\n" +
                "FOREACH (ignore IN CASE WHEN metaType = 'ComposeOf' THEN [1] ELSE [] END |\n" +
                "  MERGE (sObj)-[l:LinkComposeOf {name: r.associationName}]->(tObj)\n" +
                "  SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole)\n" +
                "FOREACH (ignore IN CASE WHEN metaType = 'Aggregates' THEN [1] ELSE [] END |\n" +
                "  MERGE (sObj)-[l:LinkAggregates {name: r.associationName}]->(tObj)\n" +
                "  SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole)\n" +
                "FOREACH (ignore IN CASE WHEN metaType = 'AssociateWith' THEN [1] ELSE [] END |\n" +
                "  MERGE (sObj)-[l:LinkAssociateWith {name: r.associationName}]->(tObj)\n" +
                "  SET l.isTernary = false, l.sourceRole = r.sourceClassrole, l.targetRole = r.targerClassrole)";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("srcOid", srcOid, "tgtOid", tgtOid, "assocName", assocName));
        }
    }

    public List<Map<String, Object>> listRegularObjects() {
        String query = "MATCH (o {isCorrespondence: false})-[:ObjectInstanceOf]->(cls)\n" +
            "RETURN o.use_id AS name, cls.name AS type";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }


    public List<Map<String, Object>> listRegularLinks() {
        String query = "MATCH (s)-[r]->(t)\n" +
            "WHERE type(r) STARTS WITH 'Link'\n" +
            "RETURN r.name AS name, type(r) AS type, s.use_id AS src, t.use_id AS tgt";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }

    public void deleteRegularObject(String useId) {
        String query = "MATCH (o {use_id: $useId, isCorrespondence: false}) DETACH DELETE o";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("useId", useId));
        }
    }

}