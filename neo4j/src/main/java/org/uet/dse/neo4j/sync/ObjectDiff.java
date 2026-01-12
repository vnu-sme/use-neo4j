package org.uet.dse.neo4j.sync;

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

    public Map<String, ObjectState> getJavaSnapshot() {
        return javaSnapshot;
    }

    public void setJavaSnapshot(Map<String, ObjectState> javaSnapshot) {
        this.javaSnapshot = javaSnapshot;
    }

    public Map<String, ObjectState> getNeo4jSnapshot() {
        return neo4jSnapshot;
    }

    public void setNeo4jSnapshot(Map<String, ObjectState> neo4jSnapshot) {
        this.neo4jSnapshot = neo4jSnapshot;
    }

    // Những đối tượng chỉ có ở phía Java (USE)
    public List<String> javaOnlyObjects = new ArrayList<>();
    // Những đối tượng chỉ có ở phía Database (Neo4j)
    public List<String> neo4jOnlyObjects = new ArrayList<>();
    // Những đối tượng có ở cả hai nơi nhưng giá trị thuộc tính hoặc tham chiếu khác nhau
    public List<String> mismatchedObjects = new ArrayList<>();

    // Map chứa thông tin chi tiết sự sai lệch giá trị (ObjectName -> Danh sách mô tả lỗi)
    public Map<String, List<String>> mismatchDetails = new HashMap<>();

    // Lưu trữ snapshot để truy xuất dữ liệu khi thực hiện Push/Pull
    public Map<String, ObjectState> javaSnapshot;
    public Map<String, ObjectState> neo4jSnapshot;

    /**
     * Kiểm tra xem có bất kỳ sự sai lệch nào không
     */
    public boolean hasDifference() {
        return !javaOnlyObjects.isEmpty() || !neo4jOnlyObjects.isEmpty() || !mismatchedObjects.isEmpty();
    }

    /**
     * Báo cáo cho chiều XUÔI (Java -> DB): Đẩy dữ liệu từ máy mình lên server
     */
    public String generateForwardReport() {
        StringBuilder sb = new StringBuilder("===== OBJECT SYNC: PUSH TO DATABASE =====\n");
        if (javaOnlyObjects.isEmpty() && neo4jOnlyObjects.isEmpty() && mismatchedObjects.isEmpty()) {
            return sb.append("Status: All objects are already in sync.").toString();
        }

        if (!javaOnlyObjects.isEmpty()) {
            sb.append("[+] NEW OBJECTS (Will be created in DB):\n");
            javaOnlyObjects.forEach(o -> sb.append("    - ").append(o).append("\n"));
        }

        if (!neo4jOnlyObjects.isEmpty()) {
            sb.append("[!] ORPHAN DB NODES (Not in your USE - Will be DELETED from DB):\n");
            neo4jOnlyObjects.forEach(o -> sb.append("    - ").append(o).append("\n"));
        }

        if (!mismatchedObjects.isEmpty()) {
            sb.append("[*] VALUE MISMATCHES (DB values will be overwritten by Java):\n");
            mismatchedObjects.forEach(o -> {
                sb.append("    - ").append(o).append("\n");
                if (mismatchDetails.containsKey(o)) {
                    mismatchDetails.get(o).forEach(d -> sb.append("        > ").append(d).append("\n"));
                }
            });
        }
        return sb.toString();
    }

    /**
     * Báo cáo cho chiều NGƯỢC (DB -> Java): Kéo dữ liệu từ server về máy mình
     */
    public String generateBackwardReport() {
        StringBuilder sb = new StringBuilder("===== OBJECT SYNC: PULL FROM DATABASE =====\n");
        if (!hasDifference()) return sb.append("Status: All objects are already in sync.").toString();

        if (!neo4jOnlyObjects.isEmpty()) {
            sb.append("[+] NEW FROM DB (Will be created in USE):\n");
            neo4jOnlyObjects.forEach(o -> sb.append("    - ").append(o).append("\n"));
        }

        if (!javaOnlyObjects.isEmpty()) {
            sb.append("[-] REMOVED IN DB (Will be DELETED from USE):\n");
            javaOnlyObjects.forEach(o -> sb.append("    - ").append(o).append("\n"));
        }

        if (!mismatchedObjects.isEmpty()) {
            sb.append("[*] VALUE UPDATES (Your USE values will match DB):\n");
            mismatchedObjects.forEach(o -> sb.append("    - ").append(o).append("\n"));
        }
        return sb.toString();
    }
}