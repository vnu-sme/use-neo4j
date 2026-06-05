package org.uet.dse.neo4j.oclite.ast;

public class ASTLetBinding extends ASTNode {
    public final ASTVarDecl variable;
    public final ASTExpression value;

    public ASTLetBinding(ASTVarDecl variable, ASTExpression value) {
        this.variable = variable;
        this.value = value;
    }
}
