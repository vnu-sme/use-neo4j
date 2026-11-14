package org.uet.dse.ocl2cypher.support;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.uet.dse.ocl2cypher.source.omg.OclOperation;

/** Test evidence kept separately from runtime admission. */
public final class CapabilityEvidence {
    public record Capability(boolean admitted, String surfaceWitness, String asConstructor,
            String coreConstructor, String qFamily, String realizationRule,
            String serializerSupport, String oracleCoverage, String diagnosticCode,
            String testId, String reason) { }

    private static final Map<OclOperation, Capability> ENTRIES = load();
    private CapabilityEvidence() { }
    public static Map<OclOperation, Capability> entries() { return ENTRIES; }
    public static Capability capability(OclOperation operation) { return ENTRIES.get(operation); }

    private static Map<OclOperation, Capability> load() {
        var entries = new EnumMap<OclOperation, Capability>(OclOperation.class);
        for (var op : OclOperation.values()) entries.put(op, new Capability(false, null,
                "OmgAs.OperationCallExp", null, null, null, null, null,
                "N_UNSUPPORTED_OPERATION", "CAP-REJECT-" + op.name(),
                "operation has no reviewed end-to-end capability row"));
        try (var stream = CapabilityEvidence.class.getResourceAsStream("/operation-witnesses.tsv")) {
            if (stream == null) throw new IllegalStateException("missing operation witnesses");
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line : reader.lines().skip(1).toList()) {
                var c = line.split("\t", -1);
                var op = OclOperation.valueOf(c[0]);
                if (entries.get(op).admitted()) throw new IllegalStateException("duplicate witness: " + op);
                entries.put(op, new Capability(true,c[1],c[2],c[3],c[4],c[5],c[6],c[7],null,c[8],c[9]));
            }
            return Map.copyOf(entries);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
