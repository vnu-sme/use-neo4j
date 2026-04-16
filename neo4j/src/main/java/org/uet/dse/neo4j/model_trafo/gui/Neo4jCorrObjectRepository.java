package org.uet.dse.neo4j.model_trafo.gui;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.*;

public class Neo4jCorrObjectRepository {
    public void createCorrObject(String clsName, String objName, String srcModel, String trgModel) {
        String corrModelName = srcModel + "2" + trgModel + "_Corr";
        String query = String.format(
            "MATCH (m:ManageModel {name: $corrModel})-[:DefineMetamodels]->(meta:MetaNode {name: 'NodeConcreteClass'})\n" +
                "MATCH (cls:`%s` {name: $clsName})-[:InstanceOf]->(meta)\n" +
                "MERGE (obj:`%s` {use_id: $objName})\n" +
                "SET obj.isCorrespondence = true, obj.name = $objName\n" +
                "MERGE (obj)-[:ObjectInstanceOf]->(cls)", clsName, clsName);

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("corrModel", corrModelName, "clsName", clsName, "objName", objName));
        }
    }

    public void createCorrLink(String sId, String tId, String assocName, String sCls, String tCls) {
        String query =
            "MATCH (src {use_id: $sId}), (tgt {use_id: $tId})\n" +
                "MERGE (src)-[r:LinkAssociateWith {associationName: $name}]->(tgt)\n" +
                "SET r.sourceClassName = $sCls, r.targetClassName = $tCls, \n" +
                "    r.isTernary = false, r.isCorrespondenceLink = true";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("sId", sId, "tId", tId, "name", assocName, "sCls", sCls, "tCls", tCls));
        }
    }

    public List<Map<String, Object>> listAllLinks() {
        String query = "MATCH (a)-[r:LinkAssociateWith {isCorrespondenceLink: true}]->(b) " +
            "RETURN r.associationName AS name, a.use_id AS src, b.use_id AS tgt";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }

    public void deleteLink(String name, String src, String tgt) {
        String query = "MATCH (a {use_id: $src})-[r:LinkAssociateWith {associationName: $name}]->(b {use_id: $tgt}) DELETE r";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters("name", name, "src", src, "tgt", tgt));
        }
    }

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