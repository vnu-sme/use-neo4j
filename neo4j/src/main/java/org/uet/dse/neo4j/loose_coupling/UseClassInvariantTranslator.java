package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MClassInvariant;
import org.uet.dse.neo4j.mm.core.node.NodeClassInvariant;

public class UseClassInvariantTranslator implements IModelTranslator<UseClassInvariantTranslator.InvariantContext, NodeClassInvariant> {

    public record InvariantContext(MClassInvariant invariant, String className, int index) {}

    @Override
    public NodeClassInvariant translate(InvariantContext ctx) {
        MClassInvariant inv = ctx.invariant();
        String displayName = (inv.name() == null || inv.name().isEmpty()) ? "unnamed" : inv.name();
        String invId = ctx.className() + "_inv_" + ctx.index();

        NodeClassInvariant node = new NodeClassInvariant(invId);
        node.setInvName(displayName);
        node.setExpression(inv.bodyExpression().toString());
        node.setExistential(inv.isExistential());
        node.setIndex(ctx.index());

        return node;
    }
}