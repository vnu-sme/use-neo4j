package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.ocl.type.EnumType;
import org.uet.dse.neo4j.mm.core.node.NodeEnumeration;

public class UseEnumTranslator implements IModelTranslator<EnumType, NodeEnumeration> {

    @Override
    public NodeEnumeration translate(EnumType source) {
        return new NodeEnumeration(source.name(), source.getLiterals());
    }
}