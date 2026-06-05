package org.uet.dse.neo4j.oclite.ast;

import org.uet.dse.neo4j.OCLBaseVisitor;
import org.uet.dse.neo4j.OCLParser;
import org.uet.dse.neo4j.OCLParser.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ASTVisitor extends OCLBaseVisitor<ASTNode> {
    private static final List<String> QUERY_OPERATIONS = List.of(
        "select", "reject", "collect", "collectnested", "forall", "exists", "any", "one",
        "isunique", "sortedby", "closure"
    );

    @Override
    public ASTNode visitOclFile(OCLParser.OclFileContext ctx) {
        if (!ctx.declaration().isEmpty()) {
            return visit(ctx.declaration(0));
        }

        if (!ctx.expression().isEmpty()) {
            return visit(ctx.expression(0));
        }
        return null;
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
    public ASTNode visitLetExp(OCLParser.LetExpContext ctx) {
        List<ASTLetBinding> bindings = new ArrayList<>();
        for (OCLParser.LetBindingContext bindingCtx : ctx.letBinding()) {
            bindings.add(parseLetBinding(bindingCtx));
        }
        return new ASTLet(bindings, (ASTExpression) visit(ctx.expression()));
    }

    @Override
    public ASTNode visitIfExp(OCLParser.IfExpContext ctx) {
        return new ASTIf(
            (ASTExpression) visit(ctx.condition),
            (ASTExpression) visit(ctx.thenExpression),
            (ASTExpression) visit(ctx.elseExpression)
        );
    }

    @Override
    public ASTNode visitUnaryExp(OCLParser.UnaryExpContext ctx) {
        return new ASTUnary(ctx.op.getText(), (ASTExpression) visit(ctx.expression()));
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
    public ASTNode visitQualifiedNavigationExpr(OCLParser.QualifiedNavigationExprContext ctx) {
        return new ASTQualifiedNavigation(
            (ASTExpression) visit(ctx.primary()),
            ctx.Identifier().getText(),
            (ASTExpression) visit(ctx.expression())
        );
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
    public ASTNode visitCollectionPropertyExpr(OCLParser.CollectionPropertyExprContext ctx) {
        return new ASTCollectionOp((ASTExpression) visit(ctx.primary()), ctx.Identifier().getText(), Collections.emptyList());
    }

    @Override
    public ASTNode visitQueryExpr(OCLParser.QueryExprContext ctx) {
        ASTExpression source = (ASTExpression) visit(ctx.primary());
        String op = ctx.Identifier().getText();
        OCLParser.QueryArgsContext argsCtx = ctx.queryArgs();
        ASTExpression body = (ASTExpression) visit(argsCtx.expression());
        List<ASTVarDecl> variables = parseVarDecls(argsCtx.varDeclList());

        if (!isQueryOperation(op) && variables.isEmpty()) {
            return new ASTCollectionOp(source, op, List.of(body));
        }
        return new ASTQuery(source, op, variables, body);
    }

    @Override
    public ASTNode visitIterateExpr(OCLParser.IterateExprContext ctx) {
        OCLParser.IterateArgsContext args = ctx.iterateArgs();
        return new ASTIterate(
            (ASTExpression) visit(ctx.primary()),
            parseVarDecl(args.varDecl()),
            parseLetBinding(args.accumulator),
            (ASTExpression) visit(args.expression())
        );
    }

    @Override
    public ASTNode visitCollectionLiteralExpr(OCLParser.CollectionLiteralExprContext ctx) {
        return visit(ctx.collectionLiteral());
    }

    @Override
    public ASTNode visitCollectionLiteral(OCLParser.CollectionLiteralContext ctx) {
        List<ASTExpression> items = new ArrayList<>();
        for (OCLParser.CollectionItemContext itemCtx : ctx.collectionItem()) {
            items.add(parseCollectionItem(itemCtx));
        }
        return new ASTCollectionLiteral(ctx.collectionKind().getText(), items);
    }

    @Override
    public ASTNode visitParenExpr(OCLParser.ParenExprContext ctx) {
        return visit(ctx.expression());
    }


    @Override
    public ASTNode visitLiteral(LiteralContext ctx) {
        String text = ctx.getText();

        if (text.equals("null")) {
            return new ASTNullLiteral();
        }

        if (text.equals("Undefined") || text.equals("invalid")) {
            return new ASTUndefinedLiteral(null);
        }

        if (text.startsWith("oclUndefined")) {
            return new ASTUndefinedLiteral(ctx.typeRef() != null ? typeName(ctx.typeRef()) : null);
        }

        if (text.startsWith("#")) {
            return new ASTEnumLiteral(null, ctx.Identifier(0).getText());
        }

        if (ctx.Identifier().size() == 2 && text.contains("::")) {
            return new ASTEnumLiteral(ctx.Identifier(0).getText(), ctx.Identifier(1).getText());
        }

        if (ctx.Number() != null) {
            if (text.contains(".")) {
                return new ASTRealLiteral(Double.parseDouble(text));
            } else {
                return new ASTIntegerLiteral(Long.parseLong(text));
            }
        }

        if (ctx.StringLiteral() != null) {
            return new ASTStringLiteral(text.substring(1, text.length() - 1));
        }

      return switch (text) {
        case "true" -> new ASTBooleanLiteral(true);
        case "false" -> new ASTBooleanLiteral(false);
        case "self" -> new ASTVar("self");
        default -> null;
      };

    }
    @Override
    public ASTNode visitLogicalImpliesExp(OCLParser.LogicalImpliesExpContext ctx) {
        return new ASTBinary(
            (ASTExpression) visit(ctx.left),
            "implies",
            (ASTExpression) visit(ctx.right)
        );
    }

    @Override
    public ASTNode visitMethodCallExpr(OCLParser.MethodCallExprContext ctx) {
        ASTExpression source = (ASTExpression) visit(ctx.primary());

        String methodName = ctx.Identifier().getText();

        List<ASTExpression> args = new ArrayList<>();
        if (ctx.argList() != null) {
            for (OCLParser.ExpressionContext argCtx : ctx.argList().expression()) {
                args.add((ASTExpression) visit(argCtx));
            }
        }

        return new ASTMethodCall(source, methodName, args);
    }

    @Override
    public ASTNode visitStaticMethodCallExpr(OCLParser.StaticMethodCallExprContext ctx) {
        ASTExpression source = new ASTVar(ctx.Identifier(0).getText());
        String methodName = ctx.Identifier(1).getText();

        List<ASTExpression> args = new ArrayList<>();
        if (ctx.argList() != null) {
            for (OCLParser.ExpressionContext argCtx : ctx.argList().expression()) {
                args.add((ASTExpression) visit(argCtx));
            }
        }

        return new ASTMethodCall(source, methodName, args);
    }

    @Override
    public ASTNode visitDeclaration(OCLParser.DeclarationContext ctx) {
        String className = ctx.className.getText();
        String invName = ctx.invName != null ? ctx.invName.getText() : "UnnamedInv";
        String contextVariable = ctx.contextVariable() != null ? ctx.contextVariable().Identifier().getText() : null;
        ASTExpression expr = (ASTExpression) visit(ctx.expression());
        return new ASTContext(className, invName, contextVariable, expr);
    }

    private ASTLetBinding parseLetBinding(OCLParser.LetBindingContext ctx) {
        return new ASTLetBinding(
            new ASTVarDecl(ctx.Identifier().getText(), ctx.typeRef() != null ? typeName(ctx.typeRef()) : null),
            (ASTExpression) visit(ctx.expression())
        );
    }

    private List<ASTVarDecl> parseVarDecls(OCLParser.VarDeclListContext ctx) {
        if (ctx == null) return Collections.emptyList();
        List<ASTVarDecl> result = new ArrayList<>();
        for (OCLParser.VarDeclContext varDecl : ctx.varDecl()) {
            result.add(parseVarDecl(varDecl));
        }
        return result;
    }

    private ASTVarDecl parseVarDecl(OCLParser.VarDeclContext ctx) {
        return new ASTVarDecl(ctx.Identifier().getText(), ctx.typeRef() != null ? typeName(ctx.typeRef()) : null);
    }

    private ASTExpression parseCollectionItem(OCLParser.CollectionItemContext ctx) {
        if (ctx.expression().size() == 1) {
            return (ASTExpression) visit(ctx.expression(0));
        }
        return new ASTRange((ASTExpression) visit(ctx.expression(0)), (ASTExpression) visit(ctx.expression(1)));
    }

    private String typeName(OCLParser.TypeRefContext ctx) {
        return ctx == null ? null : ctx.getText();
    }

    private boolean isQueryOperation(String op) {
        return QUERY_OPERATIONS.contains(op.toLowerCase(Locale.ROOT));
    }
}
