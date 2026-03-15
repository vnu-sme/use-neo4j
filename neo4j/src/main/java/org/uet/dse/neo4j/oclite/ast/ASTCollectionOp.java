package org.uet.dse.neo4j.oclite.ast;

import java.util.List;

//(p1->size())
public class ASTCollectionOp extends ASTExpression {
    public ASTExpression source;
    public String opName;
    public List<ASTExpression> args;
    public ASTCollectionOp(ASTExpression s, String o, List<ASTExpression> a) { this.source = s; this.opName = o; this.args = a; }
}