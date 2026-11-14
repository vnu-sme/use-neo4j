package org.uet.dse.ocl2cypher.cypher;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.uet.dse.ocl2cypher.core.CoreDeclaration;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphValueCodec;
import org.uet.dse.ocl2cypher.qcyp.QNode;

/** Checked range witnesses, conditional on exact in-range Cypher integer ops.
 * Stored attribute payloads are checked against the concrete graph by Realization
 * before this conservative [MIN,MAX] certificate is consumed. Numeric variables
 * inherit the INT64 invariant from their already-certified realization binding. */
final class IntegerRangeCertificates {
    static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    record Certificate(BigInteger lower, BigInteger upper) { }

    private final GraphModel graph;

    IntegerRangeCertificates() {
        this(null);
    }

    IntegerRangeCertificates(GraphModel graph) {
        this.graph = graph;
    }

    Optional<Certificate> certify(QNode.QExpr expression) {
        return certify(expression, ignored -> Optional.empty());
    }

    Optional<Certificate> certify(QNode.QExpr e,
            Function<CoreDeclaration, Optional<Certificate>> variables) {
        if (!e.type.isInteger()) return Optional.empty();
        if (e instanceof QNode.QExpr.Unary u
                && u.operator == org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.COLLECTION_SUM)
            return sum(u.operand, variables);
        if (e instanceof QNode.QExpr.CountFamily c && c.countKind == QNode.QKind.SUM)
            return sum(c.source, variables);
        if (e instanceof QNode.QExpr.Constant c && c.literalValue instanceof BigInteger n) {
            return bounded(n, n);
        }
        if (e instanceof QNode.QExpr.Unary u
                && u.operator == org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.NUMERIC_ABS) {
            return certify(u.operand, variables).flatMap(child -> {
                BigInteger lo = child.lower.signum() >= 0 ? child.lower
                        : child.upper.signum() <= 0 ? child.upper.negate() : BigInteger.ZERO;
                BigInteger hi = child.lower.abs().max(child.upper.abs());
                return bounded(lo, hi);
            });
        }
        if (e instanceof QNode.QExpr.Unary u
                && u.operator == org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.NUMERIC_NEGATE) {
            return certify(u.operand, variables).flatMap(child -> bounded(
                    child.upper.negate(), child.lower.negate()));
        }
        if (e instanceof QNode.QExpr.Unary u
                && u.operator == org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.COLLECTION_SIZE) {
            // Collection size is always a non-negative integer bounded by Neo4j graph cardinality.
            return bounded(BigInteger.ZERO, MAX);
        }
        if (e instanceof QNode.QExpr.IfExpr ifExpr && ifExpr.type.equals(org.uet.dse.ocl2cypher.runtime.OclType.INTEGER)) {
            var thenCert = certify(ifExpr.thenExpr, variables);
            var elseCert = certify(ifExpr.elseExpr, variables);
            if (thenCert.isPresent() && elseCert.isPresent()) {
                var t = thenCert.get(); var el = elseCert.get();
                return bounded(t.lower.min(el.lower), t.upper.max(el.upper));
            }
        }
        if (e instanceof QNode.QExpr.Let let) {
            Optional<Certificate> value = certify(let.value, variables);
            Function<CoreDeclaration, Optional<Certificate>> inner = declaration ->
                    declaration == let.binder ? value : variables.apply(declaration);
            return certify(let.body, inner);
        }
        if (e instanceof QNode.QExpr.ReadAttribute attribute) {
            return storedAttribute(attribute);
        }
        if (e instanceof QNode.QExpr.Variable variable) {
            Optional<Certificate> bound = variables.apply(variable.declaration);
            if (bound.isPresent()) return bound;
            return bounded(MIN, MAX);
        }
        if (e instanceof QNode.QExpr.Parameter) {
            return bounded(MIN, MAX);
        }
        if (!(e instanceof QNode.QExpr.Binary b)) return Optional.empty();
        // Unsupported operators never acquire a certificate merely from a type.
        switch (b.operator) {
            case NUMERIC_ADD, NUMERIC_SUBTRACT, NUMERIC_MULTIPLY, NUMERIC_MIN, NUMERIC_MAX,
                 INTEGER_DIVIDE, INTEGER_MOD -> { }
            default -> { return Optional.empty(); }
        }
        var left = certify(b.left, variables);
        var right = certify(b.right, variables);
        if (left.isEmpty() || right.isEmpty()) return Optional.empty();
        var l = left.get(); var r = right.get();
        BigInteger lo, hi;
        switch (b.operator) {
            case INTEGER_DIVIDE, INTEGER_MOD -> {
                return divideOrModulo(b.operator, l, r);
            }
            case NUMERIC_MIN -> { lo = l.lower.min(r.lower); hi = l.upper.min(r.upper); }
            case NUMERIC_MAX -> { lo = l.lower.max(r.lower); hi = l.upper.max(r.upper); }
            case NUMERIC_ADD -> { lo = l.lower.add(r.lower); hi = l.upper.add(r.upper); }
            case NUMERIC_SUBTRACT -> { lo = l.lower.subtract(r.upper); hi = l.upper.subtract(r.lower); }
            case NUMERIC_MULTIPLY -> {
                var corners = List.of(l.lower.multiply(r.lower), l.lower.multiply(r.upper),
                        l.upper.multiply(r.lower), l.upper.multiply(r.upper));
                lo = corners.stream().min(BigInteger::compareTo).orElseThrow();
                hi = corners.stream().max(BigInteger::compareTo).orElseThrow();
            }
            default -> throw new IllegalStateException("checked above");
        }
        return bounded(lo, hi);
    }

