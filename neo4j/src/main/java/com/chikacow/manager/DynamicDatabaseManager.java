package com.chikacow.manager;

import org.neo4j.driver.*;

import java.time.Instant;

public class DynamicDatabaseManager {
    private final Driver driver;

    public DynamicDatabaseManager(Driver driver) {
        this.driver = driver;
    }

    public String createRandomDatabase() {
        String dbName = "db" + Instant.now().getEpochSecond();
        try (Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.executeWrite(tx -> {
                tx.run("CREATE DATABASE `" + dbName + "` IF NOT EXISTS");
                return null;
            });
        }
        return dbName;
    }

    public void dropDatabase(String dbName) {
        try (Session session = driver.session(SessionConfig.forDatabase("system"))) {
            session.executeWrite(tx -> {
                tx.run("DROP DATABASE `" + dbName + "` IF EXISTS");
                return null;
            });
        }
    }
}
