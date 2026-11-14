package org.uet.dse.ocl2cypher;

import static org.uet.dse.ocl2cypher.SemanticBoundaryFixtures.*;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.api.FrontendCompiler;
import org.uet.dse.ocl2cypher.core.CoreDeclaration;
import org.uet.dse.ocl2cypher.core.CoreExpr;
import org.uet.dse.ocl2cypher.core.CoreInterpreter;
import org.uet.dse.ocl2cypher.core.CoreLowering;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.Realization;
import org.uet.dse.ocl2cypher.cypher.Serializer;
import org.uet.dse.ocl2cypher.diagnostics.SourceSpan;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.qcyp.QCypTranslator;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.qcyp.QQuery;
import org.uet.dse.ocl2cypher.runtime.Boolean3;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlClass;

/** Semantic boundary checks grouped by responsibility. */
class CollectionBoundaryRegressionTest {

    @Test
    void distinctBagHasSetTypeAndRemovesDuplicatesWithoutRealization() {
        var span = SourceSpan.UNKNOWN;
        var one = new QNode.QExpr.Constant(span, OclType.INTEGER, BigInteger.ONE);
        var two = new QNode.QExpr.Constant(span, OclType.INTEGER, BigInteger.TWO);
        var bag = new QNode.QExpr.CollectionLiteral(span, CoreExpr.CollectionKind.BAG,
                List.of(one, one, two), OclType.bag(OclType.INTEGER));
        var distinct = new QNode.QPlan.Distinct(span, new QNode.QPlan.FromCollection(span, bag));
        assertEquals(OclType.set(OclType.INTEGER), distinct.type);
        var sm = SchemaModel.builder("distinct").build();
        var graph = new GraphModel("distinct");
        var env = new CoreInterpreter.Env();
        var items = QInterpreter.evalPlan(sm, graph, env, distinct);
        assertEquals(List.of(new OclValue.IntegerValue(BigInteger.ONE),
                new OclValue.IntegerValue(BigInteger.TWO)), items);
        var materialized = QInterpreter.evalExpr(sm, graph, env,
                new QNode.QExpr.Materialize(span, distinct));
        assertEquals(OclType.set(OclType.INTEGER), materialized.type());
        assertEquals(items, QInterpreter.evalPlan(sm, graph, env,
                new QNode.QPlan.Distinct(span, distinct)));

        var empty = new QNode.QExpr.CollectionLiteral(span, CoreExpr.CollectionKind.BAG,
                List.of(), OclType.bag(OclType.INTEGER));
        var emptyDistinct = new QNode.QPlan.Distinct(span,
                new QNode.QPlan.FromCollection(span, empty));
        assertEquals(List.of(), QInterpreter.evalPlan(sm, graph, env, emptyDistinct));

        var elementBottom = new QNode.QExpr.Bottom(span, OclType.INTEGER);
        var bottomBag = new QNode.QExpr.CollectionLiteral(span, CoreExpr.CollectionKind.BAG,
                List.of(elementBottom), OclType.bag(OclType.INTEGER));
        var bottomDistinct = new QNode.QPlan.Distinct(span,
                new QNode.QPlan.FromCollection(span, bottomBag));
        assertEquals(List.of(elementBottomValue()),
                QInterpreter.evalPlan(sm, graph, env, bottomDistinct));

        var wholeBottom = new QNode.QPlan.Distinct(span,
                new QNode.QPlan.FromCollection(span,
                        new QNode.QExpr.Bottom(span, OclType.bag(OclType.INTEGER))));
        assertNull(QInterpreter.evalPlan(sm, graph, env, wholeBottom));
    }

    @Test
    void collectionSizeHasCardinalityCertificateAndRealizes() {
        var sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        var sn = Snapshot.builder().object("p", "Person").build();
        String text = realizeViolationText(
                "context Person inv BagSize: Bag{1, 1}->size() = 2", sm, sn);
        assertTrue(text.contains("size("), text);
    }

