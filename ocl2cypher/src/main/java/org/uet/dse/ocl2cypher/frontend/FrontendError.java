package org.uet.dse.ocl2cypher.frontend;

import org.uet.dse.ocl2cypher.diagnostics.*;

final class FrontendError extends RuntimeException {
    private final Diagnostic diagnostic;

    FrontendError(Diagnostic diagnostic) {
        super(diagnostic.toString(), null, false, false);
        this.diagnostic = diagnostic;
    }

    Diagnostic diagnostic() {
        return diagnostic;
    }

    static FrontendError error(String code, String message, SourceSpan span) {
        return new FrontendError(Diagnostic.builder(code, Stage.E_SM, message)
                .span(span)
                .rule("E-F")
                .build());
    }
}
