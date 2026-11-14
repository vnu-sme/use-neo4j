package org.uet.dse.ocl2cypher.frontend;

import static org.uet.dse.ocl2cypher.frontend.FrontendError.error;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.uet.dse.ocl2cypher.diagnostics.SourceSpan;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.omg.OmgAs;
import org.uet.dse.ocl2cypher.source.omg.OclOperation;

/** Schema-based resolution, independent of the parser cursor. */
final class OclResolution {
    private final SchemaModel sm;
    private final OclTyping typing;

    OclResolution(SchemaModel sm, OclTyping typing) {
        this.sm = sm;
        this.typing = typing;
    }

    OmgAs.OclExpression resolveProperty(SourceSpan sp, OmgAs.OclExpression receiver,
            String name, List<OmgAs.OclExpression> qualifiers) {
        OclType rt = receiver.type();
        if (rt == null) {
            throw error("E_TYPE", "receiver of '." + name + "' is unadmitted", sp);
        }
        if (!rt.isClass()) {
            throw error("E_INVALID_CALL_SHAPE",
                    "property access requires an object receiver, found " + rt, sp);
        }
        UmlAttribute attr = resolveAttribute(rt.className(), name, sp);
        if (attr != null) {
            if (!qualifiers.isEmpty()) {
                throw error("E_ARITY", "attributes take no qualifiers", sp);
            }
            return new OmgAs.PropertyCallExp(sp, attr.declaredType(), receiver,
                    List.of(), name, attr.key());
        }
        org.uet.dse.ocl2cypher.source.model.SchemaModel.AssociationClassNavigation acNavigation;
        try {
            acNavigation = sm.associationClassNavigation(rt.className(), name);
        } catch (IllegalArgumentException ambiguous) {
            throw error("E_RESOLUTION", ambiguous.getMessage(), sp);
        }
        if (acNavigation != null) {
            var assoc = acNavigation.association();
            validateQualifiers(sp, name, qualifiers, assoc,
                    acNavigation.receiverIsTarget());
            OclType element = OclType.clazz(acNavigation.associationClass().key());
            OclType result = acNavigation.toMany()
                    ? (acNavigation.unique() ? OclType.set(element) : OclType.bag(element))
                    : element;
            return new OmgAs.AssociationClassCallExp(sp, result, receiver, qualifiers,
                    acNavigation.participantRole(), acNavigation.associationClass().key());
        }
        org.uet.dse.ocl2cypher.source.model.SchemaModel.Navigation navigation;
        try {
            navigation = sm.navigation(rt.className(), name);
        } catch (IllegalArgumentException ambiguous) {
            throw error("E_RESOLUTION", ambiguous.getMessage(), sp);
        }
        if (navigation == null) {
            // navigation must start from one declared end
            throw error("E_RESOLUTION", "role '" + name + "' is not navigable from " + rt, sp);
        }
        var assoc = navigation.association();
        OclType target = OclType.clazz(navigation.targetClassKey());
        OclType type = navigation.toMany()
                ? (navigation.unique() ? OclType.set(target) : OclType.bag(target))
                : target;
        validateQualifiers(sp, name, qualifiers, assoc, navigation.reverse());
        return new OmgAs.PropertyCallExp(sp, type, receiver, qualifiers, name, assoc.key());
    }

    private void validateQualifiers(SourceSpan sp, String name,
                                    List<OmgAs.OclExpression> qualifiers,
                                    org.uet.dse.ocl2cypher.source.model.UmlAssociation assoc,
                                    boolean reverse) {
        List<org.uet.dse.ocl2cypher.source.model.UmlQualifier> expected =
                assoc.qualifiersForNavigation(reverse);
        if (qualifiers.size() != expected.size()) {
            throw error("E_ARITY", "role '" + name + "' expects "
                    + expected.size() + " qualifier(s), found "
                    + qualifiers.size(), sp);
        }
        for (int i = 0; i < qualifiers.size(); i++) {
            OmgAs.OclExpression q = qualifiers.get(i);
            if (q.type() == null || !q.type().isAtomic()) {
                throw error("E_TYPE", "qualifiers must be scalar", sp);
            }
            OclType declaredQualifierType = expected.get(i).declaredType();
            if (!q.type().equals(declaredQualifierType)) {
                throw error("E_TYPE", "qualifier '" + expected.get(i).name()
                        + "' requires " + declaredQualifierType + ", found " + q.type(), sp);
            }
        }
    }

