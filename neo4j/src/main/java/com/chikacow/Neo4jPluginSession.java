package com.chikacow;

import com.chikacow.manager.DynamicDatabaseManager;
import com.chikacow.manager.Neo4jDriverManager;
import com.chikacow.repo.EdgeRepository;
import com.chikacow.repo.VertexRepository;

public class Neo4jPluginSession implements AutoCloseable {

    private final Neo4jDriverManager driverManager;
    private final DynamicDatabaseManager dbManager;
    private final String dbName;

    private final VertexRepository vertexRepo;
    private final EdgeRepository edgeRepo;

    public Neo4jPluginSession() {
        driverManager = Neo4jDriverManager.getInstance(Neo4jConfig.uri, Neo4jConfig.user, Neo4jConfig.password);
        dbManager = new DynamicDatabaseManager(driverManager.getDriver());
        dbName = dbManager.createRandomDatabase();

        vertexRepo = new VertexRepository(driverManager.getDriver(), dbName);
        edgeRepo = new EdgeRepository(driverManager.getDriver(), dbName);
    }


    public VertexRepository getVertexRepository() { return vertexRepo; }
    public EdgeRepository getEdgeRepository() { return edgeRepo; }
    public String getDatabaseName() { return dbName; }

    @Override
    public void close() {
        //dbManager.dropDatabase(dbName);
        driverManager.close();
    }
}
