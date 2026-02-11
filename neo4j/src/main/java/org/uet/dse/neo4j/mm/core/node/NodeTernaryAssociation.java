package org.uet.dse.neo4j.mm.core.node;

import org.uet.dse.neo4j.aop.MetaNode;

import java.util.Map;

@MetaNode(type = MetaNodeData.NODE_TERNARY_ASSOCIATION)
public class NodeTernaryAssociation extends AbstractMetaNode {

    @Override
    public String getMetaName() {
        return MetaNodeData.NODE_TERNARY_ASSOCIATION.getName();
    }

    @Override
    public String getMetaLabel() {
        return MetaNodeData.NODE_TERNARY_ASSOCIATION.getLabel();
    }

    @Override
    public Map<String, Object> toPropertyMap() {
        return null;
    }
}