    private UmlAttribute resolveAttribute(String classKey, String attrName, SourceSpan sp) {
        Map<String, UmlAttribute> candidates = new LinkedHashMap<>();
        collectAttributeCandidates(classKey, attrName, new java.util.LinkedHashSet<>(), candidates);
        if (candidates.isEmpty()) {
            return null;
        }
        List<UmlAttribute> mostSpecific = new ArrayList<>(candidates.values());
        mostSpecific.removeIf(a -> candidates.values().stream().anyMatch(b ->
                !a.key().equals(b.key())
                        && sm.conforms(b.ownerClassKey(), a.ownerClassKey())
                        && !sm.conforms(a.ownerClassKey(), b.ownerClassKey())));
        if (mostSpecific.size() != 1) {
            throw error("E_AMBIGUOUS_PROPERTY", "attribute '" + attrName
                    + "' is inherited from multiple unrelated declarations on " + classKey,
                    sp);
        }
        return mostSpecific.get(0);
    }

    private void collectAttributeCandidates(String classKey, String attrName,
                                            java.util.Set<String> visited,
                                            Map<String, UmlAttribute> out) {
        if (classKey == null || !visited.add(classKey)) {
            return;
        }
        UmlAttribute own = sm.attribute(classKey, attrName);
        if (own != null) {
            out.putIfAbsent(own.key(), own);
        }
        var klass = sm.clazz(classKey);
        if (klass != null) {
            for (String superclass : klass.directSuperclassKeys()) {
                collectAttributeCandidates(superclass, attrName, visited, out);
            }
        }
    }

