package org.uet.dse.neo4j.oclite.ast;

import org.uet.dse.neo4j.OCLBaseVisitor;
import org.uet.dse.neo4j.OCLBaseVisitor.*;
import org.uet.dse.neo4j.OCLParser;
import org.uet.dse.neo4j.OCLParser.*;

import java.util.ArrayList;
import java.util.List;

public class ASTVisitor extends OCLBaseVisitor<ASTNode> {

    @Override
    public ASTNode visitOclFile(OCLParser.OclFileContext ctx) {
        return null;
    }

    @Override
    public ASTNode visitDeclaration(OCLParser.DeclarationContext ctx) {
        return new ASTContext(
            ctx.className.getText(),
            ctx.invName != null ? ctx.invName.getText() : null,
            (ASTExpression) visit(ctx.expression())
        );
    }

    @Override
    public ASTNode visitLogicalExp(OCLParser.LogicalExpContext ctx) {
        return new ASTBinary((ASTExpression)visit(ctx.left), ctx.op.getText(), (ASTExpression)visit(ctx.right));
    }

    @Override
    public ASTNode visitComparisonExp(OCLParser.ComparisonExpContext ctx) {
        return new ASTBinary((ASTExpression)visit(ctx.left), ctx.op.getText(), (ASTExpression)visit(ctx.right));
    }

    @Override
    public ASTNode visitAdditiveExp(OCLParser.AdditiveExpContext ctx) {
        return new ASTBinary((ASTExpression)visit(ctx.left), ctx.op.getText(), (ASTExpression)visit(ctx.right));
    }

    @Override
    public ASTNode visitMultiplicativeExp(OCLParser.MultiplicativeExpContext ctx) {
        return new ASTBinary((ASTExpression)visit(ctx.left), ctx.op.getText(), (ASTExpression)visit(ctx.right));
    }

    @Override
    public ASTNode visitIdExpr(OCLParser.IdExprContext ctx) {
        return new ASTVar(ctx.Identifier().getText());
    }

    @Override
    public ASTNode visitNavigationExpr(OCLParser.NavigationExprContext ctx) {
        return new ASTProperty((ASTExpression)visit(ctx.primary()), ctx.Identifier().getText());
    }

    @Override
    public ASTNode visitCollectionOpExpr(OCLParser.CollectionOpExprContext ctx) {
        List<ASTExpression> args = new ArrayList<>();
        if (ctx.argList() != null) {
            for (OCLParser.ExpressionContext arg : ctx.argList().expression()) args.add((ASTExpression)visit(arg));
        }
        return new ASTCollectionOp((ASTExpression)visit(ctx.primary()), ctx.Identifier().getText(), args);
    }

    @Override
    public ASTNode visitParenExpr(OCLParser.ParenExprContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public ASTNode visitLiteral(OCLParser.LiteralContext ctx) {
        String text = ctx.getText();
        if (ctx.Number() != null) return new ASTLiteral(Double.parseDouble(text));
        if (ctx.StringLiteral() != null) return new ASTLiteral(text.replace("'", ""));
        if (text.equals("true")) return new ASTLiteral(true);
        if (text.equals("false")) return new ASTLiteral(false);
        if (text.equals("null")) return new ASTLiteral(null);
        if (text.equals("self")) return new ASTVar("self");
        return null;
    }
}