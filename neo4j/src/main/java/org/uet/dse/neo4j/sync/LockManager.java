package org.uet.dse.neo4j.sync;

import org.neo4j.driver.*;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;

public class LockManager {
    public static class LockResult {
        public boolean success;
        public String owner;
        public LockResult(boolean s, String o) { this.success = s; this.owner = o; }
    }
    public static LockResult acquireLock(String sessionKey) {
        String dbName = Neo4jDriverManager.getInstance().getActiveDatabase();
        try (Session session = Neo4jDriverManager.getInstance().getDriver().session(SessionConfig.forDatabase(dbName))) {
            // Cypher: Thử lấy lock, nếu thất bại thì trả về owner hiện tại
            String cypher =
                    "MERGE (l:DbLock {id: 'GLOBAL_LOCK'}) " +
                            "ON CREATE SET l.owner = $key, l.time = timestamp() " +
                            "ON MATCH SET l.owner = CASE WHEN timestamp() - l.time > 300000 THEN $key ELSE l.owner END, " +
                            "             l.time = CASE WHEN l.owner = $key THEN timestamp() ELSE l.time END " +
                            "RETURN l.owner = $key AS success, l.owner AS currentOwner";

            Result res = session.run(cypher, Values.parameters("key", sessionKey));
            org.neo4j.driver.Record record = res.single();
            return new LockManager.LockResult(record.get("success").asBoolean(), record.get("currentOwner").asString());
        }
    }

    // Trong lớp LockManager
    public static LockResult acquireLockWithRetry(String sessionKey, int maxRetries, int sleepMillis) {
        int attempts = 0;
        while (attempts < maxRetries) {
            LockResult result = acquireLock(sessionKey);
            if (result.success) {
                return result;
            }

            // Nếu không lấy được, đợi một chút rồi thử lại
            try {
                attempts++;
                // Tăng dần thời gian chờ để giảm tải cho DB (Exponential Backoff)
                Thread.sleep(sleepMillis * attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new LockResult(false, "Timeout after " + maxRetries + " attempts");
    }

    public static void releaseLock() {
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run("MATCH (l:DbLock {id: 'GLOBAL_LOCK'}) DELETE l");
        }
    }
}