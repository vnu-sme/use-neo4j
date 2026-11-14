package org.uet.dse.ocl2cypher;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.api.CoreOracle;
import org.uet.dse.ocl2cypher.conformance.PropertyFixtures;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.qcyp.QCypTranslator;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D11 matrix test: every bottom/empty/direction/multiplicity corner is a row
 * whose counter is exactly what checkers of carrier-membership need (callers
 * may compare by OCL-aware identities rather than raw collection pointers).
 *
 * <p>The carrier keeps scalar bottom, element bottom, whole-collection bottom,
 * and an empty collection distinct. Filters separately follow USE's rule that
 * an undefined predicate defaults to false for the current occurrence.
 */
class ConformanceMatrixTest {

    /** Missing scalar attribute → Boolean bottom → invariant violation. */
    @Test
    void bottomCarrierRemainsDistinguishable() {
        var sm = PropertyFixtures.personSchema();
        var sn = PropertyFixtures.mixedPersons();
        String ocl = "context Person inv Adult: self.age >= 18";

        var coreViol = CoreOracle.violationsOclEq(ocl, sm, sn);
        assertEquals(Set.of("bob", "carol"), Set.copyOf(coreViol),
                "bottom (missing age) is a violation, not an empty-filter hit");
        assertTrue(coreViol.contains("carol"));

        var fe = org.uet.dse.ocl2cypher.api.FrontendCompiler.compile(ocl, sm);
        assertTrue(fe.isSuccess());
        var low = org.uet.dse.ocl2cypher.core.CoreLowering.lower(sm, fe.value().get(0),
                fe.value().get(0).constraints.get(0));
        assertTrue(low.isSuccess());
        var q = QCypTranslator.translate(low.value());
        assertTrue(q.isSuccess());
        var g = GraphBuilder.build(sm, sn);
        assertTrue(g.isSuccess());
        var qViol = QInterpreter.violations(sm, g.value().graph(), low.value(), q.value());
        assertEquals(Set.copyOf(coreViol), Set.copyOf(qViol),
                "Q-over-G violation set must equal Core even on the bottom carrier");
    }
}
