package org.uet.dse.neo4j.mm.core.edge;

public class ComposeOf extends NodeBinaryAssociation {
    public ComposeOf(
        String assocName,
        String sourceName, String sourceRole, String sourceMult, int sourceKind,
        String targetName, String targetRole, String targetMult, int targetKind
    ) {
        super(assocName, sourceName, sourceRole, sourceMult, sourceKind,
                         targetName, targetRole, targetMult, targetKind);
    }

    @Override
    public String getEdgeLabel() { return "ComposeOf"; }
}