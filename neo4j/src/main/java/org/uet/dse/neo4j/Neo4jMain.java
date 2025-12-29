package org.uet.dse.neo4j;

import org.uet.dse.neo4j.mm.Edge;
import org.uet.dse.neo4j.mm.Tag;
import org.uet.dse.neo4j.mm.Vertex;

public class Neo4jMain {
    public static void main(String[] args) {
        try (Neo4jPluginSession session = new Neo4jPluginSession()) {

            System.out.println("Using database: " + session.getDatabaseName());

            // Tạo vertex + tag
            Vertex v1 = new Vertex("v1", "Alice");
            Vertex v2 = new Vertex("v2", "Bob");
            Tag personTag = new Tag("Person");

            session.getVertexRepository().createVertex(v1, personTag);
            session.getVertexRepository().createVertex(v2, personTag);

            // Tạo edge
            Edge e1 = new Edge("KNOWS", "v1", "v2");
            session.getEdgeRepository().createEdge(e1);

            // Xem dữ liệu
            System.out.println("Vertices:");
            session.getVertexRepository().findAllVertices().forEach(System.out::println);

            System.out.println("Edges:");
            session.getEdgeRepository().findAllEdges().forEach(System.out::println);

        }
        // DB tự động drop khi kết thúc try-with-resources
    }
}

