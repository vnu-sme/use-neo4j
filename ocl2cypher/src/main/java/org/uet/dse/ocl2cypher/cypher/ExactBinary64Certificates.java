package org.uet.dse.ocl2cypher.cypher;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.uet.dse.ocl2cypher.core.CoreDeclaration;
import org.uet.dse.ocl2cypher.core.CoreExpr;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphValueCodec;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.runtime.ExactReal;
import org.uet.dse.ocl2cypher.runtime.OclType;

/**
 * Snapshot-bound finite-domain witnesses for exact IEEE-754 binary64
 * realization.
 *
 * <p>The source Real carrier remains exact.  A certificate is produced only
 * when every defined value of every Real subexpression is both finite and
 * exactly representable as a {@code double}.  Consequently native Cypher
 * floating-point arithmetic is used only where it denotes the same exact
 * value as the source operation.  Empty value sets are valid: they mean that
 * the expression is bottom for every relevant snapshot occurrence.
 */
final class ExactBinary64Certificates {
    private static final int MAX_DOMAIN_VALUES = 100_000;

    record Certificate(Set<ExactReal> values) {
        Certificate { values = Set.copyOf(values); }
    }

    private final GraphModel graph;

    ExactBinary64Certificates(GraphModel graph) {
        this.graph = graph;
    }

    Optional<Certificate> certify(QNode.QExpr expression,
            Function<CoreDeclaration, Optional<Certificate>> variables) {
        if (!expression.type.isNumeric()) return Optional.empty();

        if (expression instanceof QNode.QExpr.Constant constant) {
            ExactReal value;
            if (constant.literalValue instanceof BigInteger integer) {
                value = ExactReal.of(integer);
            } else if (constant.literalValue instanceof BigDecimal real) {
                value = ExactReal.of(real);
            } else {
                return Optional.empty();
            }
            return certificate(expression.type, Set.of(value));
        }
        if (expression instanceof QNode.QExpr.ReadAttribute attribute) {
            return storedAttribute(attribute);
        }
        if (expression instanceof QNode.QExpr.Variable variable) {
            return variables.apply(variable.declaration);
        }
        // Runtime parameters have a type/leaf check at the execution boundary,
        // but no finite value domain from which an arithmetic witness can be
        // derived at realization time.
        if (expression instanceof QNode.QExpr.Parameter) return Optional.empty();

        if (expression instanceof QNode.QExpr.Coerce coercion
                && coercion.kind == CoreExpr.CoercionKind.INTEGER_TO_REAL) {
            return certify(coercion.source, variables).flatMap(source ->
                    certificate(OclType.REAL, source.values()));
        }
        if (expression instanceof QNode.QExpr.IfExpr conditional) {
            var thenCertificate = certify(conditional.thenExpr, variables);
            var elseCertificate = certify(conditional.elseExpr, variables);
            if (thenCertificate.isEmpty() || elseCertificate.isEmpty()) {
                return Optional.empty();
            }
            Set<ExactReal> values = new LinkedHashSet<>(thenCertificate.get().values());
            values.addAll(elseCertificate.get().values());
            return certificate(expression.type, values);
        }
        if (expression instanceof QNode.QExpr.Let let) {
            Optional<Certificate> value = certify(let.value, variables);
            Function<CoreDeclaration, Optional<Certificate>> inner = declaration ->
                    declaration == let.binder ? value : variables.apply(declaration);
            return certify(let.body, inner);
        }
        if (expression instanceof QNode.QExpr.Unary unary) {
            var child = certify(unary.operand, variables);
            if (child.isEmpty()) return Optional.empty();
            Set<ExactReal> values = new LinkedHashSet<>();
            for (ExactReal value : child.get().values()) {
                switch (unary.operator) {
                    case NUMERIC_NEGATE -> values.add(value.negate());
                    case NUMERIC_ABS -> values.add(value.signum() < 0 ? value.negate() : value);
                    default -> { return Optional.empty(); }
                }
            }
            return certificate(expression.type, values);
        }
        if (!(expression instanceof QNode.QExpr.Binary binary)) {
            return Optional.empty();
        }
        switch (binary.operator) {
            case NUMERIC_ADD, NUMERIC_SUBTRACT, NUMERIC_MULTIPLY,
                    NUMERIC_MIN, NUMERIC_MAX, REAL_DIVIDE -> { }
            default -> { return Optional.empty(); }
        }
        var left = certify(binary.left, variables);
        var right = certify(binary.right, variables);
        if (left.isEmpty() || right.isEmpty()) return Optional.empty();
        if ((long) left.get().values().size() * right.get().values().size()
                > MAX_DOMAIN_VALUES) {
            return Optional.empty();
        }
        Set<ExactReal> values = new LinkedHashSet<>();
        for (ExactReal a : left.get().values()) {
            for (ExactReal b : right.get().values()) {
                ExactReal result = switch (binary.operator) {
                    case NUMERIC_ADD -> a.add(b);
                    case NUMERIC_SUBTRACT -> a.subtract(b);
                    case NUMERIC_MULTIPLY -> a.multiply(b);
                    case NUMERIC_MIN -> a.compareTo(b) <= 0 ? a : b;
                    case NUMERIC_MAX -> a.compareTo(b) >= 0 ? a : b;
                    case REAL_DIVIDE -> b.signum() == 0 ? null : a.divide(b);
                    default -> throw new IllegalStateException("checked above");
                };
                // Division by zero follows the existing typed-bottom branch and
                // therefore contributes no defined payload value.
                if (result != null) values.add(result);
            }
        }
        return certificate(expression.type, values);
    }

