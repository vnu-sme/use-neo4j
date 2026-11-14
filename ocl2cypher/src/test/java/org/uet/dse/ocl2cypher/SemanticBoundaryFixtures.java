package org.uet.dse.ocl2cypher;

import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.uet.dse.ocl2cypher.api.FrontendCompiler;
import org.uet.dse.ocl2cypher.core.CoreLowering;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.cypher.Realization;
import org.uet.dse.ocl2cypher.cypher.Serializer;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.qcyp.QCypTranslator;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.QualifierValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlClass;
import org.uet.dse.ocl2cypher.source.model.UmlQualifier;

/** Semantic boundary checks grouped by responsibility. */
final class SemanticBoundaryFixtures {
    private SemanticBoundaryFixtures() { }

    static OclValue.BottomValue elementBottomValue() {
        return new OclValue.BottomValue(OclType.INTEGER);
    }

    static String realizeViolationText(String source, SchemaModel sm, Snapshot sn) {
        var parsed = FrontendCompiler.compile(source, sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var translated = QCypTranslator.translate(lowered.value());
        assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
        var graph = GraphBuilder.build(sm, sn);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
        var realized = Realization.realize(translated.value(), graph.value().graph(),
                CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        return Serializer.cypherText(realized.value());
    }

    /**
     * For inputs intentionally outside the certified numeric realization
     * domain.
     */
    static void expectNumericRejection(String source, SchemaModel sm, Snapshot sn) {
        var parsed = FrontendCompiler.compile(source, sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var translated = QCypTranslator.translate(lowered.value());
        assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
        var graph = GraphBuilder.build(sm, sn);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
        assertEquals(new java.util.TreeSet<>(org.uet.dse.ocl2cypher.api.CoreOracle.violationsOclEq(source, sm, sn)),
                new java.util.TreeSet<>(QInterpreter.violations(sm, graph.value().graph(), lowered.value(), translated.value())), source);
        var realized = Realization.realize(translated.value(), graph.value().graph(),
                CypherAst.Dialect.CYPHER_5);
        assertFalse(realized.isSuccess(), () -> "expected certified numeric rejection: "
                + realized.diagnostics());
        assertEquals("R_NUMERIC_CAPABILITY", realized.primaryDiagnostic().code(),
                () -> realized.diagnostics().toString());
        assertEquals(org.uet.dse.ocl2cypher.diagnostics.Stage.R, realized.primaryDiagnostic().stage());
    }

    static void expectRealRejection(String source, SchemaModel sm, Snapshot sn) {
        var parsed = FrontendCompiler.compile(source, sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var translated = QCypTranslator.translate(lowered.value());
        assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
        var graph = GraphBuilder.build(sm, sn);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
        assertEquals(new java.util.TreeSet<>(org.uet.dse.ocl2cypher.api.CoreOracle.violationsOclEq(source, sm, sn)),
                new java.util.TreeSet<>(QInterpreter.violations(sm, graph.value().graph(), lowered.value(), translated.value())), source);
        var realized = Realization.realize(translated.value(), graph.value().graph(), CypherAst.Dialect.CYPHER_5);
        assertFalse(realized.isSuccess(), () -> realized.diagnostics().toString());
        assertEquals("R-REAL-EXACT-UNSUPPORTED", realized.primaryDiagnostic().code());
        assertEquals(org.uet.dse.ocl2cypher.diagnostics.Stage.R, realized.primaryDiagnostic().stage());
    }

    static SchemaModel qualifiedSchema(boolean unique) {
        return SchemaModel.builder("m")
                .clazz(UmlClass.of("Company"))
                .clazz(UmlClass.of("Employee"))
                .association(new UmlAssociation("employment-key", "employment",
                        "Company", "employer", 0, -1,
                        "Employee", "employees", 0, -1,
                        List.of(new UmlQualifier("slot", OclType.INTEGER, List.of(
                                new OclValue.IntegerValue(BigInteger.ONE),
                                new OclValue.IntegerValue(BigInteger.TWO)))), false, unique))
                .build();
    }

    static SchemaModel qualifiedSchemaWithBounds(boolean unique,
            int sourceLower,
            int targetLower,
            UmlQualifier qualifier) {
        return SchemaModel.builder("m")
                .clazz(UmlClass.of("Company"))
                .clazz(UmlClass.of("Employee"))
                .association(new UmlAssociation("employment-key", "employment",
                        "Company", "employer", sourceLower, -1,
                        "Employee", "employees", targetLower, -1,
                        List.of(qualifier), false, unique))
                .build();
    }

    static UmlQualifier slotDomain() {
        return new UmlQualifier("slot", OclType.INTEGER, List.of(
                new OclValue.IntegerValue(BigInteger.ONE),
                new OclValue.IntegerValue(BigInteger.TWO)));
    }

    static Snapshot qualifiedSnapshot() {
        return Snapshot.builder()
                .object("c", "Company")
                .object("e1", "Employee")
                .object("e2", "Employee")
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot", new OclValue.IntegerValue(BigInteger.ONE))))
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot", new OclValue.IntegerValue(BigInteger.ONE))))
                .link("employment", "c", "e2",
                        List.of(new QualifierValue("slot", new OclValue.IntegerValue(BigInteger.TWO))))
                .build();
    }

    static Snapshot uniqueQualifiedSnapshot() {
        return Snapshot.builder()
                .object("c", "Company")
                .object("e1", "Employee")
                .object("e2", "Employee")
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot", new OclValue.IntegerValue(BigInteger.ONE))))
                .link("employment", "c", "e2",
                        List.of(new QualifierValue("slot", new OclValue.IntegerValue(BigInteger.TWO))))
                .build();
    }

    static void assertInstanceOfBinary(QNode.QExpr expression) {
        assertTrue(expression instanceof QNode.QExpr.Binary,
                () -> "expected canonical Binary, found " + expression.getClass().getSimpleName());
    }
}
