package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MAssociationClass;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.uet.dse.neo4j.mm.core.node.NodeAssociationClass;

public class UseAssociationClassTranslator implements IModelTranslator<UseAssociationClassTranslator.AssociationClassContext, NodeAssociationClass> {

    public record AssociationClassContext(MAssociationClass assocClass, String modelName) {}

    @Override
    public NodeAssociationClass translate(AssociationClassContext ctx) {
        MAssociationClass ac = ctx.assocClass();
        MAssociationEnd src = ac.associationEnds().get(0);
        MAssociationEnd tgt = ac.associationEnds().get(1);

        NodeAssociationClass node = new NodeAssociationClass(ac.name());
        node.setModelName(ctx.modelName());
        node.setSourceName(src.cls().name());
        node.setSourceRole(src.name());
        node.setSourceMult(src.multiplicity().toString());
        node.setTargetName(tgt.cls().name());
        node.setTargetRole(tgt.name());
        node.setTargetMult(tgt.multiplicity().toString());

        return node;
    }
}