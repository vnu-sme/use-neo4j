package org.uet.dse.neo4j.mm.core.node;

import org.uet.dse.neo4j.aop.MetaNode;

@MetaNode(type = MetaNodeData.NODE_ASSOCIATION_CLASS)
public class NodeAssociationClass extends AbstractClassNode {

    private String modelName;
    private String sourceName;
    private String sourceRole;
    private String sourceMult;
    private String targetName;
    private String targetRole;
    private String targetMult;

    public NodeAssociationClass(String name) {
        super(name);
    }

    @Override
    public String getMetaName() { return MetaNodeData.NODE_ASSOCIATION_CLASS.getName(); }

    @Override
    public String getMetaLabel() { return MetaNodeData.NODE_ASSOCIATION_CLASS.getLabel(); }

    public String getModelName()  { return modelName; }
    public String getSourceName() { return sourceName; }
    public String getSourceRole() { return sourceRole; }
    public String getSourceMult() { return sourceMult; }
    public String getTargetName() { return targetName; }
    public String getTargetRole() { return targetRole; }
    public String getTargetMult() { return targetMult; }

    public void setModelName(String modelName)   { this.modelName = modelName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public void setSourceRole(String sourceRole) { this.sourceRole = sourceRole; }
    public void setSourceMult(String sourceMult) { this.sourceMult = sourceMult; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    public void setTargetMult(String targetMult) { this.targetMult = targetMult; }
}
