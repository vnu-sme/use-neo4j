package org.uet.dse.ocl2cypher.input;

import java.util.Objects;

/** One named OCL invariant extracted from a USE model or its OCL constraints file. */
public record SourceInvariant(String contextClassKey, String name, String body) {
    public SourceInvariant {
        contextClassKey = requireText(contextClassKey, "context class key");
        name = requireText(name, "invariant name");
        body = requireText(body, "invariant body");
    }

    /** Source accepted by {@code FrontendCompiler}. */
    public String oclText() {
        return "context " + contextClassKey + " inv " + name + ": " + body;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
