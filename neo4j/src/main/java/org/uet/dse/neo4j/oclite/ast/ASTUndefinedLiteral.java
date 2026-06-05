package org.uet.dse.neo4j.oclite.ast;

public class ASTUndefinedLiteral extends ASTLiteral {
    public final String typeName;

    public ASTUndefinedLiteral(String typeName) {
        this.typeName = typeName;
    }
}
