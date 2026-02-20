package org.uet.dse.neo4j.ocl;

import org.tzi.use.uml.ocl.expr.*;

import java.util.Map;

public class Neo4jStepVisitor implements ExpressionVisitor {
    private Neo4jEvalContext context;
    private Object lastResult;

    public Neo4jStepVisitor(Neo4jEvalContext ctx) {
        this.context = ctx;
    }

    @Override
    public void visitAllInstances(ExpAllInstances exp) {
        // OCL: Person.allInstances()
        // Cypher: MATCH (n:Person) RETURN elementId(n)
        String label = exp.getSourceType().name();
        String cypher = "MATCH (n:" + label + ") RETURN elementId(n)";
        lastResult = context.executeList(cypher, Map.of());
    }

    @Override
    public void visitAny(ExpAny exp) {

    }

    @Override
    public void visitAsType(ExpAsType exp) {

    }

    @Override
    public void visitAttrOp(ExpAttrOp exp) {

    }

    @Override
    public void visitBagLiteral(ExpBagLiteral exp) {

    }

    @Override
    public void visitCollect(ExpCollect exp) {

    }

    @Override
    public void visitCollectNested(ExpCollectNested exp) {

    }

    @Override
    public void visitConstBoolean(ExpConstBoolean exp) {

    }

    @Override
    public void visitConstEnum(ExpConstEnum exp) {

    }

    @Override
    public void visitConstInteger(ExpConstInteger exp) {

    }

    @Override
    public void visitConstReal(ExpConstReal exp) {

    }

    @Override
    public void visitConstString(ExpConstString exp) {

    }

    @Override
    public void visitEmptyCollection(ExpEmptyCollection exp) {

    }

    @Override
    public void visitExists(ExpExists exp) {

    }

    @Override
    public void visitForAll(ExpForAll exp) {

    }

    @Override
    public void visitIf(ExpIf exp) {

    }

    @Override
    public void visitIsKindOf(ExpIsKindOf exp) {

    }

    @Override
    public void visitIsTypeOf(ExpIsTypeOf exp) {

    }

    @Override
    public void visitIsUnique(ExpIsUnique exp) {

    }

    @Override
    public void visitIterate(ExpIterate exp) {

    }

    @Override
    public void visitLet(ExpLet exp) {

    }

    @Override
    public void visitNavigation(ExpNavigation exp) {
    }

    @Override
    public void visitObjAsSet(ExpObjAsSet exp) {

    }

    @Override
    public void visitObjOp(ExpObjOp exp) {

    }

    @Override
    public void visitInstanceConstructor(ExpInstanceConstructor exp) {

    }

    @Override
    public void visitObjRef(ExpObjRef exp) {

    }

    @Override
    public void visitOne(ExpOne exp) {

    }

    @Override
    public void visitOrderedSetLiteral(ExpOrderedSetLiteral exp) {

    }

    @Override
    public void visitQuery(ExpQuery exp) {

    }

    @Override
    public void visitReject(ExpReject exp) {

    }

    @Override
    public void visitWithValue(ExpressionWithValue exp) {

    }


    @Override
    public void visitSelect(ExpSelect exp) {

    }

    @Override
    public void visitSequenceLiteral(ExpSequenceLiteral exp) {

    }

    @Override
    public void visitSetLiteral(ExpSetLiteral exp) {

    }

    @Override
    public void visitSortedBy(ExpSortedBy exp) {

    }

    @Override
    public void visitStdOp(ExpStdOp exp) {

    }

    @Override
    public void visitTupleLiteral(ExpTupleLiteral exp) {

    }

    @Override
    public void visitTupleSelectOp(ExpTupleSelectOp exp) {

    }

    @Override
    public void visitUndefined(ExpUndefined exp) {

    }

    @Override
    public void visitVariable(ExpVariable exp) {
        lastResult = context.getVariable(exp.getVarname());
    }

    @Override
    public void visitClosure(ExpClosure exp) {

    }

    @Override
    public void visitOclInState(ExpOclInState exp) {

    }

    @Override
    public void visitVarDeclList(VarDeclList varDeclList) {

    }

    @Override
    public void visitVarDecl(VarDecl varDecl) {

    }

    @Override
    public void visitObjectByUseId(ExpObjectByUseId exp) {

    }

    @Override
    public void visitConstUnlimitedNatural(ExpConstUnlimitedNatural exp) {

    }

    @Override
    public void visitSelectByKind(ExpSelectByKind exp) {

    }

    @Override
    public void visitExpSelectByType(ExpSelectByType exp) {

    }

    @Override
    public void visitRange(ExpRange exp) {

    }

    @Override
    public void visitNavigationClassifierSource(ExpNavigationClassifierSource exp) {

    }

    public Object getResult() { return lastResult; }
}