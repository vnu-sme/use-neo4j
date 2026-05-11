package org.uet.dse.neo4j.mm.core.node;

public class NodeTernarySpoke {
    private final String clsName;
    private final String role;
    private final String mult;
    private final String edgeLabel;
    private final int index;

    public NodeTernarySpoke(String clsName, String role, String mult, String edgeLabel, int index) {
        this.clsName   = clsName;
        this.role      = role;
        this.mult      = mult;
        this.edgeLabel = edgeLabel;
        this.index     = index;
    }

    public String getClsName()   { return clsName; }
    public String getRole()      { return role; }
    public String getMult()      { return mult; }
    public String getEdgeLabel() { return edgeLabel; }
    public int    getIndex()     { return index; }
}