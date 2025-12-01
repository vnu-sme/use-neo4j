package com.chikacow.manager;

import org.neo4j.driver.Session;
import org.tzi.use.uml.mm.*;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Chuyển đổi MModel thành Graph.
 * Node: :MetaClass, :MetaAttribute
 * Rel: :INHERITS, :HAS_ATTRIBUTE, :ASSOCIATES_WITH
 */
public class MetamodelSynchronizer {
    private final Session session;
    private final JTextArea logArea;

    public MetamodelSynchronizer(Session session, JTextArea logArea) {
        this.session = session;
        this.logArea = logArea;
    }

    public void sync(MModel model) {
        session.executeWrite(tx -> {
            // 1. Xóa Metamodel cũ (Các node có label bắt đầu bằng Meta...)
            tx.run("MATCH (n) WHERE any(l IN labels(n) WHERE l STARTS WITH 'Meta') DETACH DELETE n");
            
            // 2. Tạo Node cho từng Class
            for (MClass cls : model.classes()) {
                String cypher = "CREATE (c:MetaClass {name: $name, isAbstract: $isAbstract})";
                tx.run(cypher, Map.of("name", cls.name(), "isAbstract", cls.isAbstract()));

                // Tạo Attribute cho Class
                for (MAttribute attr : cls.attributes()) {
                    String attrQuery = 
                        "MATCH (c:MetaClass {name: $clsName}) " +
                        "CREATE (a:MetaAttribute {name: $attrName, type: $type}) " +
                        "CREATE (c)-[:HAS_ATTRIBUTE]->(a)";
                    tx.run(attrQuery, Map.of(
                        "clsName", cls.name(),
                        "attrName", attr.name(),
                        "type", attr.type().toString()
                    ));
                }
            }

            // 3. Tạo Quan hệ Kế thừa (Inheritance)
            for (MClass cls : model.classes()) {
                for (MClass parent : cls.parents()) {
                    String inhQuery = 
                        "MATCH (child:MetaClass {name: $cName}), (parent:MetaClass {name: $pName}) " +
                        "CREATE (child)-[:INHERITS_FROM]->(parent)";
                    tx.run(inhQuery, Map.of("cName", cls.name(), "pName", parent.name()));
                }
            }

            // 4. Tạo Association (Quan hệ giữa các Class)
            for (MAssociation assoc : model.associations()) {
                // Xử lý Binary Association cơ bản
                if (assoc.associationEnds().size() == 2) {
                    MAssociationEnd end1 = assoc.associationEnds().get(0);
                    MAssociationEnd end2 = assoc.associationEnds().get(1);

                    String assocQuery = 
                        "MATCH (c1:MetaClass {name: $cls1}), (c2:MetaClass {name: $cls2}) " +
                        "CREATE (c1)-[:META_ASSOCIATION {" +
                        "   name: $assocName, " +
                        "   role1: $role1, mult1: $mult1, kind1: $kind1, " +
                        "   role2: $role2, mult2: $mult2, kind2: $kind2 " +
                        "}]->(c2)";
                    
                    Map<String, Object> params = new HashMap<>();
                    params.put("cls1", end1.cls().name());
                    params.put("cls2", end2.cls().name());
                    params.put("assocName", assoc.name());
                    
                    params.put("role1", end1.nameAsRolename());
                    params.put("mult1", end1.multiplicity().toString());
                    params.put("kind1", end1.aggregationKind() == MAggregationKind.COMPOSITION ? "composition" : "none");
                    
                    params.put("role2", end2.nameAsRolename());
                    params.put("mult2", end2.multiplicity().toString());
                    params.put("kind2", end2.aggregationKind() == MAggregationKind.COMPOSITION ? "composition" : "none");

                    tx.run(assocQuery, params);
                }
            }
            return null;
        });
    }
}