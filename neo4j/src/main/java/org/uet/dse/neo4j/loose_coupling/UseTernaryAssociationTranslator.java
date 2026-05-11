package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.uet.dse.neo4j.mm.core.node.NodeTernaryAssociation;
import org.uet.dse.neo4j.mm.core.node.NodeTernarySpoke;

import java.util.ArrayList;
import java.util.List;

public class UseTernaryAssociationTranslator implements IModelTranslator<UseTernaryAssociationTranslator.TernaryAssociationContext, NodeTernaryAssociation> {

    public record TernaryAssociationContext(MAssociation assoc, String modelName) {}

    @Override
    public NodeTernaryAssociation translate(TernaryAssociationContext ctx) {
        MAssociation assoc = ctx.assoc();
        List<MAssociationEnd> ends = assoc.associationEnds();

        NodeTernaryAssociation node = new NodeTernaryAssociation(assoc.name());
        node.setModelName(ctx.modelName());
        node.setDerived(false);

        List<NodeTernarySpoke> spokes = new ArrayList<>();
        for (int i = 0; i < ends.size(); i++) {
            MAssociationEnd end = ends.get(i);
            spokes.add(new NodeTernarySpoke(
                end.cls().name(),
                end.name(),
                end.multiplicity().toString(),
                resolveEdgeLabel(end.aggregationKind()),
                i
            ));
        }
        node.setSpokes(spokes);

        return node;
    }

    private String resolveEdgeLabel(int aggregationKind) {
        if (aggregationKind == 2) return "ComposeOf";
        if (aggregationKind == 1) return "Aggregates";
        return "AssociateWith";
    }
}