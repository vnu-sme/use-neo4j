package org.uet.dse.neo4j.model_trafo;

import org.uet.dse.neo4j.TGGBaseVisitor;
import org.uet.dse.neo4j.TGGParser;

public class TGGBuildVisitor extends TGGBaseVisitor<Object> {

    @Override
    public TGGTransformation visitTggFile(TGGParser.TggFileContext ctx) {
        TGGTransformation trans = new TGGTransformation();
        trans.name = ctx.transformationHeader().ID().getText();
        trans.direction = ctx.directionHeader().ID().getText();

        if (ctx.ruleSpec() != null) {
            for (TGGParser.RuleSpecContext rCtx : ctx.ruleSpec()) {
                trans.rules.add((TGGRule) visit(rCtx));
            }
        }
        return trans;
    }

    @Override
    public TGGRule visitRuleSpec(TGGParser.RuleSpecContext ctx) {
        TGGRule rule = new TGGRule();
        rule.name = ctx.ID().getText();

        // Sử dụng nhãn trực tiếp từ parser context
        rule.source = parseBlock(ctx.sourceBlock().lhs, ctx.sourceBlock().rhs);
        rule.target = parseBlock(ctx.targetBlock().lhs, ctx.targetBlock().rhs);
        rule.correspondence = parseBlock(ctx.corrBlock().lhs, ctx.corrBlock().rhs);

        return rule;
    }

    private TGGBlock parseBlock(TGGParser.ListElementsContext lhsCtx, TGGParser.ListElementsContext rhsCtx) {
        TGGBlock block = new TGGBlock();

        // Xử lý LHS (Phần trong ngoặc tròn)
        if (lhsCtx != null) {
            for (TGGParser.ElementContext e : lhsCtx.element()) {
                Object res = visit(e);
                if (res instanceof TGGElement) block.lhs.add((TGGElement) res);
            }
        }

        // Xử lý RHS (Phần trong ngoặc nhọn)
        if (rhsCtx != null) {
            for (TGGParser.ElementContext e : rhsCtx.element()) {
                Object res = visit(e);
                if (res instanceof TGGElement) block.rhs.add((TGGElement) res);
            }
        }

        return block;
    }

    @Override
    public Object visitElement(TGGParser.ElementContext ctx) {
        return visitChildren(ctx);
    }

    @Override
    public NodeDecl visitNodeDecl(TGGParser.NodeDeclContext ctx) {
        NodeDecl node = new NodeDecl();
        node.name = ctx.ID(0).getText();
        node.type = ctx.ID(1).getText();
        node.isStar = ctx.getText().contains("*");
        return node;
    }

    @Override
    public EdgeDecl visitEdgeDecl(TGGParser.EdgeDeclContext ctx) {
        EdgeDecl edge = new EdgeDecl();
        edge.src = ctx.ID(0).getText();
        edge.target = ctx.ID(1).getText();
        edge.type = ctx.ID(2).getText();
        return edge;
    }

    @Override
    public CorrDecl visitCorrespondenceDecl(TGGParser.CorrespondenceDeclContext ctx) {
        CorrDecl corr = new CorrDecl();

        // 1. Lấy tham chiếu Node nguồn và đích (có thể chứa dấu *)
        // idWithStar(0) tương ứng với phần tử đầu tiên trong cặp ngoặc đầu
        // idWithStar(1) tương ứng với phần tử thứ hai
        corr.nodeARef = ctx.idWithStar(0).getText(); // Ví dụ: "fm*"
        corr.nodeBRef = ctx.idWithStar(1).getText(); // Ví dụ: "mp" (Đã sửa lỗi corr.no)

        // 2. Lấy các Alias trong phần 'as (ID, ID)'
        // ID(0) là ID đầu tiên xuất hiện bên ngoài idWithStar
        corr.aliasA = ctx.ID(0).getText(); // Ví dụ: "f"
        corr.aliasB = ctx.ID(1).getText(); // Ví dụ: "mp"

        // 3. Lấy tên biến quan hệ và kiểu quan hệ trong phần 'in ID : ID'
        corr.corrName = ctx.ID(2).getText(); // Ví dụ: "f2mp"
        corr.corrType = ctx.ID(3).getText(); // Ví dụ: "F2MP"

        return corr;
    }

    @Override
    public Constraint visitConstraint(TGGParser.ConstraintContext ctx) {
        Constraint constraint = new Constraint();
        // if (label:)
        if (ctx.ID() != null) {
            constraint.label = ctx.ID().getText();
        }

        // content inside []
        String fullText = ctx.getText();
        int start = fullText.indexOf("[");
        int end = fullText.lastIndexOf("]");
        if (start != -1 && end != -1) {
            constraint.expression = fullText.substring(start + 1, end).trim();
        }
        return constraint;
    }

    @Override
    public AttributeAssignment visitAttributeCode(TGGParser.AttributeCodeContext ctx) {
        AttributeAssignment attr = new AttributeAssignment();
        // content inside ``
        String fullText = ctx.getText();
        int start = fullText.indexOf("`") + 1;
        int end = fullText.lastIndexOf("`") ;
        if (start < end) {
            attr.code = fullText.substring(start, end).trim();
        } else {
            attr.code = "";
        }
        return attr;
    }

    @Override
    public Constraint visitCorrelationLogic(TGGParser.CorrelationLogicContext ctx) {
        Constraint logic = new Constraint();
        logic.label = ctx.ID().getText();

        String fullText = ctx.getText();
        int start = fullText.indexOf("[");
        int end = fullText.lastIndexOf("]");
        if (start != -1 && end != -1) {
            logic.expression = fullText.substring(start + 1, end).trim();
        }
        return logic;
    }
}