    private static Optional<Certificate> bounded(BigInteger lo, BigInteger hi) {
        if (lo.compareTo(MIN) < 0 || hi.compareTo(MAX) > 0 || lo.compareTo(hi) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Certificate(lo, hi));
    }

    private Optional<Certificate> sum(QNode.QExpr source,
            Function<CoreDeclaration, Optional<Certificate>> variables) {
        if (!(source instanceof QNode.QExpr.CollectionLiteral literal)
                || !source.type.isCollection() || !source.type.elementType().isInteger())
            return Optional.empty();
        boolean set = literal.collectionKind == org.uet.dse.ocl2cypher.core.CoreExpr.CollectionKind.SET;
        if (!source.type.equals(set ? org.uet.dse.ocl2cypher.runtime.OclType.set(
                org.uet.dse.ocl2cypher.runtime.OclType.INTEGER)
                : org.uet.dse.ocl2cypher.runtime.OclType.bag(org.uet.dse.ocl2cypher.runtime.OclType.INTEGER)))
            return Optional.empty();
        var seen = new java.util.HashSet<BigInteger>();
        BigInteger acc = BigInteger.ZERO;

        for (QNode.QExpr element : literal.elements) {
            var child = certify(element, variables);
            // Bottom has no numeric interval: do not invent a zero witness.
            if (child.isEmpty() || !child.get().lower.equals(child.get().upper)) return Optional.empty();

            BigInteger value = child.get().lower;
            if (set && !seen.add(value)) continue;
            acc = acc.add(value);
            var prefix = bounded(acc, acc);
            if (prefix.isEmpty()) return Optional.empty();

        }
        return bounded(acc, acc);
    }

