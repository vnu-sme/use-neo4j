package org.uet.dse.ocl2cypher.source.model;

import java.util.List;
import java.util.Objects;

/**
 * A binary UML association declaration. The canonical end order is the model
 * order established by the schema; logical direction (from → to) is the
 * observation layer's concern, not the physical relationship direction.
 */
public final class UmlAssociation {

    /** UML association category preserved by every input adapter and graph rule. */
    public enum AssociationKind {
        ASSOCIATION,
        AGGREGATION,
        COMPOSITION
    }

    /** The association end that owns the qualifier declarations. */
    public enum QualifierEnd {
        NONE,
        SOURCE,
        TARGET,
        BOTH
    }

    private final String key;
    private final String name;
    private final String sourceClassKey;
    private final String sourceRole;
    private final int sourceLower;
    private final int sourceUpper;
    private final String targetClassKey;
    private final String targetRole;
    private final int targetLower;
    private final int targetUpper;
    private final List<UmlQualifier> qualifiers;
    private final List<UmlQualifier> sourceEndQualifiers;
    private final List<UmlQualifier> targetEndQualifiers;
    private final QualifierEnd qualifierEnd;
    private final AssociationKind associationKind;
    private final boolean ordered;
    private final boolean unique;

    /**
     * @param sourceUpper {@code -1} denotes unbounded {@code *}
     * @param targetUpper {@code -1} denotes unbounded {@code *}
     */
    public UmlAssociation(String key,
                          String name,
                          String sourceClassKey,
                          String sourceRole,
                          int sourceLower,
                          int sourceUpper,
                          String targetClassKey,
                          String targetRole,
                          int targetLower,
                          int targetUpper,
                          List<?> qualifierDeclarations,
                          boolean ordered,
                          boolean unique) {
        this(key, name, sourceClassKey, sourceRole, sourceLower, sourceUpper,
                targetClassKey, targetRole, targetLower, targetUpper,
                qualifierDeclarations,
                qualifierDeclarations.isEmpty() ? QualifierEnd.NONE : QualifierEnd.TARGET,
                ordered, unique, AssociationKind.ASSOCIATION);
    }

    public UmlAssociation(String key,
                          String name,
                          String sourceClassKey,
                          String sourceRole,
                          int sourceLower,
                          int sourceUpper,
                          String targetClassKey,
                          String targetRole,
                          int targetLower,
                          int targetUpper,
                          List<?> qualifierDeclarations,
                          QualifierEnd qualifierEnd,
                          boolean ordered,
                          boolean unique,
                          AssociationKind associationKind) {
        this(key, name, sourceClassKey, sourceRole, sourceLower, sourceUpper,
                targetClassKey, targetRole, targetLower, targetUpper,
                qualifierEnd == QualifierEnd.SOURCE ? qualifierDeclarations : List.of(),
                qualifierEnd == QualifierEnd.TARGET ? qualifierDeclarations : List.of(),
                ordered, unique, associationKind);
        if (qualifierEnd == QualifierEnd.BOTH) {
            throw new IllegalArgumentException(
                    "the legacy qualifier constructor cannot encode BOTH ends");
        }
    }

