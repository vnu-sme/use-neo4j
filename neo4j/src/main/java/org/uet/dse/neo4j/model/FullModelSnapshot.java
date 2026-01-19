package org.uet.dse.neo4j.model;

import java.util.HashMap;
import java.util.Map;

public class FullModelSnapshot {
    public Map<String, ClassState> classes = new HashMap<>();
    public Map<String, AssociationState> associations = new HashMap<>();
}