package org.uet.dse.neo4j.oclite.ast;

public class ASTQualifiedNavigation extends ASTExpression {
    public final ASTExpression source;
    public final String roleName;
    public final ASTExpression qualifier;

    public ASTQualifiedNavigation(ASTExpression source, String roleName, ASTExpression qualifier) {
        this.source = source;
        this.roleName = roleName;
        this.qualifier = qualifier;
    }
}