    /**
     * Full binary-association constructor. Qualifiers declared for the target
     * end constrain forward source-to-target navigation; qualifiers declared
     * for the source end constrain reverse target-to-source navigation.
     */
    public UmlAssociation(String key,
                          String name,
                          String sourceClassKey,
                          String sourceRole,
                          int sourceLower,
                          int sourceUpper,
                          String targetClassKey,
                          String targetRole,
                          int targetLower,
                          int targetUpper,
                          List<?> sourceEndQualifierDeclarations,
                          List<?> targetEndQualifierDeclarations,
                          boolean ordered,
                          boolean unique,
                          AssociationKind associationKind) {
        this.key = Objects.requireNonNull(key, "association key");
        this.name = Objects.requireNonNull(name, "association name");
        this.sourceClassKey = Objects.requireNonNull(sourceClassKey, "source class");
        this.sourceRole = Objects.requireNonNull(sourceRole, "source role");
        this.sourceLower = sourceLower;
        this.sourceUpper = sourceUpper;
        this.targetClassKey = Objects.requireNonNull(targetClassKey, "target class");
        this.targetRole = Objects.requireNonNull(targetRole, "target role");
        this.targetLower = targetLower;
        this.targetUpper = targetUpper;
        this.sourceEndQualifiers = typedQualifiers(sourceEndQualifierDeclarations,
                "source-end qualifiers");
        this.targetEndQualifiers = typedQualifiers(targetEndQualifierDeclarations,
                "target-end qualifiers");
        java.util.ArrayList<UmlQualifier> allQualifiers = new java.util.ArrayList<>(
                sourceEndQualifiers);
        allQualifiers.addAll(targetEndQualifiers);
        this.qualifiers = List.copyOf(allQualifiers);
        this.qualifierEnd = sourceEndQualifiers.isEmpty()
                ? targetEndQualifiers.isEmpty() ? QualifierEnd.NONE : QualifierEnd.TARGET
                : targetEndQualifiers.isEmpty() ? QualifierEnd.SOURCE : QualifierEnd.BOTH;
        this.associationKind = Objects.requireNonNull(associationKind, "association kind");
        this.ordered = ordered;
        this.unique = unique;
    }

    private static List<UmlQualifier> typedQualifiers(List<?> declarations, String label) {
        Objects.requireNonNull(declarations, label);
        java.util.ArrayList<UmlQualifier> typed = new java.util.ArrayList<>();
        for (Object declaration : declarations) {
            if (declaration instanceof UmlQualifier qualifier) {
                typed.add(qualifier);
            } else if (declaration instanceof String qualifierName) {
                throw new IllegalArgumentException("qualifier '" + qualifierName
                        + "' requires a declared type; use UmlQualifier");
            } else {
                throw new IllegalArgumentException("unsupported qualifier declaration "
                        + declaration);
            }
        }
        return List.copyOf(typed);
    }

    public static UmlAssociation binary(String name,
                                        String sourceClass,
                                        String sourceRole,
                                        String targetClass,
                                        String targetRole) {
        return new UmlAssociation(name, name,
                sourceClass, sourceRole, 0, 1,
                targetClass, targetRole, 0, -1,
                List.of(), false, true);
    }

    public static UmlAssociation oneToOne(String name,
                                          String sourceClass,
                                          String sourceRole,
                                          String targetClass,
                                          String targetRole) {
        return new UmlAssociation(name, name,
                sourceClass, sourceRole, 0, 1,
                targetClass, targetRole, 0, 1,
                List.of(), false, true);
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public String sourceClassKey() {
        return sourceClassKey;
    }

    public String sourceRole() {
        return sourceRole;
    }

    public int sourceLower() {
        return sourceLower;
    }

    public int sourceUpper() {
        return sourceUpper;
    }

    public String targetClassKey() {
        return targetClassKey;
    }

    public int targetLower() {
        return targetLower;
    }

    public int targetUpper() {
        return targetUpper;
    }

    public String targetRole() {
        return targetRole;
    }

    public boolean isOrdered() {
        return ordered;
    }

    public boolean isUnique() {
        return unique;
    }

    public List<UmlQualifier> qualifiers() {
        return qualifiers;
    }

    public QualifierEnd qualifierEnd() {
        return qualifierEnd;
    }

    public AssociationKind associationKind() {
        return associationKind;
    }

    public List<UmlQualifier> sourceEndQualifiers() {
        return sourceEndQualifiers;
    }

    public List<UmlQualifier> targetEndQualifiers() {
        return targetEndQualifiers;
    }

    /**
     * Qualifiers owned by the target end constrain forward source-to-target
     * navigation; source-end qualifiers constrain reverse navigation.
     */
    public List<UmlQualifier> qualifiersForNavigation(boolean reverse) {
        return reverse ? sourceEndQualifiers : targetEndQualifiers;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UmlAssociation a && a.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        String mult = targetUpper == -1 ? "*" : String.valueOf(targetUpper);
        return name + " : " + sourceClassKey + "[" + sourceRole + "] --["
                + targetRole + ":" + targetClassKey + "[" + mult + "]]";
    }
}
