package org.uet.dse.neo4j.oclite.ast;

import java.util.List;

public class ASTCollectionLiteral extends ASTExpression {
    public final String kind;
    public final List<ASTExpression> items;

    public ASTCollectionLiteral(String kind, List<ASTExpression> items) {
        this.kind = kind;
        this.items = List.copyOf(items);
    }
}
