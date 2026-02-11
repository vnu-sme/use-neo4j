package org.uet.dse.neo4j.mm.core.node;

import org.uet.dse.neo4j.aop.MetaNode;

import java.util.Map;

@MetaNode(type = MetaNodeData.NODE_PRE_CONDITION)
public class NodePreCondition extends AbstractConditionNode {

    @Override
    public String getMetaName() {
        return MetaNodeData.NODE_PRE_CONDITION.getName();
    }

    @Override
    public String getMetaLabel() {
        return MetaNodeData.NODE_PRE_CONDITION.getLabel();
    }

    @Override
    public Map<String, Object> toPropertyMap() {
        return null;
    }
}
