package org.uet.dse.ocl2cypher.caseStudy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.api.CoreOracle;
import org.uet.dse.ocl2cypher.input.UseSoilInput;
import org.uet.dse.ocl2cypher.execution.UseEvaluationAdapter;

/** Independent USE evaluations for the ER2REL worked case study. */
class Er2RelUseOracleTest {

    private static final Path FIXTURES = Path.of("..", "research", "Case Study",
            "ER2REL", "use-oracle");
    private static final String INVARIANT = "Relation::REL_K";
    private static final String OCL =
            "context Relation inv REL_K: self.attrs->exists(a | a.isKey)";

    @Test
    void useAndCoreAgreeOnValidSoilSnapshot() throws Exception {
        var result = evaluate("valid.soil");
        assertEquals(Set.of(), result.violations().get(INVARIANT));
        assertEquals(3, result.objectEvaluations());
        assertEquals(result.violations().get(INVARIANT), core("valid.soil"));
    }

    @Test
    void useAndCoreAgreeOnNegativeSoilSnapshot() throws Exception {
        var result = evaluate("negative-no-key.soil");
        assertEquals(Set.of("relation_Places"), result.violations().get(INVARIANT));
        assertEquals(3, result.objectEvaluations());
        assertEquals(result.violations().get(INVARIANT), core("negative-no-key.soil"));
    }

    private static UseEvaluationAdapter.Result evaluate(String soil) throws Exception {
        return UseEvaluationAdapter.evaluate(FIXTURES.resolve("REL.use"),
                FIXTURES.resolve("REL_K.ocl"), FIXTURES.resolve(soil));
    }

    private static Set<String> core(String soil) {
        var loaded = UseSoilInput.load(FIXTURES.resolve("REL.use"),
                FIXTURES.resolve(soil), FIXTURES.resolve("REL_K.ocl"));
        if (loaded.isFailure()) {
            throw new AssertionError(loaded.diagnostics().toString());
        }
        List<String> violations = CoreOracle.violationsOclEq(OCL,
                loaded.value().schema(), loaded.value().snapshot());
        return Set.copyOf(violations);
    }

}
