package org.uet.dse.neo4j.mm.object;

public record ObjectReference(
        String targetId,
        int index
) {}
