package org.uet.dse.neo4j.model;

import java.util.*;

public class ObjectState {
    public String name;
    public String className;
    // Lưu các giá trị nguyên thủy
    public Map<String, Object> primitiveValues = new HashMap<>();
    // Lưu các tham chiếu (AttrName -> List of TargetObjectNames)
    public Map<String, List<String>> objectReferences = new HashMap<>();

    public boolean isSameAs(ObjectState other) {
        return this.name.equals(other.name) && 
               this.primitiveValues.equals(other.primitiveValues) &&
               this.objectReferences.equals(other.objectReferences);
    }
}