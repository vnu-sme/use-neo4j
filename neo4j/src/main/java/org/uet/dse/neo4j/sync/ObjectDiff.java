package org.uet.dse.neo4j.sync;

import org.uet.dse.neo4j.model.FullObjectSnapshot;
import org.uet.dse.neo4j.model.ObjectState;
import java.util.*;

public class ObjectDiff {
    public List<String> getJavaOnlyObjects() {
        return javaOnlyObjects;
    }

    public void setJavaOnlyObjects(List<String> javaOnlyObjects) {
        this.javaOnlyObjects = javaOnlyObjects;
    }

    public List<String> getNeo4jOnlyObjects() {
        return neo4jOnlyObjects;
    }

    public void setNeo4jOnlyObjects(List<String> neo4jOnlyObjects) {
        this.neo4jOnlyObjects = neo4jOnlyObjects;
    }

    public List<String> getMismatchedObjects() {
        return mismatchedObjects;
    }

    public void setMismatchedObjects(List<String> mismatchedObjects) {
        this.mismatchedObjects = mismatchedObjects;
    }

    public Map<String, List<String>> getMismatchDetails() {
        return mismatchDetails;
    }

    public void setMismatchDetails(Map<String, List<String>> mismatchDetails) {
        this.mismatchDetails = mismatchDetails;
    }

    public FullObjectSnapshot getJavaSnapshot() {
        return javaSnapshot;
    }

    public void setJavaSnapshot(FullObjectSnapshot javaSnapshot) {
        this.javaSnapshot = javaSnapshot;
    }

    public FullObjectSnapshot getNeo4jSnapshot() {
        return neo4jSnapshot;
    }

    public void setNeo4jSnapshot(FullObjectSnapshot neo4jSnapshot) {
        this.neo4jSnapshot = neo4jSnapshot;
    }

    // --- Đối tượng (Nodes) ---
    public List<String> javaOnlyObjects = new ArrayList<>();
    public List<String> neo4jOnlyObjects = new ArrayList<>();
    public List<String> mismatchedObjects = new ArrayList<>();

    // --- Liên kết (Edges) ---
    public List<String> javaOnlyLinks = new ArrayList<>();
    public List<String> neo4jOnlyLinks = new ArrayList<>();
    public List<String> mismatchedLinks = new ArrayList<>();


    // Map chứa thông tin chi tiết sự sai lệch giá trị (ObjectName -> Danh sách mô tả lỗi)
    public Map<String, List<String>> mismatchDetails = new HashMap<>();

    // Lưu trữ snapshot để truy xuất dữ liệu khi thực hiện Push/Pull
// Snapshot lưu trữ để truy xuất dữ liệu thực thi
    public FullObjectSnapshot javaSnapshot;
    public FullObjectSnapshot neo4jSnapshot;


    /**
     * Kiểm tra xem có bất kỳ sự sai lệch nào không
     */

    /**
     * Kiểm tra tổng thể xem có bất kỳ sự sai lệch nào không
     */
    public boolean hasDifference() {
        return !javaOnlyObjects.isEmpty() || !neo4jOnlyObjects.isEmpty() || !mismatchedObjects.isEmpty() ||
                !javaOnlyLinks.isEmpty() || !neo4jOnlyLinks.isEmpty() || !mismatchedLinks.isEmpty();
    }

    /**
     * Báo cáo cho chiều XUÔI (Java -> DB)
     */
    public String generateForwardReport() {
        StringBuilder sb = new StringBuilder("===== OBJECT SYNC: PUSH TO DATABASE =====\n");
        if (!hasDifference()) return "Status: All objects and links are in sync.";

        if (!javaOnlyObjects.isEmpty()) sb.append("[+] NEW OBJECTS: ").append(javaOnlyObjects).append("\n");
        if (!neo4jOnlyObjects.isEmpty()) sb.append("[!] DELETE OBJECTS FROM DB: ").append(neo4jOnlyObjects).append("\n");
        if (!mismatchedObjects.isEmpty()) sb.append("[*] UPDATE OBJECT VALUES: ").append(mismatchedObjects).append("\n");

        sb.append("\n--- Relationship Changes ---\n");
        if (!javaOnlyLinks.isEmpty()) sb.append("[+] NEW LINKS: ").append(javaOnlyLinks).append("\n");
        if (!neo4jOnlyLinks.isEmpty()) sb.append("[!] REMOVE LINKS FROM DB: ").append(neo4jOnlyLinks).append("\n");

        return sb.toString();
    }

    /**
     * Báo cáo cho chiều NGƯỢC (DB -> Java)
     */
    public String generateBackwardReport() {
        StringBuilder sb = new StringBuilder("===== OBJECT SYNC: PULL FROM DATABASE =====\n");
        if (!hasDifference()) return "Status: Local objects and links match Database.";

        if (!neo4jOnlyObjects.isEmpty()) sb.append("[+] NEW FROM DB: ").append(neo4jOnlyObjects).append("\n");
        if (!javaOnlyObjects.isEmpty()) sb.append("[-] REMOVE FROM USE: ").append(javaOnlyObjects).append("\n");

        sb.append("\n--- Relationship Changes ---\n");
        if (!neo4jOnlyLinks.isEmpty()) sb.append("[+] NEW LINKS FROM DB: ").append(neo4jOnlyLinks).append("\n");
        if (!javaOnlyLinks.isEmpty()) sb.append("[-] REMOVE LINKS FROM USE: ").append(javaOnlyLinks).append("\n");

        return sb.toString();
    }

    // --- Getters/Setters để đảm bảo code sạch ---
    public List<String> getJavaOnlyLinks() { return javaOnlyLinks; }
    public List<String> getNeo4jOnlyLinks() { return neo4jOnlyLinks; }
}