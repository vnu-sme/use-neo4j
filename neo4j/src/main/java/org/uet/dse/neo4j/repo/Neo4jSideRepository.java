package org.uet.dse.neo4j.repo;

import org.neo4j.driver.Driver;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;

public class Neo4jSideRepository {
    private final Driver driver;

    public Neo4jSideRepository() {
        // Lấy driver từ Manager đã connect thành công
        this.driver = Neo4jDriverManager.getInstance().getDriver();
    }

    /**
     * Ghi nhật ký thao tác người dùng xuống Neo4j
     */
    /**
     * Ghi nhật ký thao tác người dùng trực tiếp vào Transaction hiện hành
     */
    public void logAction(org.neo4j.driver.TransactionContext tx, String actionType, String category, String target, String details) {
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        String sessionKey = Neo4jDriverManager.getInstance().getSessionManager().getSessionKey();

        String cypher =
                "MATCH (meta:MetaNode {name: 'NodeActionLog'}) " +
                        "CREATE (l:ActionLog { " +
                        "  user: $user, " +
                        "  timestamp: timestamp(), " +
                        "  sessionKey: $key, " +
                        "  actionType: $type, " +
                        "  category: $cat, " +
                        "  target: $target, " +
                        "  details: $details " +
                        "}) " +
                        "MERGE (l)-[:InstanceOf]->(meta)";

        tx.run(cypher, org.neo4j.driver.Values.parameters(
                "user", user, "key", sessionKey, "type", actionType,
                "cat", category, "target", target, "details", details
        ));
    }
}
