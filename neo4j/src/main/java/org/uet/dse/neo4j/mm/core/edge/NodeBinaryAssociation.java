package org.uet.dse.neo4j.mm.core.edge;

public abstract class NodeBinaryAssociation {
    private final String assocName;

    private final String sourceName;
    private final String sourceRole;
    private final String sourceMult;
    private final int sourceKind;

    private final String targetName;
    private final String targetRole;
    private final String targetMult;
    private final int targetKind;

    protected NodeBinaryAssociation(
        String assocName,
        String sourceName, String sourceRole, String sourceMult, int sourceKind,
        String targetName, String targetRole, String targetMult, int targetKind
    ) {
        this.assocName  = assocName;
        this.sourceName = sourceName;
        this.sourceRole = sourceRole;
        this.sourceMult = sourceMult;
        this.sourceKind = sourceKind;
        this.targetName = targetName;
        this.targetRole = targetRole;
        this.targetMult = targetMult;
        this.targetKind = targetKind;
    }

    public abstract String getEdgeLabel();

    public String getAssocName()  { return assocName; }
    public String getSourceName() { return sourceName; }
    public String getSourceRole() { return sourceRole; }
    public String getSourceMult() { return sourceMult; }
    public int    getSourceKind() { return sourceKind; }
    public String getTargetName() { return targetName; }
    public String getTargetRole() { return targetRole; }
    public String getTargetMult() { return targetMult; }
    public int    getTargetKind() { return targetKind; }
}