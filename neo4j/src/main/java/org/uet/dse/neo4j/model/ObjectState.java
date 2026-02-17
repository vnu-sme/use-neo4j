package org.uet.dse.neo4j.model;

import java.util.*;

public class ObjectState {
    public String name;
    public String className;

    // attrName -> Giá trị (Primitive hoặc mảng Primitive)
    public Map<String, Object> primitiveValues = new HashMap<>();

    // attrName -> Kiểu dữ liệu thực tế (vd: "Int", "Double", "String", "Sex"...)
    public Map<String, String> attributeTypes = new HashMap<>();

    // attrName -> Danh sách tên Object tham chiếu
    public Map<String, List<String>> objectReferences = new HashMap<>();

    private Map<String, Object> rawData = new HashMap<>();

    public Map<String, Object> getRawData() {
        return this.rawData;
    }

    public void setRawData(Map<String, Object> rawData) {
        this.rawData = rawData;
    }

    public void setRawValue(String attrName, Object value) {
        this.rawData.put(attrName, value);
    }

    public Object getRawValue(String attrName) {
        return this.rawData.get(attrName);
    }

    public boolean isSameAs(ObjectState other) {
        if (other == null) return false;

        // Sử dụng Objects.equals cho toàn bộ các Map để an toàn với null
        return Objects.equals(this.name, other.name) &&
                Objects.equals(this.className, other.className) &&
                comparePrimitiveMaps(this.primitiveValues, other.primitiveValues) &&
                Objects.equals(this.attributeTypes, other.attributeTypes) &&
                Objects.equals(this.objectReferences, other.objectReferences);
    }

    /**
     * Hàm helper so sánh 2 Map giá trị nguyên thủy có chứa giá trị null
     */
    private boolean comparePrimitiveMaps(Map<String, Object> map1, Map<String, Object> map2) {
        if (map1.size() != map2.size()) return false;
        for (String key : map1.keySet()) {
            Object v1 = map1.get(key);
            Object v2 = map2.get(key);
            // So sánh toString để tránh lệch kiểu dữ liệu Long/Integer từ Neo4j
            String s1 = (v1 == null) ? "null" : v1.toString();
            String s2 = (v2 == null) ? "null" : v2.toString();
            if (!s1.equals(s2)) return false;
        }
        return true;
    }
}