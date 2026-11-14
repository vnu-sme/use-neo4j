package org.uet.dse.ocl2cypher.diagnostics;

/** Stable rule identifiers referenced by diagnostics and trace records. */
public final class RuleId {

    private RuleId() {
    }

    // Identifiers currently emitted by diagnostics and trace records.
    public static final String E_DOC = "E-DOC";
    public static final String E_CONTEXT = "E-CONTEXT";
    public static final String N_INVARIANT = "N-INVARIANT";
    public static final String T_Q_VALUE_EXPR = "T-Q-VALUE-EXPR";
    public static final String T_Q_VALUE_PLAN = "T-Q-VALUE-PLAN";
    public static final String T_Q_VIOLATIONS = "T-Q-VIOLATIONS";
    public static final String T_MODEL_BATCH = "T-MODEL-BATCH";
    public static final String R_REAL_EXACT_UNSUPPORTED = "R-REAL-EXACT-UNSUPPORTED";
    public static final String R_Q_VALUE_SCALAR = "R-Q-VALUE-SCALAR";
    public static final String R_Q_VIOLATIONS = "R-Q-VIOLATIONS";
    public static final String S_ARTIFACT = "S-ARTIFACT";
}
