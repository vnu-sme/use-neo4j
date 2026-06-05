package org.uet.dse.neo4j.oclite.ast;

public class ASTTypeLiteral extends ASTExpression {
    public final String typeName;

    public ASTTypeLiteral(String typeName) {
        this.typeName = typeName;
    }
}
