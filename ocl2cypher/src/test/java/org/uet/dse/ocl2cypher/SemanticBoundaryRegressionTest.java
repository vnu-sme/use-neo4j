package org.uet.dse.ocl2cypher;

import org.uet.dse.ocl2cypher.support.CapabilityEvidence;

import static org.uet.dse.ocl2cypher.SemanticBoundaryFixtures.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.uet.dse.ocl2cypher.graph.GraphKey;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphObservation;
import org.uet.dse.ocl2cypher.qcyp.NormQ;
import org.uet.dse.ocl2cypher.qcyp.QCypTranslator;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.qcyp.QQuery;
import org.uet.dse.ocl2cypher.runtime.Boolean3;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.CapabilityMatrix;
import org.uet.dse.ocl2cypher.source.model.QualifierValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlClass;
import org.uet.dse.ocl2cypher.source.model.UmlQualifier;

/**
 * Regression checks for the high-risk semantic and representation boundaries.
 */
class SemanticBoundaryRegressionTest {

    @Test
    void reverseAndQualifiedNavigationUseTheDeclaredAssociation() {
        SchemaModel sm = qualifiedSchema(false);
        Snapshot sn = qualifiedSnapshot();
        GraphModel g = GraphBuilder.build(sm, sn).value().graph();
        OclValue.IntegerValue one = new OclValue.IntegerValue(BigInteger.ONE);

        assertEquals(List.of("e1", "e1"),
                GraphObservation.linkTargets(g, sm, "c", "employees", false, List.of(one)));
        assertEquals(List.of("c", "c"),
                GraphObservation.linkTargets(g, sm, "e1", "employer", true, List.of()));
    }

    @Test
    void bagNavigationPreservesDuplicateOccurrencesAndSetNavigationDeduplicates() {
        Snapshot sn = qualifiedSnapshot();
        GraphModel bagGraph = GraphBuilder.build(qualifiedSchema(false), sn).value().graph();
        Snapshot uniqueSn = uniqueQualifiedSnapshot();
        GraphModel setGraph = GraphBuilder.build(qualifiedSchema(true), uniqueSn).value().graph();
        assertEquals(3, GraphObservation.linkTargets(bagGraph, qualifiedSchema(false),
                "c", "employees").size());
        assertEquals(2, GraphObservation.linkTargets(setGraph, qualifiedSchema(true),
                "c", "employees").size());
    }