    OmgAs.OclExpression resolveOperation(SourceSpan sp,
            OmgAs.OclExpression receiver, String name,
            List<OmgAs.OclExpression> args, boolean dotCall) {
        // allInstances on a classifier reference arrives via ClassRef handling
        OclType rt = receiver == null ? null : receiver.type();
        switch (name) {
            case "allInstances" -> {
                if (receiver instanceof OmgAs.TypeExp type) {
                    return typing.operation(sp, OclOperation.ALL_INSTANCES, List.of(type),
                            OclType.set(OclType.clazz(type.referredType)));
                }
                throw error("E_INVALID_CALL_SHAPE", "allInstances applies to a classifier", sp);
            }
            case "oclIsTypeOf", "oclIsKindOf", "oclAsType" -> {
                OclOperation op = switch (name) {
                    case "oclIsTypeOf" -> OclOperation.OCL_IS_TYPE_OF;
                    case "oclIsKindOf" -> OclOperation.OCL_IS_KIND_OF;
                    default -> OclOperation.OCL_AS_TYPE;
                };
                if (args.size() != 1 || !(args.get(0) instanceof OmgAs.TypeExp ref)) {
                    throw error("E_ARITY", name + " requires exactly one classifier operand", sp);
                }
                if (rt == null || !rt.isClass()) {
                    throw error("E_TYPE", name + " receiver must be an object", sp);
                }
                OclType result = op.equals(OclOperation.OCL_AS_TYPE)
                        // The source profile admits only related class operands.
                        ? OclType.clazz(ref.referredType) : OclType.BOOLEAN;
                if (!sm.hasClass(ref.referredType)
                        || !(sm.conforms(rt.className(), ref.referredType)
                            || sm.conforms(ref.referredType, rt.className()))) {
                    throw error("E_TYPE", name + " requires related class types", sp);
                }
                return typing.operation(sp, op, List.of(receiver, ref), result);
            }
            default -> {
            }
        }
        if (args.size() == 0) {
            OclOperation u = switch (name) {
                case "not" -> OclOperation.BOOLEAN_NOT;
                case "oclIsUndefined" -> OclOperation.OCL_IS_UNDEFINED;
                case "abs" -> OclOperation.NUMERIC_ABS;
                case "floor" -> OclOperation.REAL_FLOOR;
                case "round" -> OclOperation.REAL_ROUND;
                case "size" -> OclOperation.COLLECTION_SIZE;
                case "isEmpty" -> OclOperation.COLLECTION_IS_EMPTY;
                case "notEmpty" -> OclOperation.COLLECTION_NOT_EMPTY;
                case "sum" -> OclOperation.COLLECTION_SUM;
                default -> null;
            };
            if (u == null) {
                throw error("E_RESOLUTION", "unsupported zero-arg operation '" + name + "'", sp);
            }
            if (!dotCall && requiresArrow(u) && rt != null && rt.isClass()) {
                receiver = optionalObjectToSet(receiver, sp);
                rt = receiver.type();
            }
            if (typing.isUntypedEmptyCollection(receiver)) {
                throw error("E_UNINFERRED_EMPTY_COLLECTION_TYPE",
                        "empty collection literal has no element type for operation '"
                                + name + "'", sp);
            }
            switch (u) {
                case BOOLEAN_NOT -> typing.requireBoolean(rt, sp);
                case OCL_IS_UNDEFINED -> { /* defined for every resolved receiver type */ }
                case NUMERIC_ABS -> typing.requireNumeric(rt, sp);
                case REAL_FLOOR, REAL_ROUND -> typing.requireReal(rt, sp);
                case COLLECTION_SIZE, COLLECTION_IS_EMPTY, COLLECTION_NOT_EMPTY ->
                        requireCollection(rt, sp);
                case COLLECTION_SUM -> {
                    OclType elementType = elementOf(rt, sp);
                    typing.requireNumeric(elementType, sp);
                }
                default -> throw error("E_RESOLUTION", "unhandled op " + u, sp);
            }
            OclType result = switch (u) {
                case BOOLEAN_NOT, OCL_IS_UNDEFINED -> OclType.BOOLEAN;
                case REAL_FLOOR, REAL_ROUND -> OclType.INTEGER;
                case NUMERIC_ABS -> rt;
                case COLLECTION_SIZE -> OclType.INTEGER;
                case COLLECTION_IS_EMPTY, COLLECTION_NOT_EMPTY -> OclType.BOOLEAN;
                case COLLECTION_SUM -> elementOf(rt, sp);
                default -> throw error("E_RESOLUTION", "unhandled op " + u, sp);
            };
            if (dotCall && requiresArrow(u)) {
                throw error("E_INVALID_CALL_SHAPE",
                        "collection operation '" + name + "' requires '->'", sp);
            }
            return typing.operation(sp, u, List.of(receiver), result);
        }
        if (args.size() == 1) {
            OmgAs.OclExpression arg = args.get(0);
            OclOperation b = switch (name) {
                case "div" -> OclOperation.INTEGER_DIVIDE;
                case "mod" -> OclOperation.INTEGER_MOD;
                case "max" -> OclOperation.NUMERIC_MAX;
                case "min" -> OclOperation.NUMERIC_MIN;
                case "count" -> OclOperation.COLLECTION_COUNT;
                case "includes" -> OclOperation.COLLECTION_INCLUDES;
                case "excludes" -> OclOperation.COLLECTION_EXCLUDES;
                case "includesAll" -> OclOperation.COLLECTION_INCLUDES_ALL;
                case "excludesAll" -> OclOperation.COLLECTION_EXCLUDES_ALL;
                case "union" -> OclOperation.SET_UNION;
                case "intersection" -> OclOperation.SET_INTERSECTION;
                default -> null;
            };
            if (b == null) {
                throw error("E_RESOLUTION", "unsupported operation '" + name + "'", sp);
            }
            if (!dotCall && isCollectionOperation(b) && rt != null && rt.isClass()) {
                receiver = optionalObjectToSet(receiver, sp);
                rt = receiver.type();
            }
            if (typing.isUntypedEmptyCollection(receiver)) {
                if (b == OclOperation.COLLECTION_COUNT
                        || b == OclOperation.COLLECTION_INCLUDES
                        || b == OclOperation.COLLECTION_EXCLUDES) {
                    if (arg.type() == null || !arg.type().isAtomic()) {
                        throw error("E_UNINFERRED_EMPTY_COLLECTION_TYPE",
                                "cannot infer empty collection element type from " + arg.type(), sp);
                    }
                    receiver = typing.typeEmptyCollection(receiver, typing.collectionTypeLike(receiver,
                            arg.type()), sp);
                } else if (arg.type() != null && arg.type().isCollection()) {
                    receiver = typing.typeEmptyCollection(receiver, arg.type(), sp);
                }
                rt = receiver.type();
            }
            if (typing.isUntypedEmptyCollection(arg) && rt != null && rt.isCollection()) {
                arg = typing.typeEmptyCollection(arg, rt, sp);
            }
            OmgAs.OclExpression effectiveReceiver = receiver;
            OmgAs.OclExpression effectiveArg = arg;
            switch (b) {
                case INTEGER_DIVIDE, INTEGER_MOD -> {
                    typing.requireInteger(rt, sp);
                    typing.requireInteger(arg.type(), sp);
                    if (!dotCall) {
                        throw error("E_INVALID_CALL_SHAPE",
                                "numeric operation '" + name + "' requires '.'", sp);
                    }
                }
                case NUMERIC_MAX, NUMERIC_MIN -> {
                    typing.requireNumeric(rt, sp);
                    typing.requireNumeric(arg.type(), sp);
                    OclType numericJoin = typing.joinType(rt, arg.type(), sp);
                    effectiveReceiver = typing.coerce(receiver, numericJoin, sp);
                    effectiveArg = typing.coerce(arg, numericJoin, sp);
                }
                case COLLECTION_COUNT, COLLECTION_INCLUDES, COLLECTION_EXCLUDES -> {
                    requireCollectionCall(name, rt, dotCall, sp);
                    OclType elementJoin = typing.joinType(rt.elementType(), arg.type(), sp);
                    OclType targetCollection = rt.kind() == OclType.Kind.SET
                            ? OclType.set(elementJoin) : OclType.bag(elementJoin);
                    effectiveReceiver = typing.coerce(receiver, targetCollection, sp);
                    effectiveArg = typing.coerce(arg, elementJoin, sp);
                }
                case COLLECTION_INCLUDES_ALL, COLLECTION_EXCLUDES_ALL -> {
                    requireCollectionCall(name, rt, dotCall, sp);
                    requireSameCollectionType(rt, arg.type(), name, sp);
                }
                case SET_UNION, SET_INTERSECTION -> {
                    requireCollectionCall(name, rt, dotCall, sp);
                    if (rt.kind() != OclType.Kind.SET) {
                        throw error("E_TYPE", name + " requires a Set receiver", sp);
                    }
                    requireSameCollectionType(rt, arg.type(), name, sp);
                }
                default -> throw error("E_RESOLUTION", "unhandled op " + b, sp);
            }
            OclType result = switch (b) {
                case INTEGER_DIVIDE, INTEGER_MOD -> OclType.INTEGER;
                case NUMERIC_MAX, NUMERIC_MIN -> effectiveReceiver.type();
                case COLLECTION_COUNT -> OclType.INTEGER;
                case COLLECTION_INCLUDES, COLLECTION_EXCLUDES,
                     COLLECTION_INCLUDES_ALL, COLLECTION_EXCLUDES_ALL -> OclType.BOOLEAN;
                default -> rt; // Set algebra keeps the receiver set type
            };
            return typing.operation(sp, b, List.of(effectiveReceiver, effectiveArg), result);
        }
        throw error("E_ARITY", "operation '" + name + "' expects 0 or 1 argument(s)", sp);
    }

