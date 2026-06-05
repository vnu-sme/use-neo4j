package org.uet.dse.neo4j.oclite.ast;

public class ASTVarDecl extends ASTNode {
    public final String name;
    public final String typeName;

    public ASTVarDecl(String name, String typeName) {
        this.name = name;
        this.typeName = typeName;
    }

    public boolean hasType() {
        return typeName != null && !typeName.isBlank();
    }
}
