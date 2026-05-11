package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.uet.dse.neo4j.mm.core.edge.Aggregates;
import org.uet.dse.neo4j.mm.core.edge.AssociateWith;
import org.uet.dse.neo4j.mm.core.edge.ComposeOf;
import org.uet.dse.neo4j.mm.core.edge.NodeBinaryAssociation;

public class UseBinaryAssociationTranslator implements IModelTranslator<MAssociation, NodeBinaryAssociation> {

    @Override
    public NodeBinaryAssociation translate(MAssociation source) {
        MAssociationEnd end0 = source.associationEnds().get(0);
        MAssociationEnd end1 = source.associationEnds().get(1);

        return createEdge(
            source.name(),
            end0.aggregationKind(), end1.aggregationKind(),
            end0.cls().name(), end0.name(), end0.multiplicity().toString(), end0.aggregationKind(),
            end1.cls().name(), end1.name(), end1.multiplicity().toString(), end1.aggregationKind()
        );
    }

    private NodeBinaryAssociation createEdge(
        String assocName, int kind0, int kind1,
        String sName, String sRole, String sMult, int sKind,
        String tName, String tRole, String tMult, int tKind
    ) {
        if (kind0 == 2 || kind1 == 2)
            return new ComposeOf(assocName, sName, sRole, sMult, sKind, tName, tRole, tMult, tKind);
        if (kind0 == 1 || kind1 == 1)
            return new Aggregates(assocName, sName, sRole, sMult, sKind, tName, tRole, tMult, tKind);
        return new AssociateWith(assocName, sName, sRole, sMult, sKind, tName, tRole, tMult, tKind);
    }
}