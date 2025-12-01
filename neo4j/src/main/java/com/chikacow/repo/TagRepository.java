package com.chikacow.repo;

import org.neo4j.driver.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TagRepository {
    private final Driver driver;
    private final String database;

    public TagRepository(Driver driver, String database) {
        this.driver = driver;
        this.database = database;
    }

    /**
     * Tạo "tag" (label) mới trong Neo4j
     * Cách làm: tạo một node đặc biệt để Neo4j nhận label tồn tại
     */
    public void createTag(String tagName) {
        String cypher = "MERGE (t:" + tagName + " {__tag__: $tagName})";
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.executeWrite(tx -> {
                tx.run(cypher, Map.of("tagName", tagName));
                return null;
            });
        }
    }

    /**
     * Lấy danh sách tất cả tag hiện có
     * Neo4j không có hệ thống quản lý label trực tiếp,
     * nên chúng ta lưu nhãn vào node __tag__ để tra cứu
     */
    public List<String> findAllTags() {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                List<String> tags = new ArrayList<>();
                Result res = tx.run("MATCH (t) WHERE exists(t.__tag__) RETURN labels(t) AS labels");
                while (res.hasNext()) {
                    List<Object> labels = res.next().get("labels").asList();
                    for (Object label : labels) {
                        String lbl = label.toString();
                        if (!lbl.equals("Vertex") && !tags.contains(lbl)) {
                            tags.add(lbl);
                        }
                    }
                }
                return tags;
            });
        }
    }
}
