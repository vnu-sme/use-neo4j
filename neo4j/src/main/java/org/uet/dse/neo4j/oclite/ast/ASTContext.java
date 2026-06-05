package org.uet.dse.neo4j.oclite.ast;

public class ASTContext extends ASTNode {
    public String className, invName;
    public String contextVariableName;
    public ASTExpression expression;
    public ASTContext(String c, String i, ASTExpression e) { this(c, i, null, e); }
    public ASTContext(String c, String i, String contextVariableName, ASTExpression e) {
        this.className = c;
        this.invName = i;
        this.contextVariableName = contextVariableName;
        this.expression = e;
    }
}
