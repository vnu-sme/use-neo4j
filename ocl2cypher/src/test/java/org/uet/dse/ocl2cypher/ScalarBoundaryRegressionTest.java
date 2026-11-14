package org.uet.dse.ocl2cypher;

import static org.uet.dse.ocl2cypher.SemanticBoundaryFixtures.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.api.FrontendCompiler;
import org.uet.dse.ocl2cypher.api.ValueQueryRequest;
import org.uet.dse.ocl2cypher.core.CoreInterpreter;
import org.uet.dse.ocl2cypher.core.CoreLowering;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.Realization;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.qcyp.QCypTranslator;
import org.uet.dse.ocl2cypher.runtime.Boolean3;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.model.UmlClass;

/** Semantic boundary checks grouped by responsibility. */
class ScalarBoundaryRegressionTest {

    @Test
    void storedIntegerAttributeUsesSnapshotRangeForArithmetic() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person"))
                .attribute(org.uet.dse.ocl2cypher.source.model.UmlAttribute.of(
                        "Person", "age", OclType.INTEGER)).build();
        Snapshot safe = Snapshot.builder().object("p", "Person")
                .attribute("p", "age", new OclValue.IntegerValue(BigInteger.valueOf(40)))
                .build();
        Snapshot overflow = Snapshot.builder().object("p", "Person")
                .attribute("p", "age", new OclValue.IntegerValue(
                        BigInteger.valueOf(Long.MAX_VALUE)))
                .build();
        // The concrete snapshot supplies the leaf interval [40,40].
        assertNotNull(realizeViolationText(
                "context Person inv AgePositive: self.age > 0", sm, safe));
        // Safe snapshot arithmetic succeeds; the MAX_VALUE snapshot still
        // rejects because its derived result interval exceeds signed INT64.
        assertNotNull(realizeViolationText(
                "context Person inv AgeSum: self.age + 1 > 0", sm, safe));
        expectNumericRejection(
                "context Person inv AgeSum: self.age + 1 > 0", sm, overflow);
    }

    @Test
    void numericVariableRealizationIsCertifiedAndSucceeds() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();
        // Literal evidence is not yet transported through declaration bindings.
        assertNotNull(realizeViolationText("context Person inv Local: let x: Integer = 1 in x > 0", sm, sn));
    }

    @Test
    void exactRealArithmeticAndLetBindingsAreCertified() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();
        assertNotNull(realizeViolationText(
                "context Person inv RealCast: 1.0 + 2.0 > 0.0", sm, sn));
        assertNotNull(realizeViolationText(
                "context Person inv RealLet: let x: Real = 0.5 in x > 0.0", sm, sn));
        expectRealRejection(
                "context Person inv RoundedDivision: 1.0 / 10.0 > 0.0", sm, sn);
    }

    @Test
    void storedRealComparisonRequiresExactBinary64LeafCertificate() {
        SchemaModel sm = SchemaModel.builder("real-leaf")
                .clazz(UmlClass.of("Person"))
                .attribute(UmlAttribute.of("Person", "rate", OclType.REAL))
                .build();
        Snapshot exact = Snapshot.builder().object("p", "Person")
                .attribute("p", "rate", new OclValue.RealValue(
                        new java.math.BigDecimal("0.5"))).build();
        Snapshot rounded = Snapshot.builder().object("p", "Person")
                .attribute("p", "rate", new OclValue.RealValue(
                        new java.math.BigDecimal("0.1"))).build();
        Snapshot collapsed = Snapshot.builder().object("p", "Person")
                .attribute("p", "rate", new OclValue.RealValue(
                        new java.math.BigDecimal("9007199254740993"))).build();

        assertNotNull(realizeViolationText(
                "context Person inv Exact: self.rate >= 0.5", sm, exact));
        expectRealRejection(
                "context Person inv Rounded: self.rate >= 0.1", sm, rounded);
        expectRealRejection(
                "context Person inv Collapse: self.rate = 9007199254740992.0", sm,
                collapsed);
    }

    @Test
    void storedRealArithmeticRequiresExactInputsAndIntermediateResults() {
        SchemaModel sm = SchemaModel.builder("real-arithmetic")
                .clazz(UmlClass.of("Account"))
                .attribute(UmlAttribute.of("Account", "balance", OclType.REAL))
                .attribute(UmlAttribute.of("Account", "credit", OclType.REAL))
                .build();
        Snapshot safe = Snapshot.builder().object("a", "Account")
                .attribute("a", "balance", new OclValue.RealValue(new BigDecimal("0.5")))
                .attribute("a", "credit", new OclValue.RealValue(new BigDecimal("0.25")))
                .build();
        Snapshot roundedIntermediate = Snapshot.builder().object("a", "Account")
                .attribute("a", "balance", new OclValue.RealValue(
                        new BigDecimal("9007199254740992")))
                .attribute("a", "credit", new OclValue.RealValue(BigDecimal.ONE))
                .build();

        assertNotNull(realizeViolationText(
                "context Account inv ExactTotal: self.balance + self.credit = 0.75",
                sm, safe));
        expectRealRejection(
                "context Account inv RoundedTotal: self.balance + self.credit > 0.0",
                sm, roundedIntermediate);
    }

    @Test
    void booleanThreeValuedTableIsTotalAndUsesKleeneDominance() {
        assertTrue(Boolean3.tableCompleteAndSound());
        assertTrue(Boolean3.BOTTOM.isBottom());
        assertTrue(Boolean3.BOTTOM instanceof OclValue.BottomValue);
        assertSame(Boolean3.FALSE, Boolean3.and(Boolean3.FALSE, Boolean3.BOTTOM));
        assertSame(Boolean3.TRUE, Boolean3.implies(Boolean3.FALSE, Boolean3.BOTTOM));
        assertSame(Boolean3.BOTTOM, Boolean3.implies(Boolean3.TRUE, Boolean3.BOTTOM));
    }

    @Test
    void booleanBottomRemainsBottomWhenUsedAsAnIfCondition() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::flag", "flag", "Person", OclType.BOOLEAN))
                .build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();
        String source = "context Person inv BottomCondition: "
                + "if self.flag then false else true endif";

        assertEquals(List.of("p"), org.uet.dse.ocl2cypher.api.CoreOracle
                .violationsOclEq(source, sm, sn));
        var parsed = FrontendCompiler.compile(source, sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var translated = QCypTranslator.translate(lowered.value());
        assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
        GraphModel graph = GraphBuilder.build(sm, sn).value().graph();
        CoreInterpreter.Env env = new CoreInterpreter.Env();
        env.bind(lowered.value().selfVariable(),
                new OclValue.ObjectValue(OclType.clazz("Person"), "p"));
        assertTrue(org.uet.dse.ocl2cypher.qcyp.QInterpreter.evalExpr(
                sm, graph, env, translated.value().expressionBody()).isBottom());
    }

    @Test
    void certifiedAbsSucceedsWhileFloorAndRoundStillRequireCertificates() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String abs = realizeViolationText(
                "context Person inv Abs: (-2).abs() = 2", sm, sn);
        assertTrue(abs.contains("abs("), abs);
        expectNumericRejection(
                "context Person inv Floor: (-1.2).floor() = -2", sm, sn);
        expectNumericRejection(
                "context Person inv Round: (-1.5).round() = -1", sm, sn);
        assertEquals(List.of(), org.uet.dse.ocl2cypher.api.CoreOracle.violationsOclEq(
                "context Person inv Round: (-1.5).round() = -1", sm, sn));
    }

    @Test
    void certifiedLiteralArithmeticPreservesExpectedCoreAndQResults() {
        SchemaModel sm = SchemaModel.builder("certified-literals").clazz(UmlClass.of("Person")).build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();
        for (String body : List.of("(-7) div 3 = -2", "(-7) mod 3 = -1",
                "Bag{2, 2, -1}->sum() = 3", "Set{2, 2, -1}->sum() = 1",
                "(-2).abs() = 2", "2.min(3) = 2", "2.max(3) = 3")) {
            String source = "context Person inv Certified: " + body;
            assertEquals(List.of(), org.uet.dse.ocl2cypher.api.CoreOracle.violationsOclEq(source, sm, sn));
            var fe = FrontendCompiler.compile(source, sm);
            assertTrue(fe.isSuccess(), () -> fe.diagnostics().toString());
            var doc = fe.value().get(0);
            var core = CoreLowering.lower(sm, doc, doc.constraints.get(0));
            assertTrue(core.isSuccess(), () -> core.diagnostics().toString());
            var q = QCypTranslator.translate(core.value());
            assertTrue(q.isSuccess(), () -> q.diagnostics().toString());
            var graph = GraphBuilder.build(sm, sn);
            assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
            assertEquals(List.of(), org.uet.dse.ocl2cypher.qcyp.QInterpreter.violations(
                    sm, graph.value().graph(), core.value(), q.value()), source);
            org.uet.dse.ocl2cypher.cypher.Neo4jCypherParserGate.assertParses(realizeViolationText(source, sm, sn));
        }
    }

    @Test
    void storedIntegerAttributeIsCertifiedAndRealizes() {
        var schema = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(UmlAttribute.of("Person", "age", OclType.INTEGER)).build();
        var sn = Snapshot.builder()
                .object("p", "Person")
                .attribute("p", "age", new OclValue.IntegerValue(BigInteger.valueOf(42))).build();
        var graph = GraphBuilder.build(schema, sn);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());

        var fe = FrontendCompiler.compileValueQuery(
                ValueQueryRequest.contextual("self.age", "Person"), schema);
        assertTrue(fe.isSuccess(), () -> fe.diagnostics().toString());
        var lowered = CoreLowering.lowerValueQuery(schema, fe.value());
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var q = QCypTranslator.translate(lowered.value());
        assertTrue(q.isSuccess(), () -> q.diagnostics().toString());
        var r = Realization.realize(q.value(), graph.value().graph(), CypherAst.Dialect.CYPHER_5);
        assertTrue(r.isSuccess(), () -> r.diagnostics().toString());
        assertEquals(CypherAst.ResultShape.SCALAR, r.value().contract().shape());
    }

    @Test
    void storedIntegerAttributeRequiresConcreteInt64PayloadCertificate() {
        var schema = SchemaModel.builder("numeric-domain")
                .clazz(UmlClass.of("Person"))
                .attribute(UmlAttribute.of("Person", "age", OclType.INTEGER)).build();
        for (BigInteger value : List.of(BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE))) {
            var snapshot = Snapshot.builder().object("p", "Person")
                    .attribute("p", "age", new OclValue.IntegerValue(value)).build();
            if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                assertNotNull(realizeViolationText(
                        "context Person inv Positive: self.age > 0", schema, snapshot));
            } else {
                expectNumericRejection(
                        "context Person inv Positive: self.age > 0", schema, snapshot);
            }
        }
    }
}
