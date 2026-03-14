package org.uet.dse.neo4j.tgg;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.*;

public class TggRepository {
    private final Driver driver;

    public TggRepository() {
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    /**
     * Lấy danh sách tất cả các Model hiện có trên Database
     */
    public List<Map<String, Object>> getAllRegisteredModels() {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = driver.session(SessionConfig.forDatabase(dbName))) {
            String cypher = "MATCH (m:ManageModel) RETURN m.modelName as name, m.createdAt as date ORDER BY m.modelName";
            return session.run(cypher).list(r -> r.asMap());
        }
    }

    /**
     * Tạo hoặc cập nhật Node quản lý Model (M3)
     */
    public void upsertManageModel(TransactionContext tx, String modelName) {
        String cypher = "MERGE (m:ManageModel {modelName: $name}) " +
                        "ON CREATE SET m.createdAt = timestamp() " +
                        "SET m.lastUpdated = timestamp()";
        tx.run(cypher, Map.of("name", modelName));
    }

    /**
     * Nối thực thể (Class/Enum) vào Model tương ứng
     */
    public void linkEntityToModel(TransactionContext tx, String modelName, String entityId) {
        String cypher = "MATCH (m:ManageModel {modelName: $mName}), (e {name: $eId}) " +
                        "MERGE (m)-[:CONTAINS_ENTITY]->(e)";
        tx.run(cypher, Map.of("mName", modelName, "eId", entityId));
    }
}