    private boolean requiresArrow(OclOperation u) {
        return switch (u) {
            case COLLECTION_SIZE, COLLECTION_IS_EMPTY, COLLECTION_NOT_EMPTY, COLLECTION_SUM -> true;
            default -> false;
        };
    }

    private boolean isCollectionOperation(OclOperation operation) {
        return switch (operation) {
            case COLLECTION_COUNT, COLLECTION_INCLUDES, COLLECTION_EXCLUDES,
                    COLLECTION_INCLUDES_ALL, COLLECTION_EXCLUDES_ALL,
                    SET_UNION, SET_INTERSECTION -> true;
            default -> false;
        };
    }

    private OmgAs.OclExpression optionalObjectToSet(OmgAs.OclExpression receiver,
                                                     SourceSpan span) {
        OclType source = receiver.type();
        return new OmgAs.CoerceExp(span, receiver, source, OclType.set(source),
                org.uet.dse.ocl2cypher.core.CoreExpr.CoercionKind.OPTIONAL_OBJECT_TO_SET);
    }

    private OclType elementOf(OclType t, SourceSpan sp) {
        if (t == null || !t.isCollection()) {
            throw error("E_TYPE", "operation requires a collection receiver", sp);
        }
        return t.elementType();
    }

    private void requireCollection(OclType type, SourceSpan sp) {
        if (type == null || !type.isCollection()) {
            throw error("E_TYPE", "collection receiver required, found " + type, sp);
        }
    }

    private void requireCollectionCall(String name, OclType receiverType,
                                       boolean dotCall, SourceSpan sp) {
        requireCollection(receiverType, sp);
        if (dotCall) {
            throw error("E_INVALID_CALL_SHAPE",
                    "collection operation '" + name + "' requires '->'", sp);
        }
    }

    private void requireSameCollectionType(OclType left, OclType right,
                                           String operation, SourceSpan sp) {
        if (right == null || !right.isCollection() || !left.equals(right)) {
            throw error("E_TYPE", operation + " requires two collections of the same type; found "
                    + left + " and " + right, sp);
        }
    }
}
