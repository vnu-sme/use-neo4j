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
            Value params = Values.parameters("modelName", modelName, "clsName", className, "useId", useId);
            s.run(query, params);
        }
    }


    public void createRegularLink(String srcOid, String tgtOid, String assocName, String modelName) {
        String query =
            "MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->(metaN:MetaNode)\n" +
                "WHERE metaN.name IN ['NodeConcreteClass', 'NodeAbstractClass', 'NodeAssociationClass']\n" +

                "MATCH (obj1 {use_id: $srcOid})-[:ObjectInstanceOf]->(cls1)-[:InstanceOf]->(metaN)\n" +
                "MATCH (obj2 {use_id: $tgtOid})-[:ObjectInstanceOf]->(cls2)-[:InstanceOf]->(metaN)\n" +

                "MATCH (metaS)-[r]->(metaT)\n" +
                "WHERE r.associationName = $assocName\n" +
                "  AND type(r) IN ['AssociateWith', 'ComposeOf', 'Aggregates']\n" +
                "  AND (\n" +
                "    (\n" +
                "      (cls1 = metaS OR (cls1)-[:Extends*1..]->(metaS))\n" +
                "      AND\n" +
                "      (cls2 = metaT OR (cls2)-[:Extends*1..]->(metaT))\n" +
                "    )\n" +
                "    OR\n" +
                "    (\n" +
                "      (cls2 = metaS OR (cls2)-[:Extends*1..]->(metaS))\n" +
                "      AND\n" +
                "      (cls1 = metaT OR (cls1)-[:Extends*1..]->(metaT))\n" +
                "    )\n" +
                "  )\n" +

                "WITH obj1, obj2, cls1, cls2, r, metaS, metaT, type(r) AS metaType\n" +
                "WITH CASE WHEN cls1 = metaS OR (cls1)-[:Extends*1..]->(metaS) THEN obj1 ELSE obj2 END AS sObj,\n" +
                "     CASE WHEN cls2 = metaT OR (cls2)-[:Extends*1..]->(metaT) THEN obj2 ELSE obj1 END AS tObj,\n" +
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
            Value params = Values.parameters(
                "srcOid", srcOid,
                "tgtOid", tgtOid,
                "assocName", assocName,
                "modelName", modelName
            );
            s.run(query, params);
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
            "  AND coalesce(r.isCorrespondenceLink, false) = false\n" +
            "  AND s.isCorrespondence = false\n" +
            "  AND t.isCorrespondence = false\n" +
            "RETURN r.name AS name, type(r) AS type, s.use_id AS src, t.use_id AS tgt";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }

    public void deleteRegularObject(String useId) {
        String query = "MATCH (o {use_id: $useId, isCorrespondence: false}) DETACH DELETE o";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            Value params = Values.parameters("useId", useId);
            s.run(query, params);
        }
    }

}
