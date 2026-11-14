package org.uet.dse.ocl2cypher.cypher;

import static org.uet.dse.ocl2cypher.cypher.CypherArtifacts.*;

import java.util.List;
import java.util.Optional;
import org.uet.dse.ocl2cypher.cypher.CypherAst.*;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.cypher.Realization.*;

/** Collections rules using the shared compilation context. */
final class CollectionRealization {
    private final Realization owner;

    CollectionRealization(Realization owner) {
        this.owner = owner;
    }

    CypherExpr realizeIncludes(QNode.QExpr.IncludesFamily inc, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(inc.source, ctx);
        CypherExpr el = owner.realizeExpr(inc.element, ctx);
        boolean exclude = switch (inc.includesKind) {
            case INCLUDES -> false;
            case EXCLUDES -> true;
            case INCLUDES_ALL, EXCLUDES_ALL -> inc.includesKind == QNode.QKind.EXCLUDES_ALL;
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "not an includes-family constructor: " + inc.includesKind);
        };
        return owner.bindPair("membershipSource", src, "membershipElement", el,
                (source, element) -> inc.includesKind == QNode.QKind.INCLUDES
                        || inc.includesKind == QNode.QKind.EXCLUDES
                        ? realizeMembership(source, element, exclude)
                        : owner.scalars.realizeMembershipAll(source, element, exclude));
    }

    CypherExpr realizeMembership(CypherExpr src, CypherExpr el, boolean exclude) {
        CypherExpr items = new PropertyAccess(src, CypherArtifacts.OCL_ITEMS);
        String x = owner.fresh("x");
        CypherExpr memberEq = owner.scalars.atomicTaggedEqual(new VariableExpr(x), el);
        CypherExpr anyMember = new QuantifiedPredicateExpression(
                QuantifierKind.ANY, x, items, memberEq);
        CypherExpr value = exclude ? new UnaryExpr(UnaryOp.NOT, anyMember) : anyMember;
        return new CaseExpr(List.of(
                new WhenThen(isBottom(src), CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                CypherArtifacts.taggedScalar(OclType.BOOLEAN, value));
    }

    CypherExpr realizeCount(QNode.QExpr.CountFamily cf, Ctx ctx) {
        if (cf.countKind == QNode.QKind.SUM) {
            return owner.scalars.realizeUnary(new QNode.QExpr.Unary(cf.span,
                    org.uet.dse.ocl2cypher.core.CoreExpr.UnaryOp.COLLECTION_SUM,
                    cf.source, cf.type), ctx);
        }
        CypherExpr src = owner.realizeExpr(cf.source, ctx);
        if (cf.countKind == QNode.QKind.COUNT) {
            CypherExpr element = owner.realizeExpr(cf.element, ctx);
            return owner.bindPair("countSource", src, "countElement", element,
                    (source, item) -> owner.scalars.collectionCount(
                            new QNode.QExpr.Binary(cf.span,
                                    org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.COLLECTION_COUNT,
                                    cf.source, cf.element, cf.type), source, item));
        }
        return owner.bindOnce("countSource", src, source -> {
        CypherExpr items = new PropertyAccess(source, CypherArtifacts.OCL_ITEMS);
        return switch (cf.countKind) {
            case SIZE -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(OclType.INTEGER))),
                    CypherArtifacts.taggedScalar(OclType.INTEGER,
                            new FunctionCall("size", false, List.of(items))));
            case IS_EMPTY -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                    CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                            new BinaryExpr(BinaryOp.EQUAL,
                                    new FunctionCall("size", false, List.of(items)),
                                    new IntegerLiteral(java.math.BigInteger.ZERO))));
            case NOT_EMPTY -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                    CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                            new BinaryExpr(BinaryOp.GREATER_THAN,
                                    new FunctionCall("size", false, List.of(items)),
                                    new IntegerLiteral(java.math.BigInteger.ZERO))));
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "not a count-family constructor: " + cf.countKind);
        };
        });
    }

    CypherExpr realizeSetAlgebra(QNode.QExpr.SetAlgebra sa, Ctx ctx) {
        CypherExpr left = owner.realizeExpr(sa.left, ctx);
        CypherExpr right = owner.realizeExpr(sa.right, ctx);
        boolean union = sa.operator
                == org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.SET_UNION;
        if (!union && sa.operator
                != org.uet.dse.ocl2cypher.core.CoreExpr.BinaryOp.SET_INTERSECTION) {
            throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "not a set-algebra constructor: " + sa.operator);
        }
        // Reuse the canonical Binary realization after evaluating both operands;
        // this keeps direct QExpr.SetAlgebra construction semantically identical
        // to the normalized path used by the translator.
        return owner.bindPair("setLeft", left, "setRight", right,
                (leftValue, rightValue) -> owner.scalars.realizeSetBinary(
                        new QNode.QExpr.Binary(sa.span, sa.operator,
                                sa.left, sa.right, sa.type),
                        leftValue, rightValue, union));
    }

    /**
     * Plan→Expr bridge. For the Slice-B case the plan is a filtered/collected
     * navigation; the materialized value is the tagged collection of the
     * plan's element tags. When the plan source is bottom the whole-collection
     * bottom is kept, not an empty collection.
     */
    CypherExpr realizeMaterialize(QNode.QExpr.Materialize mat, Ctx ctx) {
        MaterializedPlan mp = materializePlan(mat.plan, ctx);
        // Items are already tagged element maps.
        return owner.bindOnce("materializedPlan", mp.value(), plan -> {
            CypherExpr bottom = new PropertyAccess(plan, CypherArtifacts.OCL_BOTTOM);
            CypherExpr items = new PropertyAccess(plan, CypherArtifacts.OCL_ITEMS);
            return new CaseExpr(List.of(
                    new WhenThen(bottom, CypherArtifacts.bottomLiteral(mat.type))),
                    CypherArtifacts.collectionTag(mat.type, items));
        });
    }

    /**
     * exists₃: {@code CASE WHEN anyBottom AND noTrue THEN ⊥ WHEN anyTrue THEN T
     * ELSE F END}. A predicate-bottom only makes the whole result bottom when no
     * occurrence is already true (Kleene OR), so a single true still wins.
     */
    CypherExpr realizeExists(QNode.QExpr.Exists3 ex, Ctx ctx) {
        FoldedPredicates folded = foldPlanPredicates(ex.source, ex.iterator,
                ex.predicate, ctx);
        return owner.bindOnce("existsFold", folded.value(), fold -> {
            CypherExpr bottom = new PropertyAccess(fold, CypherArtifacts.OCL_BOTTOM);
            CypherExpr predicates = new PropertyAccess(fold, "predicates");
            return owner.bindOnce("existsPredicates", predicates, preds -> {
                CypherExpr anyTrue = anyPredValue(preds, true);
                CypherExpr anyBottom = anyPredBottom(preds);
                return new CaseExpr(List.of(
                        new WhenThen(bottom,
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN)),
                        new WhenThen(anyTrue,
                                CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                        new BooleanLiteral(true))),
                        new WhenThen(anyBottom,
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BooleanLiteral(false)));
            });
        });
    }

    /**
     * forAll₃: {@code CASE WHEN anyFalse THEN F WHEN anyBottom THEN ⊥ ELSE T END}.
     * A single false wins (Kleene AND); a bottom makes it bottom only if no
     * occurrence is already false.
     */
    CypherExpr realizeForAll(QNode.QExpr.ForAll3 fa, Ctx ctx) {
        FoldedPredicates folded = foldPlanPredicates(fa.source, fa.iterator,
                fa.predicate, ctx);
        return owner.bindOnce("forAllFold", folded.value(), fold -> {
            CypherExpr bottom = new PropertyAccess(fold, CypherArtifacts.OCL_BOTTOM);
            CypherExpr predicates = new PropertyAccess(fold, "predicates");
            return owner.bindOnce("forAllPredicates", predicates, preds -> {
                CypherExpr anyFalse = anyPredValue(preds, false);
                CypherExpr anyBottom = anyPredBottom(preds);
                return new CaseExpr(List.of(
                        new WhenThen(bottom,
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN)),
                        new WhenThen(anyFalse,
                                CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                        new BooleanLiteral(false))),
                        new WhenThen(anyBottom,
                                CypherArtifacts.bottomLiteral(OclType.BOOLEAN))),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN,
                                new BooleanLiteral(true)));
            });
        });
    }

    private MaterializedPlan planValue(CypherExpr items, CypherExpr bottomGuard) {
        return new MaterializedPlan(new MapExpr(List.of(
                new MapEntry(CypherArtifacts.OCL_ITEMS, items),
                new MapEntry(CypherArtifacts.OCL_BOTTOM, bottomGuard))));
    }

    private MaterializedPlan bindPlanOnce(String base, MaterializedPlan plan,
            java.util.function.BiFunction<CypherExpr, CypherExpr, MaterializedPlan> body) {
        String alias = owner.fresh(base);
        CypherExpr reference = new VariableExpr(alias);
        CypherExpr items = new PropertyAccess(reference, CypherArtifacts.OCL_ITEMS);
        CypherExpr bottom = new PropertyAccess(reference, CypherArtifacts.OCL_BOTTOM);
        MaterializedPlan transformed = body.apply(items, bottom);
        return new MaterializedPlan(owner.bindOnce(alias, plan.value(), transformed.value()));
    }

    MaterializedPlan materializePlan(QNode.QPlan plan, Ctx ctx) {
        if (plan instanceof QNode.QPlan.FromCollection fc) {
            CypherExpr source = owner.realizeExpr(fc.collection, ctx);
            return new MaterializedPlan(owner.bindOnce("collectionSource", source, value ->
                    planValue(new PropertyAccess(value, CypherArtifacts.OCL_ITEMS),
                            isBottom(value)).value()));
        }
        if (plan instanceof QNode.QPlan.NavigateMany nav) {
            if (nav.associationClass) {
                return materializeAssociationClassPlan(nav, ctx);
            }
            if (nav.viaAssociationClass) {
                return materializeParticipantThroughAssociationClassPlan(nav, ctx);
            }
            CypherExpr src = owner.realizeExpr(nav.source, ctx);
            Optional<String> boundSourceAlias = ctx.nodeAlias(nav.source);
            String roleParam = owner.fresh("__oclRole");
            owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
            CypherExpr value = owner.bindOnce("navigationSource", src, source ->
                    owner.navigation.bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
            String o = owner.fresh("o");
            String t = owner.fresh("t");
            NodePattern sourceNode = boundSourceAlias
                    .map(alias -> new NodePattern(alias, List.of(), List.of()))
                    .orElseGet(() -> new NodePattern(o, List.of("Object"),
                            owner.withModel(new PropertyMapEntry("use_id", payload(source)))));
            Pattern navP = new Pattern(List.of(new PathPattern(
                    List.of(sourceNode,
                            new NodePattern(t, List.of("Object"), owner.modelProperty())),
                    List.of(new RelPattern(owner.fresh("rel"), owner.navigation.binaryLinkType(nav.associationKey),
                             nav.reverse ? RelDirection.INCOMING : RelDirection.OUTGOING,
                             owner.navigation.navigationProperties(roleParam, nav.associationKey, nav.reverse,
                                    qualifierValues))))));
            CypherQuery sub = new CypherQuery(List.of(
                    new MatchClause(navP, null),
                    new ReturnClause(nav.type.kind() == OclType.Kind.SET,
                            List.of(new ProjectionItem(taggedObject(t, nav.elementType),
                                    null)))),
                    true);
            return planValue(new CollectSubquery(sub),
                    or(isBottom(source), owner.navigation.qualifierBottom(qualifierValues))).value();
            }));
            return new MaterializedPlan(value);
        }
        if (plan instanceof QNode.QPlan.Filter flt) {
            MaterializedPlan inner = materializePlan(flt.source, ctx);
            String x = owner.fresh("x");
            Ctx filtered = ctx.child();
            filtered.bind(flt.iterator, new VariableExpr(x));
            // pred is a tagged Boolean₃ map expressed over the element alias x,
            // It is bound inside the comprehension and evaluated once per item.
            CypherExpr pred = owner.realizeExpr(flt.predicate, filtered);
            // USE defaults an undefined filter predicate to false per
            // occurrence: select drops it and reject keeps it. Only a bottom
            // source collection makes the resulting plan whole-bottom.
            return bindPlanOnce("filterInputPlan", inner, (innerItems, innerBottom) -> {
                CypherExpr evaluatedEntry = owner.bindOnce("filterPredicate", pred, predicate ->
                        new MapExpr(List.of(
                                new MapEntry("item", new VariableExpr(x)),
                                new MapEntry("predicate", predicate))));
                CypherExpr evaluated = new ListComprehension(x, innerItems, null,
                        evaluatedEntry);
                return new MaterializedPlan(owner.bindOnce("filterRows", evaluated, rows -> {
                    String rowName = owner.fresh("filterRow");
                    CypherExpr row = new VariableExpr(rowName);
                    CypherExpr predicate = new PropertyAccess(row, "predicate");
                    CypherExpr predicateTrue = new BinaryExpr(BinaryOp.AND,
                            new BinaryExpr(BinaryOp.EQUAL,
                                    new PropertyAccess(predicate,
                                            CypherArtifacts.OCL_BOTTOM),
                                    new BooleanLiteral(false)),
                            new BinaryExpr(BinaryOp.EQUAL,
                                    new PropertyAccess(predicate,
                                            CypherArtifacts.OCL_VALUE),
                                    new BooleanLiteral(true)));
                    CypherExpr keep = flt.isSelect
                            ? predicateTrue
                            : new BinaryExpr(BinaryOp.OR,
                                    new BinaryExpr(BinaryOp.EQUAL,
                                            new PropertyAccess(predicate,
                                                    CypherArtifacts.OCL_BOTTOM),
                                            new BooleanLiteral(true)),
                                    new BinaryExpr(BinaryOp.EQUAL,
                                            new PropertyAccess(predicate,
                                                    CypherArtifacts.OCL_VALUE),
                                            new BooleanLiteral(false)));
                    CypherExpr filteredItems = new ListComprehension(rowName, rows,
                            keep, new PropertyAccess(row, "item"));
                    return planValue(filteredItems, innerBottom).value();
                }));
            });
        }
        if (plan instanceof QNode.QPlan.Collect collect) {
            MaterializedPlan inner = materializePlan(collect.source, ctx);
            String x = owner.fresh("x");
            Ctx collected = ctx.child();
            collected.bind(collect.iterator, new VariableExpr(x));
            // collect always yields a Bag.  A bottom predicate result is kept
            // as an element of that Bag; only a bottom source makes the whole
            // collection bottom.
            CypherExpr body = owner.realizeExpr(collect.body, collected);
            return bindPlanOnce("collectInputPlan", inner, (innerItems, innerBottom) ->
                    planValue(new ListComprehension(x, innerItems, null, body),
                            innerBottom));
        }
        if (plan instanceof QNode.QPlan.ScanClass scan) {
            String ck = owner.fresh("__oclClass");
            owner.addParam(ck, "Physical:ClassKey", QueryParameter.Origin.GENERATED,
                    scan.classKey);
            String o = owner.fresh("o");
            String direct = owner.fresh("directClass");
            String c = owner.fresh("c");
            Pattern scanP = new Pattern(List.of(new PathPattern(
                    List.of(new NodePattern(o, List.of("Object"), owner.modelProperty()),
                            new NodePattern(direct, List.of("UmlClass"), owner.modelProperty()),
                            new NodePattern(c, List.of("UmlClass"),
                                    List.of(new PropertyMapEntry("modelKey", owner.modelKeyExpr()),
                                            new PropertyMapEntry("classKey",
                                            new ParameterExpr(ck))))),
                    List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                            RelDirection.OUTGOING, List.of()),
                            new RelPattern(null, GraphModel.EXTENDS, RelDirection.OUTGOING,
                                    List.of(), 0, owner.navigation.inheritanceBound())))));
            CypherQuery sub = new CypherQuery(List.of(
                    new MatchClause(scanP, null),
                    new ReturnClause(true, List.of(new ProjectionItem(
                            taggedObject(o, OclType.clazz(scan.classKey)), null)))),
                    true);
            return planValue(new CollectSubquery(sub), new BooleanLiteral(false));
        }
        if (plan instanceof QNode.QPlan.Distinct distinct) {
            MaterializedPlan inner = materializePlan(distinct.source, ctx);
            return bindPlanOnce("distinctInputPlan", inner, (innerItems, innerBottom) ->
                    planValue(owner.scalars.distinctAtomicItems(innerItems), innerBottom));
        }
        if (plan instanceof QNode.QPlan.PlanLet let) {
            int uses = owner.referenceCount(let.body, let.binder);
            if (uses == 0) {
                return materializePlan(let.body, ctx);
            }
            CypherExpr value = owner.realizeExpr(let.value, ctx);
            Ctx bodyCtx = ctx.child();
            if (uses == 1) {
                bodyCtx.bind(let.binder, value);
                return materializePlan(let.body, bodyCtx);
            }
            String alias = owner.fresh("planLetValue");
            bodyCtx.bind(let.binder, new VariableExpr(alias));
            MaterializedPlan body = materializePlan(let.body, bodyCtx);
            return new MaterializedPlan(owner.bindOnce(alias, value, body.value()));
        }
        throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                "materialize of plan " + plan.getClass().getSimpleName() + " is future work");
    }

    private MaterializedPlan materializeAssociationClassPlan(QNode.QPlan.NavigateMany nav,
                                                              Ctx ctx) {
        CypherExpr src = owner.realizeExpr(nav.source, ctx);
        String roleParam = owner.fresh("__oclRole");
        owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
        CypherExpr value = owner.bindOnce("associationSource", src, source ->
                owner.navigation.bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
        String participant = owner.fresh("participant");
        String occurrence = owner.fresh("associationClass");
        Pattern pattern = new Pattern(List.of(new PathPattern(
                List.of(new NodePattern(participant, List.of("Object"),
                                owner.withModel(new PropertyMapEntry("use_id", payload(source)))),
                        new NodePattern(occurrence, List.of("AssociationClassObject"),
                                owner.modelProperty())),
                List.of(new RelPattern(owner.fresh("participantRel"),
                        nav.reverse
                                ? GraphModel.ASSOCIATION_CLASS_TARGET_PARTICIPANT
                                : GraphModel.ASSOCIATION_CLASS_SOURCE_PARTICIPANT,
                        RelDirection.INCOMING,
                        owner.navigation.associationClassProperties(roleParam, nav.associationKey,
                                nav.reverse, qualifierValues))))));
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(pattern, null),
                new ReturnClause(nav.type.kind() == OclType.Kind.SET,
                        List.of(new ProjectionItem(
                                taggedObject(occurrence, nav.elementType), null)))),
                true);
        return planValue(new CollectSubquery(sub),
                or(isBottom(source), owner.navigation.qualifierBottom(qualifierValues))).value();
        }));
        return new MaterializedPlan(value);
    }

    private MaterializedPlan materializeParticipantThroughAssociationClassPlan(
            QNode.QPlan.NavigateMany nav, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(nav.source, ctx);
        String roleParam = owner.fresh("__oclRole");
        owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
        CypherExpr value = owner.bindOnce("associationSource", src, sourceValue ->
                owner.navigation.bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
        String source = owner.fresh("participant");
        String occurrence = owner.fresh("associationClass");
        String target = owner.fresh("target");
        Pattern pattern = owner.navigation.associationClassParticipantPath(source, occurrence, target,
                sourceValue, roleParam, nav.associationKey, nav.reverse, qualifierValues);
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(pattern, null),
                new ReturnClause(nav.type.kind() == OclType.Kind.SET,
                        List.of(new ProjectionItem(taggedObject(target, nav.elementType), null)))),
                true);
        return planValue(new CollectSubquery(sub),
                or(isBottom(sourceValue), owner.navigation.qualifierBottom(qualifierValues))).value();
        }));
        return new MaterializedPlan(value);
    }

    private FoldedPredicates foldPlanPredicates(QNode.QPlan plan,
                                                org.uet.dse.ocl2cypher.core.CoreDeclaration binder,
                                                QNode.QExpr predicate, Ctx ctx) {
        MaterializedPlan mp = materializePlan(plan, ctx);
        String x = owner.fresh("x");
        Ctx sub = ctx.child();
        sub.bind(binder, new VariableExpr(x));
        CypherExpr pred = owner.realizeExpr(predicate, sub);
        return new FoldedPredicates(owner.bindOnce("foldInputPlan", mp.value(), planValue ->
                new MapExpr(List.of(
                        new MapEntry("predicates", new ListComprehension(x,
                                new PropertyAccess(planValue, CypherArtifacts.OCL_ITEMS),
                                null, pred)),
                        new MapEntry(CypherArtifacts.OCL_BOTTOM,
                                new PropertyAccess(planValue,
                                        CypherArtifacts.OCL_BOTTOM))))));
    }

    /**
     * {@code any(p IN preds WHERE p.__oclBottom = true)} — the tagged predicate
     * elements are already Boolean₃ maps, so "some predicate is bottom" is a
     * single quantified check over the list, not native null testing.
     */
    private CypherExpr anyPredBottom(CypherExpr preds) {
        String p = owner.fresh("p");
        CypherExpr cond = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(new VariableExpr(p), CypherArtifacts.OCL_BOTTOM),
                new BooleanLiteral(true));
        return new QuantifiedPredicateExpression(QuantifierKind.ANY, p, preds, cond);
    }

    private CypherExpr anyPredValue(CypherExpr preds, boolean value) {
        String p = owner.fresh("p");
        CypherExpr defined = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(new VariableExpr(p), CypherArtifacts.OCL_BOTTOM),
                new BooleanLiteral(false));
        CypherExpr valueEq = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(new VariableExpr(p), CypherArtifacts.OCL_VALUE),
                new BooleanLiteral(value));
        CypherExpr cond = new BinaryExpr(BinaryOp.AND, defined, valueEq);
        return new QuantifiedPredicateExpression(QuantifierKind.ANY, p, preds, cond);
    }
}
