package org.uet.dse.neo4j.ocl;

import org.neo4j.driver.*;

import java.util.*;

public class Neo4jEvalContext {
    private final Driver driver;
    private final Map<String, Object> variables = new HashMap<>(); // Lưu biến: p -> "node-id-123"

    public Neo4jEvalContext(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    public void setVariable(String name, Object value) { variables.put(name, value); }
    public Object getVariable(String name) { return variables.get(name); }

    // Thực thi Cypher và lấy 1 giá trị duy nhất (Property)
    public Object executeScalar(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, params);
                if (result.hasNext()) {
                    Value value = result.next().get(0);
                    return value.asObject();
                }
                return null;
            });
        }
    }

    // Thực thi Cypher và lấy danh sách Element ID (Nodes/Objects)
    public List<Object> executeList(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, params);
                List<Object> list = new ArrayList<>();
                while (result.hasNext()) {
                    list.add(result.next().get(0).asObject());
                }
                return list;
            });
        }
    }

    public void close() { driver.close(); }
}