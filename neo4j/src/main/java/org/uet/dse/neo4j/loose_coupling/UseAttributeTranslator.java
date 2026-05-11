package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.ocl.type.Type;
import org.uet.dse.neo4j.mm.core.common.NCollectionType;
import org.uet.dse.neo4j.mm.core.node.NodeAttribute;
import org.uet.dse.neo4j.sync.helper.UmlTypeTranslator;

public class UseAttributeTranslator implements IModelTranslator<UseAttributeTranslator.AttributeContext, NodeAttribute> {
    public record AttributeContext(MClass cls, MAttribute attr, int index) {}

    @Override
    public NodeAttribute translate(AttributeContext ctx) {
        MAttribute attr = ctx.attr();
        String instanceId = ctx.cls().name() + "_" + attr.name();

        Type fullType = attr.type();
        Type ultimateBaseType = UmlTypeTranslator.getUltimateBaseType(fullType);
        int depth = UmlTypeTranslator.getCollectionDepth(fullType);
        boolean isCollection = depth > 0;
        boolean isNested = depth > 1;

        NodeAttribute node = new NodeAttribute(instanceId);
        node.setAttrName(attr.name());
        node.setIndex(ctx.index());
        node.setNestedCollection(isNested);
        node.setCollection(isCollection);
        node.setType(UmlTypeTranslator.toDatabaseType(ultimateBaseType));
        node.setDerived(attr.isDerived());
        node.setCollectionType(resolveCollectionType(fullType, isCollection));

        return node;
    }

    private NCollectionType resolveCollectionType(Type fullType, boolean isCollection) {
        if (!isCollection) return NCollectionType.None;
        String colTypeStr = UmlTypeTranslator.getCollectionType(fullType);
        try {
            return NCollectionType.valueOf(colTypeStr);
        } catch (IllegalArgumentException e) {
            return NCollectionType.None;
        }
    }
}