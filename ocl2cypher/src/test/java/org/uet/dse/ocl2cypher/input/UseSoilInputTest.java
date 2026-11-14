package org.uet.dse.ocl2cypher.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uet.dse.ocl2cypher.api.FrontendCompiler;
import org.uet.dse.ocl2cypher.api.CoreOracle;
import org.uet.dse.ocl2cypher.execution.UseEvaluationAdapter;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.QualifierValue;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;

class UseSoilInputTest {

    @Test
    void loadsUseSchemaSoilSnapshotAndEmbeddedInvariants() throws Exception {
        var loaded = UseSoilInput.load(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        ModelInputBundle input = loaded.value();
        assertEquals("Shop", input.schema().modelKey());
        assertEquals(2, input.schema().classes().size());
        assertEquals(2, input.snapshot().objects().size());
        assertEquals(2, input.invariants().size());
        assertEquals("context Catalog inv HasItems: self.items->notEmpty()",
                input.invariants().get(0).oclText());
        assertEquals("Main -- catalog",
                ((OclValue.StringValue) input.snapshot()
                        .attributeSlot("catalog1", "name").orElseThrow()).value());
        assertEquals(java.util.List.of("item1"),
                input.snapshot().linkTargets(input.schema(), "items", "catalog1"));

        for (SourceInvariant invariant : input.invariants()) {
            var compiled = FrontendCompiler.compile(invariant.oclText(), input.schema());
            assertTrue(compiled.isSuccess(), () -> invariant.name() + ": "
                    + compiled.diagnostics());
        }
        var graph = GraphBuilder.build(input.schema(), input.snapshot());
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
    }

    @Test
    void compilesEmbeddedInvariantThroughCoreQAndCypher() throws Exception {
        var compiled = UseSoilCompiler.compile(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop.soil"), "HasItems");

        assertTrue(compiled.isSuccess(), () -> compiled.diagnostics().toString());
        assertEquals("HasItems", compiled.value().core().invariantName());
        assertFalse(compiled.value().serializedCypher().cypherText().isBlank());
        assertTrue(compiled.value().serializedCypher().cypherText().contains("RETURN"));
    }

    @Test
    void preparedGraphCompilationKeepsTheSameCypher() throws Exception {
        var loaded = UseSoilInput.load(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop.soil"));
        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        var graph = GraphBuilder.build(loaded.value().schema(), loaded.value().snapshot());
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());
        var dialect = org.uet.dse.ocl2cypher.cypher.CypherAst.Dialect.CYPHER_5;
        var ordinary = ModelInputCompiler.compile(loaded.value(), "HasItems", dialect);
        var reused = ModelInputCompiler.compile(loaded.value(), "HasItems", dialect,
                graph.value());
        assertTrue(ordinary.isSuccess(), () -> ordinary.diagnostics().toString());
        assertTrue(reused.isSuccess(), () -> reused.diagnostics().toString());
        assertEquals(ordinary.value().serializedCypher().cypherText(),
                reused.value().serializedCypher().cypherText());
    }

    @Test
    void requiresQualifiedNameWhenInvariantNamesAreAmbiguous() throws Exception {
        Path use = example("families-to-persons-correctness-case-study/families",
                "families.use");
        Path soil = example("families-to-persons-correctness-case-study/families",
                "families.soil");
        var loaded = UseSoilInput.load(use, soil);
        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());

        var ambiguous = ModelInputCompiler.compile(loaded.value(), "Named",
                org.uet.dse.ocl2cypher.cypher.CypherAst.Dialect.CYPHER_5);
        assertTrue(ambiguous.isFailure());
        assertEquals("E_INVARIANT_AMBIGUOUS", ambiguous.primaryDiagnostic().code());

        var qualified = ModelInputCompiler.compile(loaded.value(), "FamilyMember::Named",
                org.uet.dse.ocl2cypher.cypher.CypherAst.Dialect.CYPHER_5);
        assertTrue(qualified.isSuccess(), () -> qualified.diagnostics().toString());
        assertEquals("FamilyMember", qualified.value().sourceInvariant().contextClassKey());
    }

    @Test
    void reportsMissingFilesAtTheInputBoundary() {
        var loaded = UseSoilInput.load(Path.of("missing.use"),
                Path.of("missing.soil"));

        assertTrue(loaded.isFailure());
        assertEquals("E_USE_SOIL_INPUT", loaded.primaryDiagnostic().code());
    }

    @Test
    void preparesTheUseSoilGraphForNeo4jWithoutOpeningAConnection() throws Exception {
        var loaded = UseSoilInput.load(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        var prepared = GraphBuilder.build(loaded.value().schema(), loaded.value().snapshot());
        assertTrue(prepared.isSuccess(), () -> prepared.diagnostics().toString());
        assertEquals("Shop", prepared.value().graph().modelKey());
        assertFalse(prepared.value().graph().nodes().isEmpty());
    }

    @Test
    void loadsTraditionalUseCreateSyntax() throws Exception {
        var loaded = UseSoilInput.load(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop-traditional.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        assertEquals(2, loaded.value().snapshot().objects().size());
        assertEquals("Book", ((OclValue.StringValue) loaded.value().snapshot()
                .attributeSlot("item1", "name").orElseThrow()).value());
        assertEquals(java.util.List.of("item1"), loaded.value().snapshot()
                .linkTargets(loaded.value().schema(), "items", "catalog1"));
    }

    @Test
    void executesMultilineFullSoilBlockWithVariablesAndConditional()
            throws Exception {
        var loaded = UseSoilInput.load(example("shop-use-soil", "shop.use"),
                example("shop-use-soil", "shop-block.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        assertEquals(2, loaded.value().snapshot().objects().size());
        assertEquals("Book!", ((OclValue.StringValue) loaded.value().snapshot()
                .attributeSlot("item1", "name").orElseThrow()).value());
        assertEquals(java.util.List.of("item1"), loaded.value().snapshot()
                .linkTargets(loaded.value().schema(), "items", "catalog1"));
    }

    @Test
    void loadsQualifiedLinksAndAssociationClassOccurrencesFromProductionFiles()
            throws Exception {
        var loaded = UseSoilInput.load(example("advanced-adapters", "advanced.use"),
                example("advanced-adapters", "advanced.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        ModelInputBundle input = loaded.value();
        var catalog = input.schema().associationByName("Catalog");
        assertEquals(1, catalog.qualifiers().size());
        assertEquals("shelf", catalog.qualifiers().get(0).name());
        assertEquals(UmlAssociation.QualifierEnd.TARGET, catalog.qualifierEnd());
        assertEquals(UmlAssociation.QualifierEnd.SOURCE,
                input.schema().associationByName("Ownership").qualifierEnd());
        assertTrue(input.schema().clazz("Loan").isAssociationClass());
        assertEquals("Loan", input.schema().associationByName("Loan").key());

        var qualified = input.snapshot().links().stream()
                .filter(link -> link.associationName.equals("Catalog"))
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of(new QualifierValue("shelf",
                        new OclValue.StringValue("A"))), qualified.qualifiers);
        var sourceQualified = input.snapshot().links().stream()
                .filter(link -> link.associationName.equals("Ownership"))
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of(new QualifierValue("section",
                        new OclValue.StringValue("X"))), sourceQualified.qualifiers);
        var loan = input.snapshot().links().stream()
                .filter(link -> link.associationName.equals("Loan"))
                .findFirst().orElseThrow();
        assertEquals("loan1", loan.associationClassObjectStableId);
        assertEquals("overnight", ((OclValue.StringValue) input.snapshot()
                .attributeSlot("loan1", "note").orElseThrow()).value());

        for (SourceInvariant invariant : input.invariants()) {
            var compiled = FrontendCompiler.compile(invariant.oclText(), input.schema());
            assertTrue(compiled.isSuccess(), () -> invariant.name() + ": "
                    + compiled.diagnostics());
            var pipeline = UseSoilCompiler.compile(
                    example("advanced-adapters", "advanced.use"),
                    example("advanced-adapters", "advanced.soil"), invariant.name());
            assertTrue(pipeline.isSuccess(), () -> invariant.name() + " pipeline: "
                    + pipeline.diagnostics());
            assertFalse(pipeline.value().serializedCypher().cypherText().isBlank());
        }
        assertTrue(GraphBuilder.build(input.schema(), input.snapshot()).isSuccess());

        assertTrue(FrontendCompiler.compile(
                "context Book inv ReverseCatalog: self.library->notEmpty()",
                input.schema()).isSuccess());
        assertTrue(FrontendCompiler.compile(
                "context Library inv ForwardOwnership: self.ownedBook->notEmpty()",
                input.schema()).isSuccess());
        assertTrue(FrontendCompiler.compile(
                "context Book inv WrongCatalogQualifier: self.library['A']->notEmpty()",
                input.schema()).isFailure());
        assertTrue(FrontendCompiler.compile(
                "context Library inv WrongOwnershipQualifier: self.ownedBook['X']->notEmpty()",
                input.schema()).isFailure());
    }

    @Test
    void rejectsOperationDeclarationsInsteadOfSilentlyErasingThem() throws Exception {
        var loaded = UseSoilInput.load(
                example("unsupported-operations", "operations.use"),
                example("unsupported-operations", "empty.soil"));

        assertTrue(loaded.isFailure());
        assertEquals("E_USE_SOIL_INPUT", loaded.primaryDiagnostic().code());
        assertTrue(loaded.primaryDiagnostic().message().contains("operation declarations"));
    }

    @Test
    void rejectsOperationContextsAndPreconditionsInsteadOfSilentlyErasingThem()
            throws Exception {
        var loaded = UseSoilInput.load(example("unsupported-prepost", "prepost.use"),
                example("unsupported-prepost", "empty.soil"));

        assertTrue(loaded.isFailure());
        assertEquals("E_USE_SOIL_INPUT", loaded.primaryDiagnostic().code());
        assertTrue(loaded.primaryDiagnostic().message().contains("pre/postconditions"));
    }

    @Test
    void loadsExternalOclAlongsideEmbeddedInvariants(@TempDir Path directory) throws Exception {
        Path use = directory.resolve("model.use");
        Path soil = directory.resolve("snapshot.soil");
        Path ocl = directory.resolve("constraints.ocl");
        Files.writeString(use, """
                model ExternalOcl
                class Person
                attributes
                  age : Integer
                end
                constraints
                context Person inv Nonnegative:
                  self.age >= 0
                """);
        Files.writeString(soil, """
                !new Person('p1')
                !set p1.age := 20
                !new Person('p2')
                !set p2.age := 15
                """);
        Files.writeString(ocl, """
                -- Keep the source call syntax when projecting the USE model.
                context Person inv Adult:
                  not self.age.oclIsUndefined()
                  and self.age >= 18
                """);

        var loaded = UseSoilInput.load(use, soil, ocl);
        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        var input = loaded.value();
        assertEquals(List.of("Adult", "Nonnegative"),
                input.invariants().stream().map(SourceInvariant::name).toList());
        assertEquals("self.age >= 0", input.invariants().get(1).body());
        assertTrue(input.invariants().get(0).body().contains("oclIsUndefined()"));

        var compiled = ModelInputCompiler.compile(input, "Adult",
                org.uet.dse.ocl2cypher.cypher.CypherAst.Dialect.CYPHER_5);
        assertTrue(compiled.isSuccess(), () -> compiled.diagnostics().toString());
        var result = compiled.value();
        var useIds = UseEvaluationAdapter.evaluate(use, ocl, soil).violationsFor("Adult");
        assertEquals(Set.of("p2"), useIds);
        assertEquals(useIds, new TreeSet<>(CoreOracle.violationsOclEq(
                result.sourceInvariant().oclText(), input.schema(), input.snapshot())));
        assertEquals(useIds, new TreeSet<>(QInterpreter.violations(input.schema(),
                result.graph().graph(), result.core(), result.query())));
        assertFalse(result.serializedCypher().cypherText().isBlank());
    }

    @Test
    void rejectsUnsupportedExtensionsForEveryInputRole(@TempDir Path directory) throws Exception {
        Path use = example("shop-use-soil", "shop.use");
        Path soil = example("shop-use-soil", "shop.soil");
        for (String extension : List.of("ecore", "uml", "xmi")) {
            Path unsupported = directory.resolve("model." + extension);
            Files.writeString(unsupported, "unsupported input");
            var loaded = UseSoilInput.load(unsupported, soil);
            assertTrue(loaded.isFailure());
            assertEquals("E_USE_SOIL_INPUT", loaded.primaryDiagnostic().code());
            assertTrue(loaded.primaryDiagnostic().message().contains(".use file"));
        }
        Path unsupported = directory.resolve("input.txt");
        Files.writeString(unsupported, "unsupported input");
        var snapshot = UseSoilInput.load(use, unsupported);
        assertTrue(snapshot.isFailure());
        assertTrue(snapshot.primaryDiagnostic().message().contains(".soil file"));
        var constraints = UseSoilInput.load(use, soil, unsupported);
        assertTrue(constraints.isFailure());
        assertTrue(constraints.primaryDiagnostic().message().contains(".ocl file"));
    }

    @Test
    void rejectsUnreadableOrMalformedExternalOcl(@TempDir Path directory) throws Exception {
        Path use = example("shop-use-soil", "shop.use");
        Path soil = example("shop-use-soil", "shop.soil");
        var missing = UseSoilInput.load(use, soil, directory.resolve("missing.ocl"));
        assertTrue(missing.isFailure());
        assertEquals("E_USE_SOIL_INPUT", missing.primaryDiagnostic().code());
        assertTrue(missing.primaryDiagnostic().message().contains("missing.ocl"));

        Path ocl = directory.resolve("broken.ocl");
        Files.writeString(ocl, "context Catalog inv Broken: true and");
        var malformed = UseSoilInput.load(use, soil, ocl);
        assertTrue(malformed.isFailure());
        assertEquals("E_USE_SOIL_INPUT", malformed.primaryDiagnostic().code());
        assertTrue(malformed.primaryDiagnostic().message().contains("USE specification compilation failed"));
    }

    private static Path example(String directory, String fileName) {
        return Path.of("..", "example", directory, fileName)
                .toAbsolutePath().normalize();
    }
}