    private Optional<Certificate> storedAttribute(QNode.QExpr.ReadAttribute attribute) {
        String attributeKey = attribute.ownerClassKey + "::" + attribute.attributeName;
        Set<ExactReal> values = new LinkedHashSet<>();
        for (GraphModel.Node node : graph.nodes()) {
            if (!"ATTRIBUTE_VALUE".equals(node.observationRole())
                    || !attributeKey.equals(node.properties().get("attributeKey"))
                    || !GraphValueCodec.DEFINED.equals(
                            node.properties().get(GraphValueCodec.VALUE_STATE))) {
                continue;
            }
            String payload = node.properties().get(GraphValueCodec.PAYLOAD);
            try {
                ExactReal value = attribute.type.equals(OclType.INTEGER)
                        ? ExactReal.of(new BigInteger(payload))
                        : ExactReal.of(new BigDecimal(payload));
                values.add(value);
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }
        return certificate(attribute.type, values);
    }

    private Optional<Certificate> certificate(OclType type, Set<ExactReal> values) {
        if (values.size() > MAX_DOMAIN_VALUES) return Optional.empty();
        if (type.equals(OclType.REAL)) {
            for (ExactReal value : values) {
                if (!isExactlyBinary64(value)) return Optional.empty();
            }
        }
        return Optional.of(new Certificate(values));
    }

    static boolean isExactlyBinary64(ExactReal exact) {
        BigInteger numerator = exact.numerator().abs();
        if (numerator.signum() == 0) return true;
        BigInteger denominator = exact.denominator();
        // ExactReal is reduced, so a binary floating-point value can only have
        // a power-of-two denominator.
        if (denominator.bitCount() != 1) return false;

        // Canonicalize n * 2^e by removing powers of two from n.  A finite
        // binary64 has at most 53 significant bits, a least exponent of -1074
        // (the smallest subnormal), and a greatest set-bit exponent of 1023.
        int numeratorTwos = numerator.getLowestSetBit();
        BigInteger significand = numerator.shiftRight(numeratorTwos);
        long exponent = (long) numeratorTwos - (denominator.bitLength() - 1L);
        long highestSetBitExponent = exponent + significand.bitLength() - 1L;
        return significand.bitLength() <= 53
                && exponent >= -1074
                && highestSetBitExponent <= 1023;
    }
}
