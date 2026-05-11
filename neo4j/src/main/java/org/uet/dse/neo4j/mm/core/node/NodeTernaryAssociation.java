package org.uet.dse.neo4j.mm.core.node;

import org.uet.dse.neo4j.aop.MetaNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@MetaNode(type = MetaNodeData.NODE_TERNARY_ASSOCIATION)
public class NodeTernaryAssociation extends AbstractMetaNode {

    private boolean isDerived;
    private String modelName;
    private List<NodeTernarySpoke> spokes;

    public NodeTernaryAssociation(String name) {
        super(name);
    }

    @Override
    public String getMetaName() { return MetaNodeData.NODE_TERNARY_ASSOCIATION.getName(); }

    @Override
    public String getMetaLabel() { return MetaNodeData.NODE_TERNARY_ASSOCIATION.getLabel(); }

    @Override
    public Map<String, Object> toPropertyMap() {
        Map<String, Object> props = new HashMap<>();
        props.put("name", this.getName());
        props.put("isDerived", this.isDerived);
        return props;
    }

    public boolean isDerived()               { return isDerived; }
    public void setDerived(boolean derived)  { isDerived = derived; }
    public String getModelName()             { return modelName; }
    public void setModelName(String name)    { modelName = name; }
    public List<NodeTernarySpoke> getSpokes(){ return spokes; }
    public void setSpokes(List<NodeTernarySpoke> spokes) { this.spokes = spokes; }
}