    @Test
    void setAlgebraNowHasACompleteSourceToRealizationPath() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age", "age", "Person", OclType.INTEGER))
                .build();
        var parsed = FrontendCompiler.compile(
                "context Person inv Bad: Set{1}->union(Set{2})->includes(1)", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        expectNumericRejection("context Person inv Union: Set{1}->union(Set{2})->includes(1)",
                sm, Snapshot.builder().object("p", "Person").build());
        String text = realizeViolationText(
                "context Person inv Union: Set{'a'}->union(Set{'b'})->includes('a')",
                sm, Snapshot.builder().object("p", "Person").build());
        assertTrue(text.contains("COLLECT {UNWIND"), text);
        assertTrue(text.contains("RETURN DISTINCT"), text);
    }

    @Test
    void includesAllExcludesAllAndIntersectionUseTypedQuantification() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String includesAll = realizeViolationText(
                "context Person inv AllPresent: Set{1, 2}->includesAll(Set{2})", sm, sn);
        String excludesAll = realizeViolationText(
                "context Person inv AllAbsent: Bag{1, 1}->excludesAll(Bag{2, 2})", sm, sn);
        expectNumericRejection(
                "context Person inv Intersect: Set{1, 2}->intersection(Set{2})->size() = 1",
                sm, sn);
        String intersection = realizeViolationText(
                "context Person inv Intersect: Set{'a', 'b'}->intersection(Set{'b'})->notEmpty()", sm, sn);

