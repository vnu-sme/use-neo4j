package org.uet.dse.neo4j.model_trafo.gui;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.*;

public class Neo4jCorrObjectRepository {
    public void createCorrObject(String clsName, String objName, String srcModel, String trgModel) {
        String corrModelName = srcModel + "2" + trgModel + "_Corr";

        String query = String.format(
                "MATCH (m:ManageModel {name: $corrModel})-[:DefineMetamodels]->(meta:MetaNode {name: 'NodeConcreteClass'})\n" +
                "MATCH (cls:NodeConcreteClass {name: $clsName})-[:InstanceOf]->(meta)\n" +
                "MERGE (obj:Object {use_id: $objName})\n" +
                "SET obj:`%s`, obj.name = $objName, obj.isCorrespondence = true\n" +
                "MERGE (obj)-[:ObjectInstanceOf]->(cls)",
            clsName, clsName
        );

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("corrModel", corrModelName, "clsName", clsName, "objName", objName));
        }
    }

    /**
     * Logic: get type and direction of link by searching its meta-link (assoc on models)
     */
    public void createCorrLink(String sId, String tId, String assocName) {
        String query =
            "//find the objects and their class\n" +
                "MATCH (obj1 {use_id: $sId})-[:ObjectInstanceOf]->(cls1)\n" +
                "MATCH (obj2 {use_id: $tId})-[:ObjectInstanceOf]->(cls2)\n" +
                "// find undirected corr assoc\n" +
                "MATCH (metaS)-[r:AssociateWith]->(metaT) \n" +
                "WHERE r.associationName = $name \n" +
                "  AND ((metaS = cls1 AND metaT = cls2) OR (metaS = cls2 AND metaT = cls1))\n" +
                "WITH obj1, obj2, cls1, cls2, r, metaS, metaT\n" +
                "// decide start-end direction\n" +
                "WITH CASE WHEN metaS = cls1 THEN obj1 ELSE obj2 END AS startNode,\n" +
                "     CASE WHEN metaT = cls2 THEN obj2 ELSE obj1 END AS endNode,\n" +
                "     r, metaS, metaT\n" +
                "// create link\n" +
                "MERGE (startNode)-[l:LinkAssociateWith {name: $name}]->(endNode)\n" +
                "SET l.isTernary = false, \n" +
                "    l.isCorrespondenceLink = true, \n" +
                "    l.sourceRole = r.sourceClassrole, \n" +
                "    l.targetRole = r.targerClassrole, \n" + // Theo lỗi chính tả targer trong DB
                "    l.sourceClassName = metaS.name, \n" +
                "    l.targetClassName = metaT.name";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("sId", sId, "tId", tId, "name", assocName));
        }
    }

    public List<Map<String, Object>> listAllLinks() {
        String query = "MATCH (a)-[r:LinkAssociateWith {isCorrespondenceLink: true}]->(b) " +
            "RETURN r.name AS name, type(r) AS type, a.use_id AS src, b.use_id AS tgt";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }

    public void deleteLink(String name, String src, String tgt) {
        String query = "MATCH (a {use_id: $src})-[r {name: $name}]->(b {use_id: $tgt}) DELETE r";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("name", name, "src", src, "tgt", tgt));
        }
    }

    //delete object will also delete its associated link
    public void deleteObject(String id) {
        String query = "MATCH (o {use_id: $id, isCorrespondence: true}) DETACH DELETE o";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("id", id));
        }
    }

    public List<Map<String, Object>> listAllCorrObjects() {
        String query = "MATCH (o {isCorrespondence: true})-[:ObjectInstanceOf]->(cls) " +
            "RETURN o.use_id AS name, cls.name AS type";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }
}