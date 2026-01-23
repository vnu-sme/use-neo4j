package org.uet.dse.neo4j.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FullModelSnapshot {
    public Map<String, ClassState> classes = new HashMap<>();
    public Map<String, AssociationState> associations = new HashMap<>();


    // Chứa các Association N-bên (vd: Filmstrip)
    public List<Map<String, Object>> ternaryAssociations = new ArrayList<>();
}