        assertTrue(includesAll.contains("all(`membershipItem"), includesAll);
        assertTrue(excludesAll.contains("all(`membershipItem"), excludesAll);
        assertTrue(intersection.contains("intersectionItem"), intersection);
        assertTrue(intersection.contains("COLLECT {UNWIND"), intersection);
    }

    @Test
    void collectionMembershipCoercesNumericElementTypesConsistently() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();
        var parsed = FrontendCompiler.compile(
                "context Person inv NumericMember: Set{1}->includes(1.0)", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
    }

    @Test
    void emptyCollectionLiteralUsesExpectedTypesButRejectsUninferredUse() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        expectNumericRejection(
                "context Person inv EmptyLet: "
                + "let xs : Set(Integer) = Set{} in xs->isEmpty()",
                sm, sn);
        String letText = realizeViolationText("context Person inv EmptyLet: "
                + "let xs : Set(String) = Set{} in xs->isEmpty()", sm, sn);
        expectNumericRejection(
                "context Person inv EmptyEq: Set{} = Set{1}->reject(x | true)", sm, sn);
        // Empty literal has no uncertified elements; member 1 has an I64 certificate.
        String numericMembership = realizeViolationText(
                "context Person inv EmptyMember: Set{}->excludes(1)", sm, sn);
        assertTrue(numericMembership.contains("UNWIND []"), numericMembership);
        String equalityText = realizeViolationText(
                "context Person inv EmptyEq: Set{} = Set{'a'}->reject(x | true)", sm, sn);
        String membershipText = realizeViolationText(
                "context Person inv EmptyMember: Set{}->excludes('a')", sm, sn);

        assertTrue(letText.contains("UNWIND []"), letText);
        assertTrue(equalityText.contains("UNWIND []"), equalityText);
        assertTrue(membershipText.contains("UNWIND []"), membershipText);
        assertEquals(List.of(), org.uet.dse.ocl2cypher.api.CoreOracle.violationsOclEq(
                "context Person inv EmptyLet: "
                + "let xs : Set(Integer) = Set{} in xs->isEmpty()", sm, sn));

        var uninferred = FrontendCompiler.compile(
                "context Person inv UnknownEmpty: Set{}->isEmpty()", sm);
        assertTrue(uninferred.isFailure());
        assertEquals("E_UNINFERRED_EMPTY_COLLECTION_TYPE",
                uninferred.primaryDiagnostic().code());
    }

    @Test
    void collectionLiteralCoercesEveryElementToItsJoinType() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        assertNotNull(realizeViolationText(
                "context Person inv NumericJoin: Set{1, 2.0}->includes(1.0)", sm,
                Snapshot.builder().object("p", "Person").build()));
    }

    @Test
    void collectPlanCanBeMaterializedBeforeACollectionOperation() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Company"))
                .clazz(UmlClass.of("Employee"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Employee::age", "age", "Employee", OclType.INTEGER))
                .association(new UmlAssociation("employment-key", "employment",
                        "Company", "employer", 0, 1,
                        "Employee", "employees", 0, -1,
                        List.of(), false, true))
                .build();
        var parsed = FrontendCompiler.compile(
                "context Company inv HasAges: self.employees->collect(e | e.age)->size() > 0", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var translated = QCypTranslator.translate(lowered.value());
        assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
        var graph = GraphBuilder.build(sm, Snapshot.builder().object("c", "Company").build());
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
        var realized = Realization.realize(translated.value(), graph.value().graph(),
                CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isFailure());
        assertEquals("R_NUMERIC_CAPABILITY", realized.primaryDiagnostic().code());
        expectNumericRejection("context Company inv HasAges: self.employees->collect(e | e.age)->size() > 0",
                sm, Snapshot.builder().object("c", "Company").build());
        String text = realizeViolationText("context Company inv Collected: self.employees->collect(e | true)->notEmpty()",
                sm, Snapshot.builder().object("c", "Company").build());
        assertTrue(text.contains("__oclItems"), text);
        assertTrue(text.contains("COLLECT"), text);
    }

    @Test
    void includesAndExcludesReachRealizationThroughTheCanonicalBinaryPath() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String includes = realizeViolationText(
                "context Person inv Member: Set{1, 2}->includes(1)", sm, sn);
        String excludes = realizeViolationText(
                "context Person inv Absent: Bag{1, 2}->excludes(3)", sm, sn);

        assertTrue(includes.contains("any(`x"), includes);
        assertTrue(excludes.contains("any(`x"), excludes);
        assertFalse(includes.contains("any(["), includes);
        assertFalse(excludes.contains("any(["), excludes);
    }

    @Test
    void setLiteralDeduplicatesButBagLiteralKeepsOccurrences() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String setSizeText = realizeViolationText(
                "context Person inv SetSize: Set{1, 1}->size() = 1", sm, sn);
        String bagSizeText = realizeViolationText(
                "context Person inv BagSize: Bag{1, 1}->size() = 2", sm, sn);
        assertTrue(setSizeText.contains("COLLECT {UNWIND"), setSizeText);
        assertFalse(bagSizeText.contains("COLLECT {UNWIND"), bagSizeText);
        String setText = realizeViolationText("context Person inv SetMembers: Set{1,1}->notEmpty()", sm, sn);
        String bagText = realizeViolationText("context Person inv BagMembers: Bag{1,1}->notEmpty()", sm, sn);

        assertTrue(setText.contains("COLLECT {UNWIND"), setText);
        assertTrue(setText.contains("RETURN DISTINCT"), setText);
        assertFalse(bagText.contains("COLLECT {UNWIND"), bagText);
    }

    @Test
    void collectionEqualityUsesSetExtensionalityAndBagMultiplicity() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String setText = realizeViolationText(
                "context Person inv SetEq: Set{1, 2} = Set{2, 1}", sm, sn);
        String bagText = realizeViolationText(
                "context Person inv BagEq: Bag{1, 1} <> Bag{1}", sm, sn);

        assertTrue(setText.contains("all(`leftMember"), setText);
        assertTrue(setText.contains("all(`rightMember"), setText);
        assertTrue(bagText.contains("all(`bagMember"), bagText);
        assertTrue(bagText.contains("size("), bagText);
    }

    @Test
    void directQBottomCoerceDistinctAndPlanLetHaveRealizationRules() {
        SourceSpan span = SourceSpan.UNKNOWN;
        CoreDeclaration self = new CoreDeclaration(1, "self", CoreDeclaration.Kind.SELF,
                OclType.clazz("Person"));
        CoreDeclaration local = new CoreDeclaration(2, "x", CoreDeclaration.Kind.LET,
                OclType.INTEGER);
        QNode.QExpr one = new QNode.QExpr.Constant(span, OclType.INTEGER, BigInteger.ONE);
        QNode.QExpr duplicateBag = new QNode.QExpr.CollectionLiteral(span,
                CoreExpr.CollectionKind.BAG, List.of(one, one), OclType.bag(OclType.INTEGER));
        QNode.QPlan distinct = new QNode.QPlan.Distinct(span,
                new QNode.QPlan.FromCollection(span, duplicateBag));
        QNode.QExpr coerced = new QNode.QExpr.Coerce(span,
                CoreExpr.CoercionKind.INTEGER_TO_REAL, OclType.INTEGER, one, OclType.REAL);
        QNode.QPlan letPlan = new QNode.QPlan.PlanLet(span, local, one, distinct);
        QNode.QExpr materialized = new QNode.QExpr.Materialize(span, letPlan);
        QNode.QExpr size = new QNode.QExpr.Unary(span, CoreExpr.UnaryOp.COLLECTION_SIZE,
                materialized, OclType.INTEGER);
        QNode.QExpr body = new QNode.QExpr.Binary(span, CoreExpr.BinaryOp.VALUE_EQUAL,
                size, one, OclType.BOOLEAN);
        QQuery query = new QQuery(body, null, QQuery.QResultShape.IDS,
                QQuery.QueryMode.VIOLATIONS, null, "Person", self, true);
        GraphModel graph = GraphBuilder.build(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().object("p", "Person").build()).value().graph();

        var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isFailure());
        assertEquals("R_NUMERIC_CAPABILITY", realized.primaryDiagnostic().code());
        assertEquals(new OclValue.IntegerValue(BigInteger.ONE), QInterpreter.evalExpr(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(), graph,
                new CoreInterpreter.Env(), size));
        assertEquals(OclType.set(OclType.INTEGER), materialized.type);

        QQuery bottomQuery = new QQuery(new QNode.QExpr.Bottom(span, OclType.BOOLEAN), null,
                QQuery.QResultShape.IDS, QQuery.QueryMode.VIOLATIONS, null,
                "Person", self, true);
        var bottomRealized = Realization.realize(bottomQuery, graph,
                CypherAst.Dialect.CYPHER_5);
        assertTrue(bottomRealized.isSuccess(), () -> bottomRealized.diagnostics().toString());
        assertTrue(Serializer.cypherText(bottomRealized.value())
                .contains("`__oclBottom`: true"));

        QNode.QExpr coerceEquality = new QNode.QExpr.Binary(span,
                CoreExpr.BinaryOp.VALUE_EQUAL, coerced,
                new QNode.QExpr.Constant(span, OclType.REAL, java.math.BigDecimal.ONE),
                OclType.BOOLEAN);
        QQuery coerceQuery = new QQuery(coerceEquality, null, QQuery.QResultShape.IDS,
                QQuery.QueryMode.VIOLATIONS, null, "Person", self, true);
        var coerceRealized = Realization.realize(coerceQuery, graph,
                CypherAst.Dialect.CYPHER_5);
        assertTrue(coerceRealized.isSuccess(), () -> coerceRealized.diagnostics().toString());
        assertEquals(Boolean3.TRUE, QInterpreter.evalExpr(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(), graph,
                new CoreInterpreter.Env(), coerceEquality));
    }

    @Test
    void stringPlanLetDistinctMaterializesWithSupportAndRealizes() {
        var span = SourceSpan.UNKNOWN;
        var sm = SchemaModel.builder("m").build();
        var graph = new GraphModel("m");
        var local = new CoreDeclaration(2, "x", CoreDeclaration.Kind.LET, OclType.STRING);
        var item = new QNode.QExpr.Constant(span, OclType.STRING, "a");
        var variable = new QNode.QExpr.Variable(span, local);
        var bag = new QNode.QExpr.CollectionLiteral(span, CoreExpr.CollectionKind.BAG,
                List.of(variable, variable), OclType.bag(OclType.STRING));
        var plan = new QNode.QPlan.PlanLet(span, local, item,
                new QNode.QPlan.Distinct(span, new QNode.QPlan.FromCollection(span, bag)));
        var materialized = new QNode.QExpr.Materialize(span, plan);
        assertEquals(new OclValue.SetValue(OclType.set(OclType.STRING),
                List.of(new OclValue.StringValue("a"))),
                QInterpreter.evalExpr(sm, graph, new CoreInterpreter.Env(), materialized));
        var query = new QQuery(null, plan, QQuery.QResultShape.SET,
                QQuery.QueryMode.VALUE, plan.type, null, null, false);
        var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        assertTrue(Serializer.cypherText(realized.value()).contains("COLLECT {UNWIND"));
        assertEquals(CypherAst.ResultShape.SET, realized.value().contract().shape());
    }

    @Test
    void directLegacyCollectionConstructorsHaveRealizationRules() {
        SourceSpan span = SourceSpan.UNKNOWN;
        CoreDeclaration self = new CoreDeclaration(1, "self", CoreDeclaration.Kind.SELF,
                OclType.clazz("Person"));
        QNode.QExpr one = new QNode.QExpr.Constant(span, OclType.INTEGER, BigInteger.ONE);
        QNode.QExpr set = new QNode.QExpr.CollectionLiteral(span,
                CoreExpr.CollectionKind.SET, List.of(one), OclType.set(OclType.INTEGER));
        QNode.QExpr all = new QNode.QExpr.IncludesFamily(span, QNode.QKind.INCLUDES_ALL,
                set, set, OclType.BOOLEAN);
        QNode.QExpr union = new QNode.QExpr.SetAlgebra(span,
                CoreExpr.BinaryOp.SET_UNION, set, set, OclType.set(OclType.INTEGER));
        QNode.QExpr sum = new QNode.QExpr.CountFamily(span, QNode.QKind.SUM, set,
                null, OclType.INTEGER);
        GraphModel graph = GraphBuilder.build(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().object("p", "Person").build()).value().graph();

        QNode.QExpr unionCheck = new QNode.QExpr.Binary(span,
                CoreExpr.BinaryOp.VALUE_EQUAL, union, set, OclType.BOOLEAN);
        QNode.QExpr sumCheck = new QNode.QExpr.Binary(span,
                CoreExpr.BinaryOp.VALUE_EQUAL, sum, one, OclType.BOOLEAN);
        for (QNode.QExpr expression : List.of(all, unionCheck, sumCheck)) {
            QQuery query = new QQuery(expression, null, QQuery.QResultShape.IDS,
                    QQuery.QueryMode.VIOLATIONS, null, "Person", self, true);
            var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
            assertEquals(Boolean3.TRUE, QInterpreter.evalExpr(
                    SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(), graph,
                    new CoreInterpreter.Env(), expression));
            if (expression == all || expression == sumCheck) {
                assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
                assertTrue(Serializer.cypherText(realized.value()).contains(
                        expression == all ? "all(" : "reduce("));
            } else {
                assertTrue(realized.isFailure());
                assertEquals("R_NUMERIC_CAPABILITY", realized.primaryDiagnostic().code());
                assertEquals(org.uet.dse.ocl2cypher.diagnostics.Stage.R, realized.primaryDiagnostic().stage());
            }
        }
    }

    @Test
    void valuePlanWholeBottomCarriesTheCollectionTypeTag() {
        SourceSpan span = SourceSpan.UNKNOWN;
        OclType setOfInteger = OclType.set(OclType.INTEGER);
        QNode.QPlan plan = new QNode.QPlan.FromCollection(span,
                new QNode.QExpr.Bottom(span, setOfInteger));
        QQuery query = new QQuery(null, plan, QQuery.QResultShape.SET,
                QQuery.QueryMode.VALUE, setOfInteger, null, null, false);
        GraphModel graph = GraphBuilder.build(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().build()).value().graph();

        var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        String text = Serializer.cypherText(realized.value());
        assertTrue(text.contains("THEN 'Set<Integer>' ELSE 'Integer'"), text);
    }
}
