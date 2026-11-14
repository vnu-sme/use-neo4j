package org.uet.dse.ocl2cypher.source.model;

import java.util.Objects;
import java.util.Set;
import org.uet.dse.ocl2cypher.source.omg.OclOperation;

/** Closed operation allowlist. New enum values remain rejected by default. */
public final class CapabilityMatrix {
    private CapabilityMatrix() { }

    private static final Set<OclOperation> SUPPORTED = Set.of(
            OclOperation.BOOLEAN_NOT,
            OclOperation.OCL_IS_UNDEFINED,
            OclOperation.BOOLEAN_AND,
            OclOperation.BOOLEAN_OR,
            OclOperation.BOOLEAN_XOR,
            OclOperation.BOOLEAN_IMPLIES,
            OclOperation.VALUE_EQUAL,
            OclOperation.VALUE_NOT_EQUAL,
            OclOperation.LESS_THAN,
            OclOperation.LESS_THAN_OR_EQUAL,
            OclOperation.GREATER_THAN,
            OclOperation.GREATER_THAN_OR_EQUAL,
            OclOperation.NUMERIC_ADD,
            OclOperation.NUMERIC_SUBTRACT,
            OclOperation.NUMERIC_MULTIPLY,
            OclOperation.REAL_DIVIDE,
            OclOperation.INTEGER_DIVIDE,
            OclOperation.INTEGER_MOD,
            OclOperation.NUMERIC_NEGATE,
            OclOperation.NUMERIC_ABS,
            OclOperation.REAL_FLOOR,
            OclOperation.REAL_ROUND,
            OclOperation.NUMERIC_MAX,
            OclOperation.NUMERIC_MIN,
            OclOperation.COLLECTION_SIZE,
            OclOperation.COLLECTION_IS_EMPTY,
            OclOperation.COLLECTION_NOT_EMPTY,
            OclOperation.COLLECTION_SUM,
            OclOperation.COLLECTION_COUNT,
            OclOperation.COLLECTION_INCLUDES,
            OclOperation.COLLECTION_EXCLUDES,
            OclOperation.COLLECTION_INCLUDES_ALL,
            OclOperation.COLLECTION_EXCLUDES_ALL,
            OclOperation.SET_UNION,
            OclOperation.SET_INTERSECTION,
            OclOperation.ALL_INSTANCES,
            OclOperation.OCL_IS_TYPE_OF,
            OclOperation.OCL_IS_KIND_OF,
            OclOperation.OCL_AS_TYPE);

    public static boolean isAdmitted(OclOperation operation) {
        return SUPPORTED.contains(Objects.requireNonNull(operation, "operation"));
    }
}
