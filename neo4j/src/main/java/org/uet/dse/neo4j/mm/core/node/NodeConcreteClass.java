package org.uet.dse.neo4j.mm.core.node;

import org.uet.dse.neo4j.aop.MetaNode;

import java.util.List;

@MetaNode(type = MetaNodeData.NODE_CONCRETE_CLASS)
public class NodeConcreteClass extends AbstractClassNode {
    public NodeConcreteClass(String name) {
        super(name);
    }

    public NodeConcreteClass(String name, String name1, boolean anAbstract, List<NodeAttribute> attrs, List<NodeOperation> ops, List<Object> of) {
        super(name);
    }

    @Override
    public String getMetaName() {
        return MetaNodeData.NODE_CONCRETE_CLASS.getName();
    }

    @Override
    public String getMetaLabel() {
        return MetaNodeData.NODE_CONCRETE_CLASS.getLabel();
    }

}
