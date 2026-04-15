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
                "MERGE (obj)-[:ObjectInstanceOf]->(cls)",
            clsName, clsName
        );

        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run(query, Values.parameters(
                "corrModel", corrModelName,
                "clsName", clsName,
                "objName", objName
            ));
        }
    }

    public List<Map<String, Object>> listAllObjects() {
        String query = "MATCH (o {isCorrespondence: true})-[:ObjectInstanceOf]->(cls) " +
            "RETURN o.use_id AS name, cls.name AS type";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }


    public void createCorrLink(String srcId, String tgtId, String assocName, String srcCls, String tgtCls) {
        // Nối 2 node bất kỳ bằng cạnh AssociateWith
        String query =
            "MATCH (src {use_id: $srcId}), (tgt {use_id: $tgtId})\n" +
                "MERGE (src)-[r:AssociateWith {associationName: $assocName}]->(tgt)\n" +
                "SET r.sourceClassName = $srcCls, r.targetClassName = $tgtCls, \n" +
                "    r.isTernary = false, r.isCorrespondenceLink = true";

        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            s.run(query, Values.parameters(
                "srcId", srcId, "tgtId", tgtId, "assocName", assocName,
                "srcCls", srcCls, "tgtCls", tgtCls));
        }
    }

    public List<Map<String, Object>> listAllLinks() {
        String query = "MATCH (a)-[r:AssociateWith {isCorrespondenceLink: true}]->(b)\n" +
            "RETURN r.associationName AS name, a.use_id AS src, b.use_id AS tgt";
        try (Session s = Neo4jDriverManager.getInstance().openSession()) {
            return s.run(query).list(r -> r.asMap());
        }
    }

    public void deleteObject(String name) {
        Neo4jDriverManager.getInstance().openSession().run("MATCH (o {use_id: $n, isCorrespondence: true}) DETACH DELETE o", Map.of("n", name));
    }

    public void deleteLink(String assocName, String srcId, String tgtId) {
        Neo4jDriverManager.getInstance().openSession().run("MATCH ({use_id: $s})-[r:AssociateWith {associationName: $n}]->({use_id: $t}) DELETE r",
            Map.of("n", assocName, "s", srcId, "t", tgtId));
    }
}