    @Test
    void validRepChecksQualifiedLowerBoundsForEveryFiniteKey() {
        SchemaModel sm = qualifiedSchemaWithBounds(false, 0, 1, slotDomain());
        Snapshot sn = Snapshot.builder()
                .object("c", "Company")
                .object("e1", "Employee")
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.ONE))))
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.ONE))))
                .build();

        var result = GraphBuilder.build(sm, sn);
        assertTrue(result.isFailure(), () -> result.diagnostics().toString());
        assertEquals("VALIDREP_MULTIPLICITY", result.primaryDiagnostic().code());
        assertTrue(result.primaryDiagnostic().message().contains("qualifier"));
    }

    @Test
    void validRepRequiresCompleteQualifierDomainsForPositiveLowerBounds() {
        SchemaModel sm = qualifiedSchemaWithBounds(false, 0, 1,
                UmlQualifier.typed("slot", OclType.INTEGER));
        Snapshot sn = Snapshot.builder()
                .object("c", "Company")
                .object("e1", "Employee")
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.ONE))))
                .build();

        var result = GraphBuilder.build(sm, sn);
        assertTrue(result.isFailure(), () -> result.diagnostics().toString());
        assertEquals("VALIDREP_QUALIFIER_DOMAIN_REQUIRED", result.primaryDiagnostic().code());
    }

    @Test
    void linkStableKeysAreDeterministicAndInheritanceCyclesAreRejected() {
        SchemaModel sm = qualifiedSchema(false);
        Snapshot sn = qualifiedSnapshot();
        var first = GraphBuilder.build(sm, sn).value().graph().relationships().stream()
                .map(GraphModel.Relationship::stableKey).toList();
        var second = GraphBuilder.build(sm, sn).value().graph().relationships().stream()
                .map(GraphModel.Relationship::stableKey).toList();
        assertEquals(first, second);

        Snapshot reordered = Snapshot.builder()
                .object("c", "Company")
                .object("e1", "Employee")
                .object("e2", "Employee")
                .link("employment", "c", "e2",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.TWO))))
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.ONE))))
                .link("employment", "c", "e1",
                        List.of(new QualifierValue("slot",
                                new OclValue.IntegerValue(BigInteger.ONE))))
                .build();
        var reorderedKeys = GraphBuilder.build(sm, reordered).value().graph().relationships()
                .stream().map(GraphModel.Relationship::stableKey).toList();
        assertEquals(first.stream().filter(k -> GraphKey.isKind(k, GraphKey.Kind.LINK))
                        .sorted().toList(),
                reorderedKeys.stream().filter(k -> GraphKey.isKind(k, GraphKey.Kind.LINK))
                        .sorted().toList());

        SchemaModel cyclic = SchemaModel.builder("cycle")
                .clazz(new UmlClass("A", "A", false, false, List.of("B")))
                .clazz(new UmlClass("B", "B", false, false, List.of("A")))
                .build();
        assertTrue(cyclic.hasInheritanceCycle());
        assertTrue(GraphBuilder.build(cyclic, Snapshot.builder().build()).isFailure());
    }

    @Test
    void iteratorMayUseTheImplicitItBinder() {
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
                "context Company inv Adults: self.employees->forAll(it.age >= 18)", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
    }

    @Test
    void loweringFindsAttributesOnEveryDirectSuperclassBranch() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Left"))
                .clazz(UmlClass.of("Right"))
                .clazz(UmlClass.of("Child", "Left", "Right"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Right::age", "age", "Right", OclType.INTEGER))
                .build();
        var parsed = FrontendCompiler.compile(
                "context Child inv Adult: self.age >= 18", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var comparison = (org.uet.dse.ocl2cypher.source.omg.OmgAs.OperationCallExp) doc.constraints.get(0).specification.bodyExpression;
        var property = (org.uet.dse.ocl2cypher.source.omg.OmgAs.PropertyCallExp) comparison.source;
        assertEquals("Right::age", property.referredProperty);
        var lowered = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
        var coreComparison = (CoreExpr.Binary) lowered.value().body();
        var coreProperty = (CoreExpr.AttributeRead) coreComparison.left;
        assertEquals("Right", coreProperty.ownerClassKey);
    }

    @Test
    void frontendRejectsAmbiguousInheritedAttributesAndAcceptsAnOverride() {
        SchemaModel ambiguous = SchemaModel.builder("m")
                .clazz(UmlClass.of("Left"))
                .clazz(UmlClass.of("Right"))
                .clazz(UmlClass.of("Child", "Left", "Right"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Left::age", "age", "Left", OclType.INTEGER))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Right::age", "age", "Right", OclType.INTEGER))
                .build();
        var rejected = FrontendCompiler.compile(
                "context Child inv Adult: self.age >= 18", ambiguous);
        assertTrue(rejected.isFailure());
        assertEquals("E_AMBIGUOUS_PROPERTY", rejected.primaryDiagnostic().code());

        SchemaModel overridden = SchemaModel.builder("m")
                .clazz(UmlClass.of("Left"))
                .clazz(UmlClass.of("Right"))
                .clazz(UmlClass.of("Child", "Left", "Right"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Left::age", "age", "Left", OclType.INTEGER))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Right::age", "age", "Right", OclType.INTEGER))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Child::age", "age", "Child", OclType.INTEGER))
                .build();
        var accepted = FrontendCompiler.compile(
                "context Child inv Adult: self.age >= 18", overridden);
        assertTrue(accepted.isSuccess(), () -> accepted.diagnostics().toString());
    }

    @Test
    void orderedNavigationAllowsPermutationInvariantUseButRejectsEquality() {
        SchemaModel sm = SchemaModel.builder("ordered")
                .clazz(UmlClass.of("Company"))
                .clazz(UmlClass.of("Employee"))
                .association(new UmlAssociation("Employment", "Employment",
                        "Company", "employer", 0, 1,
                        "Employee", "employees", 0, -1,
                        List.of(), true, true))
                .build();

        var invariant = FrontendCompiler.compile(
                "context Company inv Empty: self.employees->isEmpty()", sm);
        assertTrue(invariant.isSuccess(), () -> invariant.diagnostics().toString());

        var rejected = FrontendCompiler.compile(
                "context Company inv OrderedEq: self.employees = self.employees", sm);
        assertTrue(rejected.isFailure());
        assertEquals("E_UNSUPPORTED_ORDERED_EQUALITY",
                rejected.primaryDiagnostic().code());

        var throughLet = FrontendCompiler.compile(
                "context Company inv OrderedLet: let xs = self.employees in xs = xs", sm);
        assertTrue(throughLet.isFailure());
        assertEquals("E_UNSUPPORTED_ORDERED_EQUALITY",
                throughLet.primaryDiagnostic().code());

        var unused = FrontendCompiler.compile(
                "context Company inv Unused: let xs = self.employees in Set{1} = Set{1}",
                sm);
        assertTrue(unused.isSuccess(), () -> unused.diagnostics().toString());
    }

    @Test
    void operationCapabilityMatrixIsTotalAndFailSafe() {
        assertEquals(org.uet.dse.ocl2cypher.source.omg.OclOperation.values().length,
                CapabilityEvidence.entries().size());
        assertTrue(CapabilityMatrix.isAdmitted(
                org.uet.dse.ocl2cypher.source.omg.OclOperation.NUMERIC_ABS));
        assertTrue(CapabilityMatrix.isAdmitted(
                org.uet.dse.ocl2cypher.source.omg.OclOperation.SET_UNION));
        assertTrue(CapabilityMatrix.isAdmitted(
                org.uet.dse.ocl2cypher.source.omg.OclOperation.COLLECTION_INCLUDES));
    }

    @Test
    void frontendRejectsIllTypedLogicalAndCollectionOperations() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();

        var badAnd = FrontendCompiler.compile(
                "context Person inv BadAnd: 1 and true", sm);
        assertTrue(badAnd.isFailure());
        assertEquals("E_TYPE", badAnd.primaryDiagnostic().code());

        var badMembership = FrontendCompiler.compile(
                "context Person inv BadMember: Set{1}->includes('x')", sm);
        assertTrue(badMembership.isFailure());
        assertEquals("E_TYPE", badMembership.primaryDiagnostic().code());

        var dotCollectionCall = FrontendCompiler.compile(
                "context Person inv BadShape: Set{1}.includes(1)", sm);
        assertTrue(dotCollectionCall.isFailure());
        assertEquals("E_INVALID_CALL_SHAPE", dotCollectionCall.primaryDiagnostic().code());

        var badSum = FrontendCompiler.compile(
                "context Person inv BadSum: Set{'x'}->sum() = 'x'", sm);
        assertTrue(badSum.isFailure());
        assertEquals("E_TYPE", badSum.primaryDiagnostic().code());

        var badCast = FrontendCompiler.compile(
                "context Person inv BadCast: Set{1}.oclAsType(Person) = self", sm);
        assertTrue(badCast.isFailure());
        assertEquals("E_TYPE", badCast.primaryDiagnostic().code());
    }

    @Test
    void frontendEnforcesQualifierArityEvenWhenNoQualifierWasWritten() {
        SchemaModel sm = qualifiedSchema(false);

        var missing = FrontendCompiler.compile(
                "context Company inv MissingQualifier: self.employees->isEmpty()", sm);
        assertTrue(missing.isFailure());
        assertEquals("E_ARITY", missing.primaryDiagnostic().code());

        var supplied = FrontendCompiler.compile(
                "context Company inv Qualified: self.employees[1]->notEmpty()", sm);
        assertTrue(supplied.isSuccess(), () -> supplied.diagnostics().toString());
    }

    @Test
    void selfIsBoundAsATaggedObjectRatherThanReadAsARawNodeValue() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age", "age", "Person", OclType.INTEGER))
                .attribute(org.uet.dse.ocl2cypher.source.model.UmlAttribute.of("Person", "active", OclType.BOOLEAN))
                .build();
        // Integer attribute realization now succeeds; verify self-binding shape.
        String ageText = realizeViolationText("context Person inv Adult: self.age >= 18", sm,
                Snapshot.builder().object("p", "Person").build());
        assertTrue(ageText.contains("`self`.`use_id`"), ageText);
        String text = realizeViolationText(
                "context Person inv Active: self.active", sm,
                Snapshot.builder().object("p", "Person").build());

        assertTrue(text.contains("`self`.`use_id`"), text);
        assertFalse(text.contains("`self`.`__oclValue`"), text);
    }

    @Test
    void exactClassCastAcceptsAnObjectAlreadyOfTheTargetClass() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .build();
        Snapshot sn = Snapshot.builder().object("p", "Person").build();

        String text = realizeViolationText(
                "context Person inv ReflexiveCast: self.oclAsType(Person) = self", sm, sn);

        assertTrue(text.contains("ObjectInstanceOf"), text);
        assertTrue(text.contains("`Extends`*1.."), text);
        assertTrue(text.contains(" OR EXISTS"), text);
    }

    @Test
    void sourceBuildersRejectSilentOverwritesAndUnknownLinkEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .clazz(UmlClass.of("Person")));
        assertThrows(IllegalArgumentException.class, () -> SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age", "age", "Person", OclType.INTEGER))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age2", "age", "Person", OclType.INTEGER)));
        assertThrows(IllegalArgumentException.class, () -> SchemaModel.builder("m")
                .clazz(UmlClass.of("Person")).clazz(UmlClass.of("Employee"))
                .association(UmlAssociation.binary("a", "Person", "owner",
                        "Employee", "employees"))
                .association(UmlAssociation.binary("b", "Person", "owner",
                        "Employee", "workers")));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.builder()
                .object("p", "Person").object("p", "Person"));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.builder()
                .object("p", "Person")
                .attribute("p", "age", new OclValue.IntegerValue(BigInteger.ONE))
                .attribute("p", "age", new OclValue.IntegerValue(BigInteger.TWO)));
        assertThrows(IllegalArgumentException.class, () -> Snapshot.builder()
                .link("owns", "missing", "also-missing"));

        SchemaModel orphanAttribute = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Ghost::age", "age", "Ghost", OclType.INTEGER))
                .build();
        assertTrue(GraphBuilder.build(orphanAttribute, Snapshot.builder().build()).isFailure());

        SchemaModel invalidMultiplicity = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .association(new UmlAssociation("bad-key", "bad",
                        "Person", "owner", 2, 1,
                        "Person", "items", 0, -1,
                        List.of(), false, true))
                .build();
        var invalidBuild = GraphBuilder.build(invalidMultiplicity, Snapshot.builder().build());
        assertTrue(invalidBuild.isFailure());
        assertEquals("MM_INVALID_MULTIPLICITY", invalidBuild.primaryDiagnostic().code());
    }

    @Test
    void normalizationIsMandatoryAtRAndIdempotentByStructuralKey() {
        SourceSpan span = SourceSpan.UNKNOWN;
        QNode.QExpr one = new QNode.QExpr.Constant(span, OclType.INTEGER, BigInteger.ONE);
        QNode.QExpr set = new QNode.QExpr.CollectionLiteral(span,
                CoreExpr.CollectionKind.SET, List.of(one), OclType.set(OclType.INTEGER));
        QNode.QExpr derived = new QNode.QExpr.IncludesFamily(span, QNode.QKind.INCLUDES,
                set, one, OclType.BOOLEAN);
        QNode.QExpr once = NormQ.normalizeExpr(derived);
        QNode.QExpr twice = NormQ.normalizeExpr(once);

        assertInstanceOfBinary(once);
        assertEquals(NormQ.structuralKey(once), NormQ.structuralKey(twice));

        CoreDeclaration self = new CoreDeclaration(1, "self", CoreDeclaration.Kind.SELF,
                OclType.clazz("Person"));
        QQuery query = new QQuery(derived, null, QQuery.QResultShape.IDS,
                QQuery.QueryMode.VIOLATIONS, null, "Person", self, true);
        GraphModel graph = GraphBuilder.build(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().object("p", "Person").build()).value().graph();
        var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        assertTrue(Serializer.cypherText(realized.value()).contains("any(`x"));
    }

    @Test
    void realizationScopeUsesDeclarationIdentityUnderShadowing() {
        // Integer variable now certified; verify the let-binding realization succeeds.
        assertNotNull(realizeViolationText("context Person inv Local: let x : Integer = 1 in x = 1",
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().object("p", "Person").build()));
        SourceSpan span = SourceSpan.UNKNOWN;
        CoreDeclaration self = new CoreDeclaration(1, "self", CoreDeclaration.Kind.SELF,
                OclType.clazz("Person"));
        CoreDeclaration outer = new CoreDeclaration(2, "x", CoreDeclaration.Kind.LET,
                OclType.STRING);
        CoreDeclaration inner = new CoreDeclaration(3, "x", CoreDeclaration.Kind.LET,
                OclType.STRING);
        QNode.QExpr one = new QNode.QExpr.Constant(span, OclType.STRING, "outer");
        QNode.QExpr two = new QNode.QExpr.Constant(span, OclType.STRING, "inner");
        QNode.QExpr refersToOuter = new QNode.QExpr.Binary(span,
                CoreExpr.BinaryOp.VALUE_EQUAL,
                new QNode.QExpr.Variable(span, outer), one, OclType.BOOLEAN);
        QNode.QExpr nested = new QNode.QExpr.Let(span, inner, two, refersToOuter);
        QNode.QExpr body = new QNode.QExpr.Let(span, outer, one, nested);
        QQuery query = new QQuery(body, null, QQuery.QResultShape.IDS,
                QQuery.QueryMode.VIOLATIONS, null, "Person", self, true);
        GraphModel graph = GraphBuilder.build(
                SchemaModel.builder("m").clazz(UmlClass.of("Person")).build(),
                Snapshot.builder().object("p", "Person").build()).value().graph();

        var realized = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        String text = Serializer.cypherText(realized.value());
        assertFalse(text.contains("`__oclValue`: 'inner'"),
                "the shadowing binder is unused; an identity-correct scope must not capture it: "
                + text);
    }

    @Test
    void valueModeProducesAStableTaggedResultContract() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age", "age", "Person", OclType.INTEGER))
                .attribute(org.uet.dse.ocl2cypher.source.model.UmlAttribute.of("Person", "active", OclType.BOOLEAN))
                .build();
        for (String attribute : List.of("age", "active")) {
            var parsed = FrontendCompiler.compileValueQuery(
                    org.uet.dse.ocl2cypher.api.ValueQueryRequest.contextual(
                            "self." + attribute, "Person"), sm);
            assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
            var lowered = CoreLowering.lowerValueQuery(sm, parsed.value());
            assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
            var translated = QCypTranslator.translate(lowered.value());
            assertTrue(translated.isSuccess(), () -> translated.diagnostics().toString());
            GraphModel graph = GraphBuilder.build(sm,
                    Snapshot.builder().object("p", "Person").build()).value().graph();
            var realized = Realization.realize(translated.value(), graph, CypherAst.Dialect.CYPHER_5);
            assertTrue(realized.isSuccess(), () -> attribute + ": " + realized.diagnostics().toString());
            assertEquals(CypherAst.ResultShape.SCALAR, realized.value().contract().shape());
        }
    }

    @Test
    void valueModeRejectsInvariantUnitsAndSupportsContextlessQueries() {
        SchemaModel sm = SchemaModel.builder("m").clazz(UmlClass.of("Person")).build();
        var parsed = FrontendCompiler.compile("context Person inv Always: true", sm);
        assertTrue(parsed.isSuccess(), () -> parsed.diagnostics().toString());
        var doc = parsed.value().get(0);
        var invariant = CoreLowering.lower(sm, doc, doc.constraints.get(0));
        assertTrue(invariant.isSuccess(), () -> invariant.diagnostics().toString());
        assertTrue(invariant.value() instanceof org.uet.dse.ocl2cypher.core.CoreInvariant);

        QNode.QExpr constant = new QNode.QExpr.Constant(SourceSpan.UNKNOWN,
                OclType.INTEGER, BigInteger.ONE);
        QQuery contextless = new QQuery(constant, null, QQuery.QResultShape.SCALAR,
                QQuery.QueryMode.VALUE, OclType.INTEGER, null, null, false);
        GraphModel graph = GraphBuilder.build(sm, Snapshot.builder().build()).value().graph();
        var realized = Realization.realize(contextless, graph, CypherAst.Dialect.CYPHER_5);
        assertTrue(realized.isSuccess(), () -> realized.diagnostics().toString());
        String text = Serializer.cypherText(realized.value());
        assertFalse(text.contains("MATCH"), text);
        assertTrue(text.contains("RETURN"), text);
        assertTrue(realized.value().parameters().isEmpty());
    }

    @Test
    void typedBottomTypeTestsFollowProfileTypeConformance() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .clazz(UmlClass.of("Employee", "Person"))
                .build();
        Snapshot sn = Snapshot.builder().build();
        SourceSpan span = SourceSpan.UNKNOWN;
        CoreExpr bottomEmployee = new CoreExpr.Bottom(span, OclType.clazz("Employee"));
        CoreExpr exactEmployee = new CoreExpr.TypeTest(span, CoreExpr.TypeTestKind.EXACT_TYPE,
                bottomEmployee, "Employee");
        CoreExpr kindOfPerson = new CoreExpr.TypeTest(span, CoreExpr.TypeTestKind.CONFORMS_TO,
                bottomEmployee, "Person");
        CoreExpr exactPerson = new CoreExpr.TypeTest(span, CoreExpr.TypeTestKind.EXACT_TYPE,
                bottomEmployee, "Person");
        CoreInterpreter.Env env = new CoreInterpreter.Env();

        assertEquals(Boolean3.TRUE, CoreInterpreter.eval(sm, sn, env, exactEmployee));
        assertEquals(Boolean3.TRUE, CoreInterpreter.eval(sm, sn, env, kindOfPerson));
        assertEquals(Boolean3.FALSE, CoreInterpreter.eval(sm, sn, env, exactPerson));

        GraphModel graph = GraphBuilder.build(sm, sn).value().graph();
        QNode.QExpr qBottom = new QNode.QExpr.Bottom(span, OclType.clazz("Employee"));
        assertEquals(Boolean3.TRUE, QInterpreter.evalExpr(sm, graph, env,
                new QNode.QExpr.TypeTest(span, CoreExpr.TypeTestKind.EXACT_TYPE,
                        qBottom, "Employee")));
        assertEquals(Boolean3.TRUE, QInterpreter.evalExpr(sm, graph, env,
                new QNode.QExpr.TypeTest(span, CoreExpr.TypeTestKind.CONFORMS_TO,
                        qBottom, "Person")));
        assertEquals(Boolean3.FALSE, QInterpreter.evalExpr(sm, graph, env,
                new QNode.QExpr.TypeTest(span, CoreExpr.TypeTestKind.EXACT_TYPE,
                        qBottom, "Person")));
        CoreExpr cast = new CoreExpr.TypeCast(span,
                new CoreExpr.Bottom(span, OclType.clazz("Person")), "Employee");
        assertEquals(new OclValue.BottomValue(OclType.clazz("Employee")),
                CoreInterpreter.eval(sm, sn, env, cast));
        assertEquals(Boolean3.TRUE, CoreInterpreter.eval(sm, sn, env,
                new CoreExpr.TypeTest(span, CoreExpr.TypeTestKind.EXACT_TYPE, cast, "Employee")));
        assertEquals(Boolean3.FALSE, CoreInterpreter.eval(sm, sn, env,
                new CoreExpr.TypeTest(span, CoreExpr.TypeTestKind.CONFORMS_TO,
                        new CoreExpr.Bottom(span, OclType.clazz("Person")), "Employee")));

        for (CoreExpr.TypeTestKind kind : CoreExpr.TypeTestKind.values()) {
            QNode.QExpr test = new QNode.QExpr.TypeTest(span, kind, qBottom, "Person");
            QQuery query = new QQuery(test, null, QQuery.QResultShape.SCALAR,
                    QQuery.QueryMode.VALUE, OclType.BOOLEAN, null, null, false);
            var artifact = Realization.realize(query, graph, CypherAst.Dialect.CYPHER_5);
            assertTrue(artifact.isSuccess(), () -> artifact.diagnostics().toString());
            var clause = (CypherAst.ReturnClause) artifact.value().query().clauses().get(0);
            assertNotNull(clause);
            String cypher = Serializer.cypherText(artifact.value());
            assertTrue(cypher.contains("Class:Person"), cypher);
            assertTrue(cypher.contains("__oclType"), cypher);
        }
    }

    @Test
    void typeOperationsRejectUnknownUnrelatedAndNonClassOperands() {
        var schema = SchemaModel.builder("types")
                .clazz(UmlClass.of("Person"))
                .clazz(UmlClass.of("Employee", "Person"))
                .clazz(UmlClass.of("Vehicle")).build();
        for (String operation : List.of("oclIsTypeOf", "oclIsKindOf", "oclAsType")) {
            for (String expression : List.of("self." + operation + "(Vehicle)",
                    "self." + operation + "(UnknownClass)", "1." + operation + "(Person)")) {
                var rejected = FrontendCompiler.compileValueQuery(
                        org.uet.dse.ocl2cypher.api.ValueQueryRequest.contextual(expression, "Person"), schema);
                assertFalse(rejected.isSuccess(), expression);
                assertFalse(rejected.diagnostics().isEmpty(), expression);
            }
            for (String target : List.of("Person", "Employee")) {
                var accepted = FrontendCompiler.compileValueQuery(
                        org.uet.dse.ocl2cypher.api.ValueQueryRequest.contextual(
                                "self." + operation + "(" + target + ")", "Person"), schema);
                assertTrue(accepted.isSuccess(), () -> accepted.diagnostics().toString());
                var lowered = CoreLowering.lowerValueQuery(schema, accepted.value());
                assertTrue(lowered.isSuccess(), () -> lowered.diagnostics().toString());
            }
        }
    }

    @Test
    void storedTypedBottomIsNotRetaggedAsADefinedNullPayload() {
        SchemaModel sm = SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(new org.uet.dse.ocl2cypher.source.model.UmlAttribute(
                        "Person::age", "age", "Person", OclType.INTEGER))
                .attribute(org.uet.dse.ocl2cypher.source.model.UmlAttribute.of("Person", "active", OclType.BOOLEAN))
                .build();
        Snapshot sn = Snapshot.builder()
                .object("p", "Person")
                .attribute("p", "active", Boolean3.TRUE).build();
        String text = realizeViolationText(
                "context Person inv Adult: self.age >= 18", sm, sn);
        assertTrue(text.contains("__oclBottom"), text);
    }

}
