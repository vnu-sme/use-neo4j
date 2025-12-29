package org.uet.dse.neo4j.manager;

import org.neo4j.driver.*;

import java.util.concurrent.TimeUnit;

public class Neo4jDriverManager implements AutoCloseable {
    private static Neo4jDriverManager instance;
    private final Driver driver;
    private String activeDatabase;

    private boolean deleteOnExit = false; // Cờ đánh dấu có xóa DB khi tắt không


    // Constructor giữ nguyên
    private Neo4jDriverManager(String uri, String user, String password) {
        // Cấu hình driver để không bị treo nếu mạng lag
        Config config = Config.builder()
                .withConnectionTimeout(30, TimeUnit.SECONDS)
                .build();
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
    }

    private Neo4jDriverManager(String uri, String user, String password, String database) {
        Config config = Config.builder()
                .withConnectionTimeout(30, TimeUnit.SECONDS)
                .build();
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
        this.activeDatabase = (database == null || database.isEmpty()) ? "neo4j" : database;
    }

    private Neo4jDriverManager(String uri, String user, String password, String database, boolean deleteOnExit) {
        Config config = Config.builder()
                .withConnectionTimeout(30, TimeUnit.SECONDS)
                .build();
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), config);
        this.activeDatabase = (database == null || database.isEmpty()) ? "neo4j" : database;
        this.deleteOnExit = deleteOnExit;
    }

    // Giữ nguyên getInstance cũ (cho code cũ dùng)
    public static Neo4jDriverManager getInstance(String uri, String user, String password) {
        if (instance == null) {
            synchronized (Neo4jDriverManager.class) {
                if (instance == null) {
                    instance = new Neo4jDriverManager(uri, user, password);
                }
            }
        }
        return instance;
    }

    // --- THÊM MỚI: Hàm này dùng cho GUI để ép buộc kết nối lại ---
    public static void connect(String uri, String user, String password, String database, boolean createNew, boolean deleteOnExit) {
        // 1. Đóng kết nối cũ
        if (instance != null) {
            instance.close();
            instance = null;
        }

        // 2. Nếu yêu cầu tạo mới, phải kết nối vào 'system' để tạo DB trước
        if (createNew) {
            // Tạo driver tạm thời để kết nối vào system
            try (Driver tempDriver = GraphDatabase.driver(uri, AuthTokens.basic(user, password))) {
                try (Session sysSession = tempDriver.session(SessionConfig.forDatabase("system"))) {
                    sysSession.executeWrite(tx -> {
                        // Tạo database mới (IF NOT EXISTS để tránh lỗi nếu đã có)
                        tx.run("CREATE DATABASE `" + database + "` IF NOT EXISTS");
                        return null;
                    });
                }
            } catch (Exception e) {
                throw new RuntimeException("Không thể tạo database mới. Hãy chắc chắn bạn dùng user admin (neo4j). Lỗi: " + e.getMessage());
            }
        }

        // 3. Khởi tạo Manager chính thức kết nối vào database đích
        instance = new Neo4jDriverManager(uri, user, password, database, deleteOnExit);
        instance.driver.verifyConnectivity();
    }

    public Session openSession() {
        return driver.session(SessionConfig.forDatabase(activeDatabase));
    }
    public String getActiveDatabase() {
        return activeDatabase;
    }

    public static Neo4jDriverManager getInstance() {
        return instance;
    }
    public boolean isConnected() {
        // 1. Nếu driver chưa được khởi tạo -> Chắc chắn chưa kết nối
        if (driver == null) {
            return false;
        }

        // 2. Thử ping tới server
        try {
            driver.verifyConnectivity();
            return true;
        } catch (Exception e) {
            // Nếu có lỗi (timeout, rớt mạng...) -> Trả về false
            return false;
        }
    }

    public Driver getDriver() {
        return driver;
    }

    @Override
    public void close() {
        if (driver != null) {
            // Logic xóa Database trước khi đóng driver
            if (deleteOnExit && activeDatabase != null && !activeDatabase.equals("neo4j") && !activeDatabase.equals("system")) {
                try (Session sysSession = driver.session(SessionConfig.forDatabase("system"))) {
                    sysSession.executeWrite(tx -> {
                        // STOP database trước khi DROP
                        tx.run("STOP DATABASE `" + activeDatabase + "`");
                        tx.run("DROP DATABASE `" + activeDatabase + "` IF EXISTS");
                        return null;
                    });
                    System.out.println("Neo4j Manager: Đã xóa database [" + activeDatabase + "] theo yêu cầu.");
                } catch (Exception e) {
                    System.err.println("Lỗi khi xóa database: " + e.getMessage());
                }
            }
            driver.close();
        }
    }
}