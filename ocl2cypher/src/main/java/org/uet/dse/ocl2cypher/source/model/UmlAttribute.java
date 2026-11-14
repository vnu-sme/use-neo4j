package org.uet.dse.ocl2cypher.source.model;

import java.util.Objects;
import org.uet.dse.ocl2cypher.runtime.OclType;

/**
 * A scalar attribute declaration owned by one class.
 *
 * <p>The current OCL_val profile observes scalar attributes only; a
 * multi-valued UML attribute is rejected before any graph observer is built
 * (Rule 03 {@code MM-OBS-ATTRIBUTE} / {@code MM_UNSUPPORTED_VALUE_TYPE}).
 */
public record UmlAttribute(String key, String name, String ownerClassKey, OclType declaredType) {

    public UmlAttribute {
        Objects.requireNonNull(key, "attribute key");
        Objects.requireNonNull(name, "attribute name");
        Objects.requireNonNull(ownerClassKey, "owner class key");
        Objects.requireNonNull(declaredType, "declared type");
        if (declaredType.isCollection()) {
            throw new IllegalArgumentException(
                    "OCL_val observes scalar attributes only: " + name + " : " + declaredType);
        }
    }

    public static UmlAttribute of(String ownerClass, String name, OclType type) {
        return new UmlAttribute(ownerClass + "::" + name, name, ownerClass, type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UmlAttribute a && a.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key + " : " + declaredType;
    }
}
