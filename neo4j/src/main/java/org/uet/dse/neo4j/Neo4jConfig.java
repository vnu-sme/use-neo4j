package org.uet.dse.neo4j;

public class Neo4jConfig {
    // Đổi thành public để Dialog truy cập được
    public static final String uri = "bolt://127.0.0.1:7687";
    public static final String user = "neo4j";
    public static final String password = "12345678"; // Mật khẩu mặc định

    public static final String database = "neo4j";
}