    /** Exact minimum/maximum over the finite snapshot carrier for one slot. */
    private Optional<Certificate> storedAttribute(QNode.QExpr.ReadAttribute attribute) {
        if (graph == null) {
            return bounded(MIN, MAX);
        }
        String attributeKey = attribute.ownerClassKey + "::" + attribute.attributeName;
        BigInteger lower = null;
        BigInteger upper = null;
        for (GraphModel.Node node : graph.nodes()) {
            if (!"ATTRIBUTE_VALUE".equals(node.observationRole())
                    || !attributeKey.equals(node.properties().get("attributeKey"))
                    || !GraphValueCodec.DEFINED.equals(
                            node.properties().get(GraphValueCodec.VALUE_STATE))) {
                continue;
            }
            String payload = node.properties().get(GraphValueCodec.PAYLOAD);
            try {
                BigInteger value = new BigInteger(payload);
                if (!value.toString().equals(payload)) return Optional.empty();
                if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) {
                    return Optional.empty();
                }
                lower = lower == null ? value : lower.min(value);
                upper = upper == null ? value : upper.max(value);
            } catch (NumberFormatException malformed) {
                return Optional.empty();
            }
        }
        // No defined slot means every evaluation is bottom.  [0,0] is a
        // vacuous safe payload interval and does not turn bottom into zero.
        if (lower == null) {
            return bounded(BigInteger.ZERO, BigInteger.ZERO);
        }
        return bounded(lower, upper);
    }

    private Optional<Certificate> divideOrModulo(
            org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp operator,
            Certificate dividend, Certificate divisor) {
        // The interval premise deliberately excludes zero.  This permits a
        // direct native operation without relying on row-dependent guards as
        // part of the range proof.
        if (divisor.lower.signum() <= 0 && divisor.upper.signum() >= 0) {
            return Optional.empty();
        }
        List<BigInteger> numerators = candidates(dividend.lower, dividend.upper);
        List<BigInteger> denominators = candidates(divisor.lower, divisor.upper);
        BigInteger quotientLo = null;
        BigInteger quotientHi = null;
        for (BigInteger a : numerators) {
            for (BigInteger b : denominators) {
                BigInteger q = a.divide(b); // OCL/Java truncation toward zero
                quotientLo = quotientLo == null ? q : quotientLo.min(q);
                quotientHi = quotientHi == null ? q : quotientHi.max(q);
            }
        }
        var quotient = bounded(quotientLo, quotientHi);
        if (quotient.isEmpty()) return Optional.empty(); // includes MIN/-1
        if (operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.INTEGER_DIVIDE) {
            if (dividend.lower.equals(dividend.upper)
                    && divisor.lower.equals(divisor.upper)) {
                BigInteger productValue = divisor.lower.multiply(quotientLo);
                var product = bounded(productValue, productValue);
                if (product.isEmpty()) return Optional.empty();
                return bounded(quotientLo, quotientHi);
            }
            return bounded(quotientLo, quotientHi);
        }

        BigInteger maxAbsDivisor = divisor.lower.abs().max(divisor.upper.abs());
        BigInteger magnitude = maxAbsDivisor.subtract(BigInteger.ONE);
        BigInteger remainderLo = dividend.lower.signum() >= 0
                ? BigInteger.ZERO : magnitude.negate();
        BigInteger remainderHi = dividend.upper.signum() <= 0
                ? BigInteger.ZERO : magnitude;
        if (dividend.lower.equals(dividend.upper)
                && divisor.lower.equals(divisor.upper)) {
            BigInteger productValue = divisor.lower.multiply(quotientLo);
            var product = bounded(productValue, productValue);
            if (product.isEmpty()) return Optional.empty();
            BigInteger exact = dividend.lower.remainder(divisor.lower);
            return bounded(exact, exact);
        }
        return bounded(remainderLo, remainderHi);
    }

    private static List<BigInteger> candidates(BigInteger lower, BigInteger upper) {
        List<BigInteger> values = new ArrayList<>();
        values.add(lower);
        if (!upper.equals(lower)) values.add(upper);
        if (lower.signum() < 0 && upper.signum() >= 0) values.add(BigInteger.ZERO);
        if (lower.compareTo(BigInteger.ONE) <= 0 && upper.compareTo(BigInteger.ONE) >= 0)
            values.add(BigInteger.ONE);
        if (lower.compareTo(BigInteger.ONE.negate()) <= 0
                && upper.compareTo(BigInteger.ONE.negate()) >= 0)
            values.add(BigInteger.ONE.negate());
        return values;
    }
}
