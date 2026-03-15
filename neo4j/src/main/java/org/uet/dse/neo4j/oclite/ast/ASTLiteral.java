package org.uet.dse.neo4j.oclite.ast;

// (10, 'abc', true)
public class ASTLiteral extends ASTExpression {
    public Object value;
    public ASTLiteral(Object v) { this.value = v; }
}