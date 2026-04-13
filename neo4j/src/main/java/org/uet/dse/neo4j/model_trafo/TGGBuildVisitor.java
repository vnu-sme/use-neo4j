package org.uet.dse.neo4j.model_trafo;

import java.util.ArrayList;
import org.uet.dse.neo4j.TGGBaseVisitor;
import org.uet.dse.neo4j.TGGBaseVisitor.*;
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
        rule.source = parseBlock(ctx.sourceBlock().listElements(0), ctx.sourceBlock().listElements(1));
        rule.target = parseBlock(ctx.targetBlock().listElements(0), ctx.targetBlock().listElements(1));
        rule.correspondence = parseBlock(ctx.corrBlock().listElements(0), ctx.corrBlock().listElements(1));
        return rule;
    }

    private TGGBlock parseBlock(TGGParser.ListElementsContext contextCtx, TGGParser.ListElementsContext produceCtx) {
        TGGBlock block = new TGGBlock();
        if (contextCtx != null) {
            for (TGGParser.ElementContext e : contextCtx.element()) {
                Object result = visit(e);
                if (result instanceof TGGElement) {
                    block.context.add((TGGElement) result);
                }
            }
        }
        if (produceCtx != null) {
            for (TGGParser.ElementContext e : produceCtx.element()) {
                Object result = visit(e);
                if (result instanceof TGGElement) {
                    block.produce.add((TGGElement) result);
                }
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
        // take nodeA, nodeB (maybe  *)
        corr.nodeA = ctx.idWithStar(0).getText();
        corr.nodeB = ctx.idWithStar(1).getText();

        // alias info
        corr.aliasA = ctx.ID(0).getText();
        corr.aliasB = ctx.ID(1).getText();
        corr.corrName = ctx.ID(2).getText();
        corr.corrType = ctx.ID(3).getText();
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