package org.uet.dse.ocl2cypher.source.model;

import java.util.List;
import java.util.Objects;

/**
 * A UML class declaration inside one schema model {@code SM}.
 *
 * <p>Identity is the stable {@code umlElementKey}; the qualified name is
 * observation metadata used for diagnostics and for canonical type names. This
 * matches {@code ResolvedClass} in Typed-Core-OCL-IR.emf, where declaration
 * identity — not the surface name — decides equality.
 */
public record UmlClass(String key, String qualifiedName, boolean isAbstract,
                       boolean isAssociationClass, List<String> directSuperclassKeys) {

    public UmlClass {
        Objects.requireNonNull(key, "class key");
        Objects.requireNonNull(qualifiedName, "qualified name");
        directSuperclassKeys = List.copyOf(
                Objects.requireNonNull(directSuperclassKeys, "superclasses"));
    }

    public static UmlClass of(String name) {
        return new UmlClass(name, name, false, false, List.of());
    }

    public static UmlClass of(String name, String... superKeys) {
        return new UmlClass(name, name, false, false, List.of(superKeys));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UmlClass c && c.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return qualifiedName;
    }
}
