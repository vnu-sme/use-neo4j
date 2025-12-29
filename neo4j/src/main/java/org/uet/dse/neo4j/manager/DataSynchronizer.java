package org.uet.dse.neo4j.manager;

import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionContext; // Thay cho Transaction hoặc thêm vào
import org.tzi.use.uml.mm.*;
import org.tzi.use.uml.ocl.value.*;
import org.tzi.use.uml.sys.*;

import javax.swing.*;
import java.util.*;

public class DataSynchronizer {
    private final Session session;
    private final JTextArea logArea;
    private MSystemState state;
    private MModel model;

    public DataSynchronizer(Session session, JTextArea logArea) {
        this.session = session;
        this.logArea = logArea;
    }

    public void sync(MSystemState state) {
        this.state = state;
        this.model = state.system().model();

        // Bước 1: Schema (Constraints) - Rất quan trọng để đảm bảo tính duy nhất
        createSchemaConstraints();

        // Bước 2: Xóa dữ liệu cũ (Clean insert)
        clearOldData();

        // Bước 3: Tạo Nodes (Objects)
        createNodes();

        // Bước 4: Tạo Links (Relationships)
        createLinks();
    }

    private void createSchemaConstraints() {
        session.executeWrite(tx -> {
            for (MClass cls : model.classes()) {
                // Tạo constraint cho tất cả class (kể cả abstract như HRPerson)
                String label = sanitize(cls.name());
                String query = String.format(
                    "CREATE CONSTRAINT constraint_%s_id IF NOT EXISTS FOR (n:`%s`) REQUIRE n.use_id IS UNIQUE", 
                    label, label
                );
                try { tx.run(query); } catch (Exception ignored) {}
            }
            return null;
        });
    }

    private void clearOldData() {
        session.executeWrite(tx -> {
            tx.run("MATCH (n) WHERE n.use_id IS NOT NULL DETACH DELETE n");
            return null;
        });
    }

    private void createNodes() {
        session.executeWrite(tx -> {
            for (MObject obj : state.allObjects()) {
                // 1. Build Labels (Inheritance)
                // Ví dụ: HRManager -> Label: :HRManager:HRPerson
                StringBuilder labels = new StringBuilder();
                MClass cls = obj.cls();
                labels.append(":").append(sanitize(cls.name()));
                for (MClass parent : cls.allParents()) {
                    labels.append(":").append(sanitize(parent.name()));
                }
                
                // Đánh dấu Association Class
                if (cls instanceof MAssociationClass) {
                    labels.append(":AssociationClass");
                }

                // 2. Build Attributes
                Map<String, Object> props = new HashMap<>();
                props.put("id", obj.name());
                
                StringBuilder propsCypher = new StringBuilder("{use_id: $id");
                
                for (Map.Entry<MAttribute, Value> entry : obj.state(state).attributeValueMap().entrySet()) {
                    Value val = entry.getValue();
                    if (!val.isUndefined()) {
                        String key = entry.getKey().name();
                        propsCypher.append(", `").append(key).append("`: $").append(key);
                        props.put(key, convertValue(val));
                    }
                }
                propsCypher.append("}");

                // 3. Execute
                String query = "CREATE (n" + labels + " " + propsCypher + ")";
                tx.run(query, props);
            }
            return null;
        });
    }

    private void createLinks() {
        session.executeWrite(tx -> { // tx ở đây là TransactionContext
            for (MLink link : state.allLinks()) {
                MAssociation assoc = link.association();

                if (link instanceof MLinkObject) {
                    processLinkObject(tx, (MLinkObject) link);
                } else {
                    processStandardLink(tx, link);
                }
            }
            return null;
        });
    }

    private void processStandardLink(TransactionContext tx, MLink link) {
        MLinkEnd end1 = link.getLinkEnd(0);
        MLinkEnd end2 = link.getLinkEnd(1);
        MAssociationEnd def1 = end1.associationEnd();
        MAssociationEnd def2 = end2.associationEnd();

        String id1 = end1.object().name();
        String id2 = end2.object().name();
        String relName = link.association().name();

        // XỬ LÝ COMPOSITION: Mũi tên đi từ Whole -> Part
        // Trong file use của bạn: Department [*] role departments (Part) - EmployeeRegister (Whole)
        boolean swap = false;
        String type = "association";

        if (def1.aggregationKind() == MAggregationKind.COMPOSITION) {
            type = "composition"; // Def1 là Whole -> Giữ nguyên 1->2
        } else if (def2.aggregationKind() == MAggregationKind.COMPOSITION) {
            type = "composition";
            swap = true; // Def2 là Whole -> Đảo chiều 2->1
        }

        if (swap) {
            String tmp = id1; id1 = id2; id2 = tmp;
            MAssociationEnd tmpDef = def1; def1 = def2; def2 = tmpDef;
        }

        // Tạo Query
        String query = String.format(
            "MATCH (a {use_id: $id1}), (b {use_id: $id2}) " +
            "CREATE (a)-[:`%s` {type: $type, role: $role}]->(b)", 
            relName
        );
        
        tx.run(query, Map.of(
            "id1", id1, 
            "id2", id2, 
            "type", type,
            "role", def2.nameAsRolename() // Role của đích đến
        ));
    }

    private void processLinkObject(TransactionContext tx, MLinkObject linkObj) {
        String centerId = linkObj.name();

        for (int i = 0; i < linkObj.association().associationEnds().size(); i++) {
            MLinkEnd end = linkObj.getLinkEnd(i);
            String partId = end.object().name();
            String roleName = end.associationEnd().nameAsRolename();

            String query = String.format(
                    "MATCH (center {use_id: $cid}), (part {use_id: $pid}) " +
                            "CREATE (center)-[:`%s`]->(part)",
                    roleName
            );
            tx.run(query, Map.of("cid", centerId, "pid", partId));
        }
    }

    // --- Helpers ---
    private Object convertValue(Value val) {
        if (val instanceof org.tzi.use.uml.ocl.value.StringValue) return ((org.tzi.use.uml.ocl.value.StringValue) val).value();
        if (val instanceof org.tzi.use.uml.ocl.value.IntegerValue) return ((org.tzi.use.uml.ocl.value.IntegerValue) val).value();
        if (val instanceof org.tzi.use.uml.ocl.value.RealValue) return ((org.tzi.use.uml.ocl.value.RealValue) val).value();
        return val.toString();
    }

    private String sanitize(String s) { return s.replace("`", ""); }
}