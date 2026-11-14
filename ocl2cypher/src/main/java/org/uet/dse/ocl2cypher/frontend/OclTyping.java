package org.uet.dse.ocl2cypher.frontend;

import static org.uet.dse.ocl2cypher.frontend.FrontendError.error;
import java.util.List;
import org.uet.dse.ocl2cypher.diagnostics.SourceSpan;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.omg.OmgAs;
import org.uet.dse.ocl2cypher.source.omg.OclOperation;

/** Schema-based typing, independent of the parser cursor. */
final class OclTyping {
    private final SchemaModel sm;

    OclTyping(SchemaModel sm) {
        this.sm = sm;
    }

    OmgAs.OclExpression binary(OclOperation op, OmgAs.OclExpression left,
            OmgAs.OclExpression right, OclType result, SourceSpan sp) {
        return operation(sp, op, List.of(left, right), result);
    }

    OmgAs.OclExpression operation(SourceSpan span, OclOperation operation,
            List<OmgAs.OclExpression> operands, OclType result) {
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("resolved operation requires a source");
        }
        return new OmgAs.OperationCallExp(span, result, operands.get(0),
                operation.name(), operands.subList(1, operands.size()));
    }

    /**
     * True when a collection expression depends on an ordered association end.
     * The executable profile intentionally erases order to Set/Bag for
     * permutation-invariant operations. Equality would make that erasure
     * observable, so it is rejected fail-closed, including through let,
     * iterator, operation, if, and coercion wrappers.
     */
    boolean containsOrderedNavigation(OmgAs.OclExpression expression,
                                              java.util.Set<Object> visited) {
        if (expression == null || !visited.add(expression)) {
            return false;
        }
        if (expression instanceof OmgAs.PropertyCallExp property
                && property.type() != null && property.type().isCollection()) {
            var association = associationByKey(property.referredProperty);
            if (association != null && association.isOrdered()) {
                return true;
            }
        }
        if (expression instanceof OmgAs.AssociationClassCallExp navigation
                && navigation.type() != null && navigation.type().isCollection()) {
            var association = associationByKey(navigation.referredAssociationClass);
            if (association != null && association.isOrdered()) {
                return true;
            }
        }
        if (expression instanceof OmgAs.VariableExp variable) {
            return containsOrderedNavigation(variable.referredVariable.initExpression, visited);
        }
        if (expression instanceof OmgAs.OperationCallExp operation) {
            if (containsOrderedNavigation(operation.source, visited)) return true;
            for (var argument : operation.argument) {
                if (containsOrderedNavigation(argument, visited)) return true;
            }
            return false;
        }
        if (expression instanceof OmgAs.IteratorExp iterator) {
            return containsOrderedNavigation(iterator.source, visited)
                    || containsOrderedNavigation(iterator.body, visited);
        }
        if (expression instanceof OmgAs.IfExp conditional) {
            return containsOrderedNavigation(conditional.thenExpression, visited)
                    || containsOrderedNavigation(conditional.elseExpression, visited);
        }
        if (expression instanceof OmgAs.LetExp let) {
            // Follow the initializer only through a VariableExp that actually
            // refers to the binder. An unused ordered initializer cannot make
            // an otherwise independent collection result order-sensitive.
            return containsOrderedNavigation(let.in, visited);
        }
        if (expression instanceof OmgAs.CoerceExp coercion) {
            return containsOrderedNavigation(coercion.sourceExpression, visited);
        }
        return false;
    }

    private org.uet.dse.ocl2cypher.source.model.UmlAssociation associationByKey(
            String key) {
        for (var association : sm.associations()) {
            if (association.key().equals(key)) {
                return association;
            }
        }
        return null;
    }

    OmgAs.OclExpression coerce(OmgAs.OclExpression e, OclType to, SourceSpan sp) {
        if (isUntypedEmptyCollection(e)) {
            return typeEmptyCollection(e, to, sp);
        }
        if (e.type() != null && e.type().equals(to)) {
            return e;
        }
        if (e.type() == null) {
            throw error("E_TYPE", "unadmitted operand cannot be coerced", sp);
        }
        // Only Integer→Real and class upcast change the value carrier at AS level.
        if (e.type().equals(OclType.INTEGER) && to.equals(OclType.REAL)) {
            return new OmgAs.CoerceExp(sp, e, e.type(), to,
                    org.uet.dse.ocl2cypher.core.CoreExpr.CoercionKind.INTEGER_TO_REAL);
        }
        if (e.type().isClass() && to.isClass() && conforms(e.type(), to)) {
            return new OmgAs.CoerceExp(sp, e, e.type(), to,
                    org.uet.dse.ocl2cypher.core.CoreExpr.CoercionKind.CLASS_UPCAST);
        }
        if (e.type().isCollection() && to.isCollection()
                && e.type().kind() == to.kind()
                && ((e.type().elementType().equals(OclType.INTEGER)
                        && to.elementType().equals(OclType.REAL))
                    || (e.type().elementType().isClass()
                        && to.elementType().isClass()
                        && conforms(e.type().elementType(), to.elementType())))) {
            return new OmgAs.CoerceExp(sp, e, e.type(), to,
                    org.uet.dse.ocl2cypher.core.CoreExpr.CoercionKind.COLLECTION_ELEMENT_COERCION);
        }
        throw error("E_TYPE", "unsupported coercion from " + e.type() + " to " + to, sp);
    }

    OmgAs.OclExpression[] inferEmptyCollectionPair(OmgAs.OclExpression left,
            OmgAs.OclExpression right, SourceSpan sp) {
        if (isUntypedEmptyCollection(left) && right.type() != null) {
            left = typeEmptyCollection(left, right.type(), sp);
        }
        if (isUntypedEmptyCollection(right) && left.type() != null) {
            right = typeEmptyCollection(right, left.type(), sp);
        }
        if (left.type() == null || right.type() == null) {
            throw error("E_UNINFERRED_EMPTY_COLLECTION_TYPE",
                    "empty collection literal requires an expected collection type", sp);
        }
        return new OmgAs.OclExpression[]{left, right};
    }

    boolean isUntypedEmptyCollection(OmgAs.OclExpression expression) {
        return expression instanceof OmgAs.CollectionLiteralExp literal
                && literal.part.isEmpty() && literal.type() == null;
    }

    OclType collectionTypeLike(OmgAs.OclExpression untypedEmpty, OclType elementType) {
        OmgAs.CollectionLiteralExp literal = (OmgAs.CollectionLiteralExp) untypedEmpty;
        return literal.kind == OmgAs.OmgCollectionKind.SET
                ? OclType.set(elementType) : OclType.bag(elementType);
    }

    OmgAs.OclExpression typeEmptyCollection(OmgAs.OclExpression expression,
                                                     OclType expected, SourceSpan sp) {
        if (!isUntypedEmptyCollection(expression)) {
            return expression;
        }
        OmgAs.CollectionLiteralExp literal = (OmgAs.CollectionLiteralExp) expression;
        if (expected == null || !expected.isCollection()
                || (literal.kind == OmgAs.OmgCollectionKind.SET
                        && expected.kind() != OclType.Kind.SET)
                || (literal.kind == OmgAs.OmgCollectionKind.BAG
                        && expected.kind() != OclType.Kind.BAG)) {
            throw error("E_TYPE", "empty " + literal.kind
                    + " requires a matching expected collection type, found " + expected, sp);
        }
        return new OmgAs.CollectionLiteralExp(literal.span(), literal.kind, List.of(), expected);
    }

    OclType joinType(OclType a, OclType b, SourceSpan sp) {
        if (a == null || b == null) {
            throw error("E_TYPE", "unadmitted operand in join", sp);
        }
        OclType j = OclType.join(a, b, sm::conforms);
        if (j == null) {
            throw error("E_TYPE", "no common type for " + a + " and " + b, sp);
        }
        return j;
    }

    boolean conforms(OclType from, OclType to) {
        return from.equals(to)
                || (from.equals(OclType.INTEGER) && to.equals(OclType.REAL))
                || (from.isClass() && to.isClass() && sm.conforms(from.className(), to.className()));
    }

    void requireNumeric(OclType t, SourceSpan sp) {
        if (t == null || !t.isNumeric()) {
            throw error("E_TYPE", "numeric operand required, found " + t, sp);
        }
    }

    void requireBoolean(OclType t, SourceSpan sp) {
        if (t == null || !t.equals(OclType.BOOLEAN)) {
            throw error("E_TYPE", "Boolean operand required, found " + t, sp);
        }
    }

    void requireReal(OclType t, SourceSpan sp) {
        if (t == null || !t.equals(OclType.REAL)) {
            throw error("E_TYPE", "Real operand required, found " + t, sp);
        }
    }

    void requireInteger(OclType t, SourceSpan sp) {
        if (t == null || !t.equals(OclType.INTEGER)) {
            throw error("E_TYPE", "Integer operand required, found " + t, sp);
        }
    }
}
