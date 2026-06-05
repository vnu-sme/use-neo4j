package org.uet.dse.neo4j.oclite.ast;

public class ASTEnumLiteral extends ASTLiteral {
    public final String enumType;
    public final String literalName;

    public ASTEnumLiteral(String enumType, String literalName) {
        this.enumType = enumType;
        this.literalName = literalName;
    }

    public String value() {
        return enumType == null || enumType.isBlank() ? "#" + literalName : enumType + "::" + literalName;
    }
}
