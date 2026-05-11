package org.uet.dse.neo4j.mm.core.node;

import java.util.HashMap;
import java.util.Map;

public abstract class AbstractClassNode extends AbstractMetaNode {
    protected boolean hasParent;
    protected boolean hasChildren;

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isAbstract;

    public AbstractClassNode(String name) {
        super(name);
    }

    public void setHasParent(boolean hasParent) { this.hasParent = hasParent; }
    public void setHasChildren(boolean hasChildren) { this.hasChildren = hasChildren; }

    @Override
    public Map<String, Object> toPropertyMap() {
        Map<String, Object> props = new HashMap<>();
        props.put("name", getName());
        props.put("hasParent", this.hasParent);
        props.put("hasChildren", this.hasChildren);
        return props;
    }

}