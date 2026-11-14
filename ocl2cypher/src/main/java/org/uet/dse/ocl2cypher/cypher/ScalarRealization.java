package org.uet.dse.ocl2cypher.cypher;

import static org.uet.dse.ocl2cypher.cypher.CypherArtifacts.*;

import java.math.BigInteger;
import java.util.List;
import org.uet.dse.ocl2cypher.cypher.CypherAst.*;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.cypher.Realization.*;

/** Scalars rules using the shared compilation context. */
final class ScalarRealization {
    private final Realization owner;
    private final IntegerRangeCertificates numericCertificates;

    ScalarRealization(Realization owner, IntegerRangeCertificates numericCertificates) {
        this.owner = owner;
        this.numericCertificates = numericCertificates;
    }

    CypherExpr realizeBinary(QNode.QExpr.Binary b, Ctx ctx) {
        CypherExpr l = owner.realizeExpr(b.left, ctx);
        CypherExpr r = owner.realizeExpr(b.right, ctx);
        return owner.bindPair("leftOperand", l, "rightOperand", r, (left, right) -> switch (b.operator) {
            case GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL ->
                    strictCompare(b, left, right);
            case VALUE_EQUAL -> totalEqual(left, right, b.left.type, false);
            case VALUE_NOT_EQUAL -> totalEqual(left, right, b.left.type, true);
            case NUMERIC_ADD, NUMERIC_SUBTRACT, NUMERIC_MULTIPLY -> arithmetic(b, left, right);
            case REAL_DIVIDE, INTEGER_DIVIDE, INTEGER_MOD -> numericDivision(b, left, right, ctx);
            case NUMERIC_MAX, NUMERIC_MIN -> numericExtremum(b, left, right);
            case COLLECTION_COUNT -> collectionCount(b, left, right);
            case COLLECTION_INCLUDES -> owner.collections.realizeMembership(left, right, false);
            case COLLECTION_EXCLUDES -> owner.collections.realizeMembership(left, right, true);
            case COLLECTION_INCLUDES_ALL -> realizeMembershipAll(left, right, false);
            case COLLECTION_EXCLUDES_ALL -> realizeMembershipAll(left, right, true);
            case SET_UNION -> realizeSetBinary(b, left, right, true);
            case SET_INTERSECTION -> realizeSetBinary(b, left, right, false);
            case BOOLEAN_AND, BOOLEAN_OR, BOOLEAN_XOR, BOOLEAN_IMPLIES -> boolean3(b, left, right);
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "binary " + b.operator + " is future work");
        });
    }

    private CypherExpr arithmetic(QNode.QExpr.Binary b, CypherExpr l, CypherExpr r) {
        CypherExpr anyBottom = new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r));
        BinaryOp op = switch (b.operator) {
            case NUMERIC_ADD -> BinaryOp.ADD;
            case NUMERIC_SUBTRACT -> BinaryOp.SUBTRACT;
            default -> BinaryOp.MULTIPLY;
        };
        return new CaseExpr(List.of(
                new WhenThen(anyBottom, CypherArtifacts.bottomLiteral(b.type))),
                CypherArtifacts.taggedScalar(b.type, new BinaryExpr(op, payload(l), payload(r))));
    }

    private CypherExpr numericDivision(QNode.QExpr.Binary b, CypherExpr l, CypherExpr r,
                                       Ctx ctx) {
        if (b.operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.INTEGER_DIVIDE
                || b.operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.INTEGER_MOD) {
            var certificate = numericCertificates.certify(b, ctx::integerCertificate).orElseThrow(
                    () -> owner.numericCapability("uncertified integer div/mod"));
            // Preserve the compact constant form for closed expressions.  A
            // snapshot-domain certificate also permits native dynamic div/mod.
            if (certificate.lower().equals(certificate.upper())) {
                return new CaseExpr(List.of(new WhenThen(or(isBottom(l), isBottom(r)),
                        CypherArtifacts.bottomLiteral(b.type))),
                        CypherArtifacts.taggedScalar(b.type,
                                new IntegerLiteral(certificate.lower())));
            }
        }
        CypherExpr denominatorZero = new BinaryExpr(BinaryOp.EQUAL, payload(r),
                b.operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.REAL_DIVIDE
                        ? new FloatLiteral(java.math.BigDecimal.ZERO)
                        : new IntegerLiteral(java.math.BigInteger.ZERO));
        CypherExpr invalid = new BinaryExpr(BinaryOp.OR,
                new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r)), denominatorZero);
        BinaryOp op = switch (b.operator) {
            case REAL_DIVIDE, INTEGER_DIVIDE -> BinaryOp.DIVIDE;
            case INTEGER_MOD -> BinaryOp.MODULO;
            default -> throw new IllegalStateException("not a division operator");
        };
        CypherExpr quotient = new BinaryExpr(op, payload(l), payload(r));
        if (b.operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.INTEGER_DIVIDE) {
            quotient = new FunctionCall("toInteger", false, List.of(quotient));
        }
        return new CaseExpr(List.of(
                new WhenThen(invalid, CypherArtifacts.bottomLiteral(b.type))),
                CypherArtifacts.taggedScalar(b.type,
                        quotient));
    }

    private CypherExpr numericExtremum(QNode.QExpr.Binary b, CypherExpr l, CypherExpr r) {
        CypherExpr invalid = new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r));
        boolean max = b.operator == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.NUMERIC_MAX;
        CypherExpr chooseLeft = new BinaryExpr(max ? BinaryOp.GREATER_THAN_OR_EQUAL
                : BinaryOp.LESS_THAN_OR_EQUAL, payload(l), payload(r));
        CypherExpr chosen = new CaseExpr(List.of(
                new WhenThen(chooseLeft, payload(l))), payload(r));
        return new CaseExpr(List.of(new WhenThen(invalid,
                        CypherArtifacts.bottomLiteral(b.type))),
                CypherArtifacts.taggedScalar(b.type, chosen));
    }

    CypherExpr collectionCount(QNode.QExpr.Binary b, CypherExpr source,
                                       CypherExpr element) {
        CypherExpr items = new PropertyAccess(source, CypherArtifacts.OCL_ITEMS);
        String x = owner.fresh("countItem");
        CypherExpr keep = atomicTaggedEqual(new VariableExpr(x), element);
        CypherExpr count = new FunctionCall("size", false,
                List.of(new ListComprehension(x, items, keep, null)));
        return new CaseExpr(List.of(new WhenThen(isBottom(source),
                        CypherArtifacts.bottomLiteral(b.type))),
                CypherArtifacts.taggedScalar(b.type, count));
    }

    CypherExpr realizeMembershipAll(CypherExpr left, CypherExpr right,
                                            boolean excludeAll) {
        CypherExpr leftItems = new PropertyAccess(left, CypherArtifacts.OCL_ITEMS);
        CypherExpr rightItems = new PropertyAccess(right, CypherArtifacts.OCL_ITEMS);
        String sought = owner.fresh("membershipItem");
        CypherExpr contained = containsAtomic(leftItems, new VariableExpr(sought));
        CypherExpr predicate = excludeAll ? new UnaryExpr(UnaryOp.NOT, contained) : contained;
        CypherExpr quantified = new QuantifiedPredicateExpression(QuantifierKind.ALL,
                sought, rightItems, predicate);
        CypherExpr anyBottom = new BinaryExpr(BinaryOp.OR, isBottom(left), isBottom(right));
        return new CaseExpr(List.of(
                new WhenThen(anyBottom, CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                CypherArtifacts.taggedScalar(OclType.BOOLEAN, quantified));
    }

    CypherExpr realizeSetBinary(QNode.QExpr.Binary operation, CypherExpr left,
                                        CypherExpr right, boolean union) {
        CypherExpr leftItems = new PropertyAccess(left, CypherArtifacts.OCL_ITEMS);
        CypherExpr rightItems = new PropertyAccess(right, CypherArtifacts.OCL_ITEMS);
        CypherExpr items;
        if (union) {
            items = distinctAtomicItems(new BinaryExpr(BinaryOp.LIST_CONCAT,
                    leftItems, rightItems));
        } else {
            String member = owner.fresh("intersectionItem");
            CypherExpr filtered = new ListComprehension(member, leftItems,
                    containsAtomic(rightItems, new VariableExpr(member)), null);
            items = distinctAtomicItems(filtered);
        }
        CypherExpr wholeBottom = new BinaryExpr(BinaryOp.OR,
                isBottom(left), isBottom(right));
        return CypherArtifacts.collectionTag(operation.type, items, wholeBottom);
    }

    private CypherExpr strictCompare(QNode.QExpr.Binary b, CypherExpr l, CypherExpr r) {
        CypherExpr anyBottom = new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r));
        BinaryOp op = switch (b.operator) {
            case GREATER_THAN -> BinaryOp.GREATER_THAN;
            case GREATER_THAN_OR_EQUAL -> BinaryOp.GREATER_THAN_OR_EQUAL;
            case LESS_THAN -> BinaryOp.LESS_THAN;
            default -> BinaryOp.LESS_THAN_OR_EQUAL;
        };
        CypherExpr cmp = new BinaryExpr(op, payload(l), payload(r));
        return new CaseExpr(List.of(
                new WhenThen(anyBottom, CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                CypherArtifacts.taggedScalar(OclType.BOOLEAN, cmp));
    }

    private CypherExpr totalEqual(CypherExpr l, CypherExpr r, OclType operandType,
                                  boolean negate) {
        CypherExpr bothBottom = new BinaryExpr(BinaryOp.AND, isBottom(l), isBottom(r));
        CypherExpr oneBottom = new BinaryExpr(BinaryOp.XOR, isBottom(l), isBottom(r));
        CypherExpr payloadEq = operandType.isCollection()
                ? collectionPayloadEqual(l, r, operandType)
                : atomicTaggedEqual(l, r);
        CypherExpr defined = negate ? new UnaryExpr(UnaryOp.NOT, payloadEq) : payloadEq;
        CypherExpr trueTag = CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                new BooleanLiteral(!negate));
        CypherExpr falseTag = CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                new BooleanLiteral(negate));
        return new CaseExpr(List.of(
                new WhenThen(bothBottom, trueTag),
                new WhenThen(oneBottom, falseTag)),
                CypherArtifacts.taggedScalar(OclType.BOOLEAN, defined));
    }

    private CypherExpr collectionPayloadEqual(CypherExpr left, CypherExpr right,
                                               OclType collectionType) {
        CypherExpr leftItems = new PropertyAccess(left, CypherArtifacts.OCL_ITEMS);
        CypherExpr rightItems = new PropertyAccess(right, CypherArtifacts.OCL_ITEMS);
        if (collectionType.kind() == OclType.Kind.SET) {
            String x = owner.fresh("leftMember");
            String y = owner.fresh("rightMember");
            CypherExpr leftSubset = new QuantifiedPredicateExpression(QuantifierKind.ALL,
                    x, leftItems, containsAtomic(rightItems, new VariableExpr(x)));
            CypherExpr rightSubset = new QuantifiedPredicateExpression(QuantifierKind.ALL,
                    y, rightItems, containsAtomic(leftItems, new VariableExpr(y)));
            return new BinaryExpr(BinaryOp.AND, leftSubset, rightSubset);
        }

        CypherExpr sameSize = new BinaryExpr(BinaryOp.EQUAL,
                new FunctionCall("size", false, List.of(leftItems)),
                new FunctionCall("size", false, List.of(rightItems)));
        String x = owner.fresh("bagMember");
        CypherExpr sameMultiplicity = new BinaryExpr(BinaryOp.EQUAL,
                countAtomic(leftItems, new VariableExpr(x)),
                countAtomic(rightItems, new VariableExpr(x)));
        CypherExpr allMultiplicitiesEqual = new QuantifiedPredicateExpression(
                QuantifierKind.ALL, x, leftItems, sameMultiplicity);
        return new BinaryExpr(BinaryOp.AND, sameSize, allMultiplicitiesEqual);
    }

    private CypherExpr containsAtomic(CypherExpr items, CypherExpr sought) {
        String candidate = owner.fresh("candidate");
        return new QuantifiedPredicateExpression(QuantifierKind.ANY, candidate, items,
                atomicTaggedEqual(new VariableExpr(candidate), sought));
    }

    private CypherExpr countAtomic(CypherExpr items, CypherExpr sought) {
        String candidate = owner.fresh("candidate");
        CypherExpr matches = new ListComprehension(candidate, items,
                atomicTaggedEqual(new VariableExpr(candidate), sought), null);
        return new FunctionCall("size", false, List.of(matches));
    }

    CypherExpr atomicTaggedEqual(CypherExpr left, CypherExpr right) {
        CypherExpr sameType = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(left, CypherArtifacts.OCL_TYPE),
                new PropertyAccess(right, CypherArtifacts.OCL_TYPE));
        CypherExpr bothBottom = new BinaryExpr(BinaryOp.AND, isBottom(left), isBottom(right));
        CypherExpr bothDefined = new BinaryExpr(BinaryOp.AND,
                new UnaryExpr(UnaryOp.NOT, isBottom(left)),
                new UnaryExpr(UnaryOp.NOT, isBottom(right)));
        CypherExpr samePayload = new BinaryExpr(BinaryOp.EQUAL, payload(left), payload(right));
        CypherExpr sameValueState = new BinaryExpr(BinaryOp.OR, bothBottom,
                new BinaryExpr(BinaryOp.AND, bothDefined, samePayload));
        return new BinaryExpr(BinaryOp.AND, sameType, sameValueState);
    }

    /**
     * Native grouping deduplication for the fixed-shape tagged atomic carrier.
     * Neo4j DISTINCT groups equal maps, including equal null payloads; the
     * explicit type and bottom fields therefore implement the same total typed
     * equality as {@link #atomicTaggedEqual}. Set order is unobservable, while
     * Bag paths do not call this helper and retain every occurrence.
     */
    CypherExpr distinctAtomicItems(CypherExpr items) {
        String x = owner.fresh("distinctItem");
        CypherQuery distinct = new CypherQuery(List.of(
                new UnwindClause(items, x),
                new ReturnClause(true, List.of(new ProjectionItem(
                        new VariableExpr(x), null)))), true);
        return new CollectSubquery(distinct);
    }

    private CypherExpr boolean3(QNode.QExpr.Binary b, CypherExpr l, CypherExpr r) {
        return switch (b.operator) {
            case BOOLEAN_AND -> {
                // Kleene AND: false dominates, then true dominates, then bottom
                CypherExpr lFalse = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(l)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(l),
                                new BooleanLiteral(false)));
                CypherExpr rFalse = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(r)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(r),
                                new BooleanLiteral(false)));
                CypherExpr anyFalse = new BinaryExpr(BinaryOp.OR, lFalse, rFalse);
                CypherExpr anyB = new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r));
                yield new CaseExpr(List.of(
                        new WhenThen(anyFalse, CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BooleanLiteral(false))),
                        new WhenThen(anyB, CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BinaryExpr(BinaryOp.AND, payload(l), payload(r))));
            }
            case BOOLEAN_OR -> {
                CypherExpr lTrue = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(l)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(l), new BooleanLiteral(true)));
                CypherExpr rTrue = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(r)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(r), new BooleanLiteral(true)));
                CypherExpr anyTrue = new BinaryExpr(BinaryOp.OR, lTrue, rTrue);
                CypherExpr bothB = new BinaryExpr(BinaryOp.AND, isBottom(l), isBottom(r));
                yield new CaseExpr(List.of(
                        new WhenThen(anyTrue, CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BooleanLiteral(true))),
                        new WhenThen(bothB, CypherArtifacts.bottomLiteral(OclType.BOOLEAN)),
                        new WhenThen(new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r)),
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BinaryExpr(BinaryOp.OR, payload(l), payload(r))));
            }
            case BOOLEAN_XOR -> {
                // xor bottom => bottom (XOR_B table)
                CypherExpr anyB = new BinaryExpr(BinaryOp.OR, isBottom(l), isBottom(r));
                CypherExpr pxor = new BinaryExpr(BinaryOp.XOR, payload(l), payload(r));
                yield new CaseExpr(List.of(
                        new WhenThen(anyB, CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN, pxor));
            }
            case BOOLEAN_IMPLIES -> {
                // implies = not l or r (Kleene)
                CypherExpr lb = isBottom(l);
                CypherExpr rb = isBottom(r);
                CypherExpr lTrue = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(l)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(l), new BooleanLiteral(true)));
                CypherExpr rFalse = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, isBottom(r)),
                        new BinaryExpr(BinaryOp.EQUAL, payload(r), new BooleanLiteral(false)));
                CypherExpr lFalse = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, lb),
                        new BinaryExpr(BinaryOp.EQUAL, payload(l), new BooleanLiteral(false)));
                CypherExpr lBottomRTrue = new BinaryExpr(BinaryOp.AND, lb,
                        new BinaryExpr(BinaryOp.AND, new UnaryExpr(UnaryOp.NOT, rb),
                                new BinaryExpr(BinaryOp.EQUAL, payload(r), new BooleanLiteral(true))));
                CypherExpr lBottomRFalse = new BinaryExpr(BinaryOp.AND, lb, rFalse);
                CypherExpr rTrue = new BinaryExpr(BinaryOp.AND,
                        new UnaryExpr(UnaryOp.NOT, rb),
                        new BinaryExpr(BinaryOp.EQUAL, payload(r), new BooleanLiteral(true)));
                yield new CaseExpr(List.of(
                        // false implies anything = true; true implies true = true — fall through
                        new WhenThen(lFalse,
                                CypherArtifacts.taggedScalar(OclType.BOOLEAN, new BooleanLiteral(true))),
                        new WhenThen(lBottomRTrue,
                                CypherArtifacts.taggedScalar(OclType.BOOLEAN, new BooleanLiteral(true))),
                        new WhenThen(lBottomRFalse,
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN)),
                        new WhenThen(lTrue,
                                new CaseExpr(List.of(
                                        new WhenThen(rFalse,
                                                CypherArtifacts.taggedScalar(OclType.BOOLEAN, new BooleanLiteral(false))),
                                        new WhenThen(rTrue,
                                                CypherArtifacts.taggedScalar(OclType.BOOLEAN, new BooleanLiteral(true)))),
                                        CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        new WhenThen(rb, CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN, new BooleanLiteral(true)));
            }
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "Boolean3 connective " + b.operator + " is future work");
        };
    }

    CypherExpr realizeUnary(QNode.QExpr.Unary u, Ctx ctx) {
        CypherExpr realizedOperand = owner.realizeExpr(u.operand, ctx);
        return owner.bindOnce("unaryOperand", realizedOperand, op -> switch (u.operator) {
            case OCL_IS_UNDEFINED -> CypherArtifacts.taggedScalar(
                    OclType.BOOLEAN, isBottom(op));
            case COLLECTION_SIZE -> {
                CypherExpr items = new PropertyAccess(op, CypherArtifacts.OCL_ITEMS);
                CypherExpr sz = new FunctionCall("size", false, List.of(items));
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(op), CypherArtifacts.bottomLiteral(OclType.INTEGER))),
                        CypherArtifacts.taggedScalar(OclType.INTEGER, sz));
            }
            case COLLECTION_IS_EMPTY, COLLECTION_NOT_EMPTY -> {
                CypherExpr items = new PropertyAccess(op, CypherArtifacts.OCL_ITEMS);
                CypherExpr szEq0 = new BinaryExpr(BinaryOp.EQUAL,
                        new FunctionCall("size", false, List.of(items)),
                        new IntegerLiteral(java.math.BigInteger.ZERO));
                CypherExpr cond = u.operator == org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.COLLECTION_IS_EMPTY
                        ? szEq0
                        : new BinaryExpr(BinaryOp.GREATER_THAN,
                                new FunctionCall("size", false, List.of(items)),
                                new IntegerLiteral(java.math.BigInteger.ZERO));
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(op), CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN, cond));
            }
            case COLLECTION_SUM -> {
                // sum of Items(op); each item is a tagged numeric map
                CypherExpr items = new PropertyAccess(op, CypherArtifacts.OCL_ITEMS);
                String x = owner.fresh("sumItem");
                String acc = owner.fresh("sumAcc");
                CypherExpr anyBottom = new QuantifiedPredicateExpression(
                        QuantifierKind.ANY, x, items,
                        new BinaryExpr(BinaryOp.EQUAL,
                                new PropertyAccess(new VariableExpr(x),
                                        CypherArtifacts.OCL_BOTTOM),
                                new BooleanLiteral(true)));
                CypherExpr initial = u.type.equals(OclType.REAL)
                        ? new FloatLiteral(java.math.BigDecimal.ZERO)
                        : new IntegerLiteral(java.math.BigInteger.ZERO);
                CypherExpr step = new BinaryExpr(BinaryOp.ADD,
                        new VariableExpr(acc),
                        new PropertyAccess(new VariableExpr(x), CypherArtifacts.OCL_VALUE));
                CypherExpr sum = new ReduceExpr(acc, initial, x, items, step);
                yield new CaseExpr(List.of(
                        new WhenThen(new BinaryExpr(BinaryOp.OR, isBottom(op), anyBottom),
                                CypherArtifacts.bottomLiteral(u.type))),
                        CypherArtifacts.taggedScalar(u.type, sum));
            }
            case BOOLEAN_NOT -> {
                CypherExpr payload = payload(op);
                CypherExpr neg = new UnaryExpr(UnaryOp.NOT, payload);
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(op), CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN, neg));
            }
            case NUMERIC_NEGATE -> new CaseExpr(List.of(
                    new WhenThen(isBottom(op), CypherArtifacts.bottomLiteral(u.type))),
                    CypherArtifacts.taggedScalar(u.type,
                            new UnaryExpr(UnaryOp.NEGATE, payload(op))));
            case NUMERIC_ABS -> new CaseExpr(List.of(
                    new WhenThen(isBottom(op), CypherArtifacts.bottomLiteral(u.type))),
                    CypherArtifacts.taggedScalar(u.type,
                            new FunctionCall("abs", false, List.of(payload(op)))));
            case REAL_FLOOR -> {
                CypherExpr floored = new FunctionCall("toInteger", false, List.of(
                        new FunctionCall("floor", false, List.of(payload(op)))));
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(op),
                                CypherArtifacts.bottomLiteral(OclType.INTEGER))),
                        CypherArtifacts.taggedScalar(OclType.INTEGER, floored));
            }
            case REAL_ROUND -> {
                CypherExpr shifted = new BinaryExpr(BinaryOp.ADD, payload(op),
                        new FloatLiteral(new java.math.BigDecimal("0.5")));
                CypherExpr rounded = new FunctionCall("toInteger", false, List.of(
                        new FunctionCall("floor", false, List.of(shifted))));
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(op),
                                CypherArtifacts.bottomLiteral(OclType.INTEGER))),
                        CypherArtifacts.taggedScalar(OclType.INTEGER, rounded));
            }
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "unary " + u.operator + " is future work");
        });
    }

    CypherExpr decodeScalar(OclType t, CypherExpr stored) {
        // Codec ocl-integer-decimal-v1 stores BigInteger.toString() as decimal text.
        // The attribute-specific certificate checks all concrete payloads before
        // Cypher toInteger() is allowed to parse them into signed INT64.
        if (t.equals(OclType.INTEGER)) {
            return new FunctionCall("toInteger", false, List.of(stored));
        }
        // This branch is reachable only after the snapshot-bound binary64
        // certificate succeeds; finite-decimal storage alone is insufficient.
        if (t.equals(OclType.REAL)) {
            return new FunctionCall("toFloat", false, List.of(stored));
        }
        if (t.equals(OclType.BOOLEAN)) {
            return new BinaryExpr(BinaryOp.EQUAL, stored, new StringLiteral("true"));
        }
        return stored;
    }

    CypherExpr literal(QNode.QExpr.Constant c) {
        Object v = c.literalValue;
        return switch (c.type.kind()) {
            case BOOLEAN -> {
                if (!(v instanceof Boolean value)) {
                    throw uncoveredConstant(c);
                }
                yield new BooleanLiteral(value);
            }
            case INTEGER -> {
                if (!(v instanceof java.math.BigInteger value)) {
                    throw uncoveredConstant(c);
                }
                yield new IntegerLiteral(value);
            }
            case REAL -> {
                if (!(v instanceof java.math.BigDecimal value)) {
                    throw uncoveredConstant(c);
                }
                yield new FloatLiteral(value);
            }
            case STRING -> {
                if (!(v instanceof String value)) {
                    throw uncoveredConstant(c);
                }
                yield new StringLiteral(value);
            }
            case CLASS -> {
                if (!(v instanceof String stableId) || stableId.isEmpty()) {
                    throw uncoveredConstant(c);
                }
                yield new StringLiteral(stableId);
            }
            case SET, BAG -> throw uncoveredConstant(c);
        };
    }

    private RealizeError uncoveredConstant(QNode.QExpr.Constant constant) {
        return new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                "constant carrier does not match " + constant.type);
    }
}
