package org.uet.dse.ocl2cypher.cypher;

import static org.uet.dse.ocl2cypher.cypher.CypherArtifacts.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.ocl2cypher.core.CoreDeclaration;
import org.uet.dse.ocl2cypher.cypher.CypherAst.*;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.SourceSpan;
import org.uet.dse.ocl2cypher.diagnostics.Stage;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphValueCodec;
import org.uet.dse.ocl2cypher.graph.Uml2GraphEncodingContract;
import org.uet.dse.ocl2cypher.qcyp.NormQ;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.qcyp.QQuery;
import org.uet.dse.ocl2cypher.runtime.OclType;

/**
 * {@code R : Q_CYP → CypherAS}, factored as {@code R₀(Norm_Q(q))}.
 *
 * <p>Realization is observer-driven and read-only: object typing goes through
 * {@code ObjectInstanceOf}, an attribute through the
 * {@code ObjectHasAttribute → AttributeValue} slot path, and navigation through
 * the fixed {@code LinkAssociateWith}/{@code LinkAggregates}/{@code LinkComposeOf}
 * family selected by the representation catalogue and constrained by the stable
 * {@code associationKey}. A tagged collection carries the whole-collection
 * bottom flag so an empty result stays distinct from a missing/bottom source,
 * and an element-bottom is kept as an element, never folded into the wrapper.
 *
 * <p>{@code WHERE} is a subclause of {@code MATCH}/{@code WITH}; no
 * {@code OPTIONAL MATCH} is ever emitted to fake a bottom, and native
 * {@code null} appears only as the guarded payload of a bottom tag.
 */
public final class Realization {

    private final GraphModel graph;
    final NavigationRealization navigation;
    final CollectionRealization collections;
    final ScalarRealization scalars;
    private final List<QueryParameter> parameters = new ArrayList<>();
    private final FreshAliasSupply aliases = new FreshAliasSupply();
    private final IntegerRangeCertificates numericCertificates;
    private final ExactBinary64Certificates realCertificates;
    private String modelKeyParameter;

    private Realization(GraphModel graph) {
        this.graph = graph;
        this.numericCertificates = new IntegerRangeCertificates(graph);
        this.realCertificates = new ExactBinary64Certificates(graph);
        this.navigation = new NavigationRealization(this, graph);
        this.collections = new CollectionRealization(this);
        this.scalars = new ScalarRealization(this, numericCertificates);
        // VIOLATIONS deliberately keeps the conventional root alias `self`.
        // Reserve it so no generated local can ever capture that live binding.
        this.aliases.reserve("self");
    }

    public static Result<GeneratedArtifact> realize(QQuery q, GraphModel graph,
                                                    CypherAst.Dialect dialect) {
        Result<GeneratedArtifact> construction = constructWithoutValidation(q, graph, dialect);
        if (construction.isFailure()) {
            return construction;
        }
        GeneratedArtifact artifact = construction.value();
        try {
            CypherAstWellFormednessValidator.validate(artifact);
            return Result.success(artifact);
        } catch (IllegalArgumentException invalidArtifact) {
            return Result.failure(Stage.R, "R_TARGET_WF", invalidArtifact.getMessage());
        }
    }

    /**
     * Construction boundary used by the R-4 refinement test. It deliberately
     * omits the postcondition validator, allowing the test to establish that
     * successful construction already satisfies WF rather than relying on the
     * public method to validate its own output.
     */
    static Result<GeneratedArtifact> constructWithoutValidation(QQuery q, GraphModel graph,
                                                                 CypherAst.Dialect dialect) {
        try {
            Realization r = new Realization(graph);
            return Result.success(r.realizeQuery(NormQ.normalize(q), dialect));
        } catch (RealizeError e) {
            return Result.failure(Stage.R, e.code, e.getMessage());
        }
    }

    private static SourceSpan querySpan(QQuery q) {
        return q.expressionBody() != null ? q.expressionBody().span : q.planBody().span;
    }

    private GeneratedArtifact realizeQuery(QQuery q, CypherAst.Dialect dialect) {
        if (q.mode() == QQuery.QueryMode.VIOLATIONS) {
            return realizeViolations(q, dialect);
        }
        return realizeValue(q, dialect);
    }

    /**
     * VALUE root: bind one context object by its stable application id, then
     * return one tagged scalar or collection value.  The context typing path
     * follows the dynamic class through the bounded inheritance closure, so a
     * value query observes subclasses in the same way as OCL.
     */
    private GeneratedArtifact realizeValue(QQuery q, CypherAst.Dialect dialect) {
        List<CypherClause> clauses = new ArrayList<>();
        Ctx ctx = new Ctx();
        if (q.contextClassKey() != null || q.selfVariable() != null) {
            if (q.contextClassKey() == null || q.selfVariable() == null) {
                throw new RealizeError("R_VALUE_CONTEXT",
                        "VALUE context class and self declaration must be present together");
            }
            String selfAlias = fresh("self");
            String directClass = fresh("directClass");
            String contextClass = fresh("contextClass");
            String idParam = "__oclContextId";
            addParam(idParam, "Physical:StableObjectId", QueryParameter.Origin.PUBLIC, null);
            String classParam = "__oclContextClassKey";
            addParam(classParam, "Physical:ClassKey", QueryParameter.Origin.GENERATED,
                    q.contextClassKey());

            Pattern typing = new Pattern(List.of(new PathPattern(
                    List.of(new NodePattern(selfAlias, List.of("Object"),
                                    withModel(new PropertyMapEntry("use_id",
                                            new ParameterExpr(idParam)))),
                            new NodePattern(directClass, List.of("UmlClass"), modelProperty()),
                            new NodePattern(contextClass, List.of("UmlClass"),
                                    List.of(new PropertyMapEntry("modelKey",
                                                    modelKeyExpr()),
                                            new PropertyMapEntry("classKey",
                                                    new ParameterExpr(classParam))))),
                    List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                                    RelDirection.OUTGOING, List.of()),
                            new RelPattern(null, GraphModel.EXTENDS, RelDirection.OUTGOING,
                                    List.of(), 0, navigation.inheritanceBound())))));
            clauses.add(new MatchClause(typing, null));
            OclType selfType = OclType.clazz(q.contextClassKey());
            ctx.bindNode(q.selfVariable(), taggedObject(selfAlias, selfType),
                    selfAlias);
        }
        CypherExpr result;
        if (q.expressionBody() != null) {
            result = realizeExpr(q.expressionBody(), ctx);
        } else {
            MaterializedPlan materialized = collections.materializePlan(q.planBody(), ctx);
            result = bindOnce("valuePlan", materialized.value(), plan ->
                    CypherArtifacts.collectionTag(q.resultType(),
                            new PropertyAccess(plan, CypherArtifacts.OCL_ITEMS),
                            new PropertyAccess(plan, CypherArtifacts.OCL_BOTTOM)));
        }
        ReturnClause ret = new ReturnClause(false,
                List.of(new ProjectionItem(result, "result")));
        clauses.add(ret);
        CypherQuery query = new CypherQuery(clauses, true, querySpan(q));
        ResultShape shape = switch (q.resultShape()) {
            case SCALAR -> ResultShape.SCALAR;
            case SET -> ResultShape.SET;
            case BAG -> ResultShape.BAG;
            case IDS -> throw new RealizeError("R_VALUE_SHAPE", "VALUE cannot return IDS");
        };
        String wholeBottom = q.resultType().isCollection()
                ? CypherArtifacts.OCL_BOTTOM : null;
        ResultContract contract = new ResultContract(shape, "result",
                CypherArtifacts.typeTag(q.resultType()),
                q.resultShape() == QQuery.QResultShape.SET,
                wholeBottom);
        return new GeneratedArtifact(dialect, query, contract, sortedParameters());
    }

    /**
     * VIOLATIONS: scan the context class, evaluate the tagged Boolean₃ body per
     * object, keep the rows whose body is not tagged-true (F or bottom), and
     * RETURN DISTINCT the stable id. Bottom is a violation, so the WHERE keeps
     * both {@code __oclBottom = true} and {@code __oclValue = false}.
     */
    private GeneratedArtifact realizeViolations(QQuery q, CypherAst.Dialect dialect) {
        String selfAlias = "self";
        String classAlias = fresh("c");

        String ctxParam = "__oclContextClassKey";
        addParam(ctxParam, "Physical:ClassKey", QueryParameter.Origin.GENERATED,
                q.contextClassKey());
        String directClassAlias = fresh("directClass");
        Pattern typing = new Pattern(List.of(new PathPattern(
                List.of(new NodePattern(selfAlias, List.of("Object"), modelProperty()),
                        new NodePattern(directClassAlias, List.of("UmlClass"), modelProperty()),
                        new NodePattern(classAlias, List.of("UmlClass"),
                                List.of(new PropertyMapEntry("modelKey", modelKeyExpr()),
                                        new PropertyMapEntry("classKey",
                                        new ParameterExpr(ctxParam))))),
                List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                        RelDirection.OUTGOING, List.of()),
                        new RelPattern(null, GraphModel.EXTENDS, RelDirection.OUTGOING,
                                List.of(), 0, navigation.inheritanceBound())))));
        MatchClause scan = new MatchClause(typing, null);

        Ctx ctx = new Ctx();
        OclType selfType = OclType.clazz(q.contextClassKey());
        ctx.bindNode(q.selfVariable(), taggedObject(selfAlias, selfType),
                selfAlias);
        CypherExpr body = realizeExpr(q.expressionBody(), ctx);
        String bodyAlias = fresh("body");
        WithClause withBody = new WithClause(false,
                List.of(new ProjectionItem(new VariableExpr(selfAlias), selfAlias),
                        new ProjectionItem(body, bodyAlias)),
                violationPredicate(bodyAlias));

        CypherExpr idExpr = new PropertyAccess(new VariableExpr(selfAlias), "use_id");
        ReturnClause ret = new ReturnClause(true,
                List.of(new ProjectionItem(idExpr, "violationId")));

        CypherQuery query = new CypherQuery(List.of(scan, withBody, ret), true, querySpan(q));
        ResultContract contract = new ResultContract(ResultShape.IDS, "violationId",
                "StableObjectId", true, null);
        return new GeneratedArtifact(dialect, query, contract, sortedParameters());
    }

    /** {@code body.__oclBottom = true OR body.__oclValue = false}. */
    private CypherExpr violationPredicate(String bodyAlias) {
        CypherExpr isBottom = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(new VariableExpr(bodyAlias), CypherArtifacts.OCL_BOTTOM),
                new BooleanLiteral(true));
        CypherExpr valueFalse = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(new VariableExpr(bodyAlias), CypherArtifacts.OCL_VALUE),
                new BooleanLiteral(false));
        return new BinaryExpr(BinaryOp.OR, isBottom, valueFalse);
    }

    // ---- R_E : QExpr → CypherExpr ---------------------------------------

    CypherExpr realizeExpr(QNode.QExpr e, Ctx ctx) {
        return CypherAst.withSpan(realizeExprUnspanned(e, ctx), e.span);
    }

    private CypherExpr realizeExprUnspanned(QNode.QExpr e, Ctx ctx) {
        requireNumericDomain(e, ctx);
        return switch (e) {
            case QNode.QExpr.Constant c -> CypherArtifacts.taggedScalar(c.type, scalars.literal(c));
            case QNode.QExpr.Bottom value -> CypherArtifacts.bottomLiteral(value.type);
            case QNode.QExpr.Parameter parameter -> realizeParameter(parameter);
            case QNode.QExpr.Variable variable -> {
                var binding = ctx.lookup(variable.declaration);
                if (binding == null) throw new RealizeError("R_SCOPE",
                        "unbound variable " + variable.declaration.name());
                yield binding.value;
            }
            case QNode.QExpr.ReadAttribute attribute -> navigation.realizeAttribute(attribute, ctx);
            case QNode.QExpr.Binary binary -> scalars.realizeBinary(binary, ctx);
            case QNode.QExpr.Unary unary -> scalars.realizeUnary(unary, ctx);
            case QNode.QExpr.Materialize materialize -> collections.realizeMaterialize(materialize, ctx);
            case QNode.QExpr.Exists3 exists -> collections.realizeExists(exists, ctx);
            case QNode.QExpr.ForAll3 forAll -> collections.realizeForAll(forAll, ctx);
            case QNode.QExpr.IfExpr conditional -> realizeIf(conditional, ctx);
            case QNode.QExpr.CollectionLiteral collection -> realizeCollectionLiteral(collection, ctx);
            case QNode.QExpr.Let let -> realizeLet(let, ctx);
            case QNode.QExpr.Coerce coercion -> realizeCoerce(coercion, ctx);
            case QNode.QExpr.NavigateOne navigationExpr -> navigation.realizeNavigateOne(navigationExpr, ctx);
            case QNode.QExpr.TypeTest test -> navigation.realizeTypeTest(test, ctx);
            case QNode.QExpr.TypeCast cast -> navigation.realizeTypeCast(cast, ctx);
            case QNode.QExpr.IncludesFamily includes -> collections.realizeIncludes(includes, ctx);
            case QNode.QExpr.CountFamily count -> collections.realizeCount(count, ctx);
            case QNode.QExpr.SetAlgebra algebra -> collections.realizeSetAlgebra(algebra, ctx);
            default -> throw new RealizeError("R_UNCOVERED_CONSTRUCTOR",
                    "no R rule for " + e.getClass().getSimpleName());
        };
    }

    private void requireNumericDomain(QNode.QExpr e, Ctx ctx) {
        if (e.type.isCollection() && e.type.elementType().isNumeric()
                && !(e instanceof QNode.QExpr.Bottom)
                && !(e instanceof QNode.QExpr.CollectionLiteral)
                && !(e instanceof QNode.QExpr.Parameter)) {
            throw numericCapability("numeric collection carrier : " + e.type);
        }
        // Only an explicit certificate authorizes native numeric realization.
        // Certificates are snapshot-bound and lexical bindings transport the
        // witness of their bound expression instead of widening to all INT64.
        if (e.type.isNumeric() && !(e instanceof QNode.QExpr.Bottom)) {
            if (e.type.equals(OclType.INTEGER)
                    && numericCertificates.certify(e, ctx::integerCertificate).isEmpty()) {
                throw numericCapability(e.getClass().getSimpleName() + " : " + e.type);
            }
            // Public Real parameters are validated by the execution adapter.
            if (e.type.equals(OclType.REAL)
                    && realCertificates.certify(e, ctx::exactCertificate).isEmpty()
                    && !(e instanceof QNode.QExpr.Parameter)) {
                throw new RealizeError(org.uet.dse.ocl2cypher.diagnostics.RuleId.R_REAL_EXACT_UNSUPPORTED,
                        "Real expression requires an exact realization certificate: "
                                + e.getClass().getSimpleName());
            }
        }
    }

    private CypherExpr realizeCollectionLiteral(QNode.QExpr.CollectionLiteral collection, Ctx ctx) {
        var elements = new ArrayList<CypherExpr>();
        for (var element : collection.elements) elements.add(realizeExpr(element, ctx));
        CypherExpr items = new ListExpr(elements);
        if (collection.collectionKind == org.uet.dse.ocl2cypher.core.CoreExpr.CollectionKind.SET) {
            items = scalars.distinctAtomicItems(items);
        }
        return CypherArtifacts.collectionTag(collection.type, items);
    }

    /** R-E-PARAMETER: intern a public declaration and convert its wire payload. */
    private CypherExpr realizeParameter(QNode.QExpr.Parameter expression) {
        QNode.QParameter parameter = expression.parameter;
        addParam(parameter.name(), CypherArtifacts.typeTag(parameter.expectedType()),
                QueryParameter.Origin.PUBLIC, null);
        return physicalizePublicParameter(new ParameterExpr(parameter.name()),
                parameter.expectedType());
    }

    /**
     * The caller wire map carries exact decimal strings for numeric values.
     * Adapter validation runs first; these conversions therefore cannot turn a
     * malformed parameter into OCL bottom. Collection conversion maps every
     * occurrence and preserves the outer whole-bottom flag and Set/Bag kind.
     */
    private CypherExpr physicalizePublicParameter(CypherExpr wire, OclType type) {
        CypherExpr bottom = new PropertyAccess(wire, CypherArtifacts.OCL_BOTTOM);
        if (type.isCollection()) {
            String item = fresh("parameterItem");
            CypherExpr items = new PropertyAccess(wire, CypherArtifacts.OCL_ITEMS);
            CypherExpr converted = new ListComprehension(item, items, null,
                    physicalizePublicParameter(new VariableExpr(item), type.elementType()));
            return CypherArtifacts.collectionTag(type, converted, bottom);
        }
        CypherExpr payload = new PropertyAccess(wire, CypherArtifacts.OCL_VALUE);
        CypherExpr converted = payload;
        if (type.equals(OclType.INTEGER)) {
            converted = new FunctionCall("toInteger", false, List.of(payload));
        } else if (type.equals(OclType.REAL)) {
            converted = new FunctionCall("toFloat", false, List.of(payload));
        }
        return new CaseExpr(List.of(
                new WhenThen(bottom, CypherArtifacts.bottomLiteral(type))),
                CypherArtifacts.taggedScalar(type, converted));
    }

    /** {@code let x = v in b}: bind the tagged value of v to χ(x), realize b. */
    private CypherExpr realizeLet(QNode.QExpr.Let let, Ctx ctx) {
        int uses = referenceCount(let.body, let.binder);
        if (uses == 0) {
            return realizeExpr(let.body, ctx);
        }
        CypherExpr v = realizeExpr(let.value, ctx);
        Optional<IntegerRangeCertificates.Certificate> integerCertificate =
                numericCertificates.certify(let.value, ctx::integerCertificate);
        Optional<ExactBinary64Certificates.Certificate> exactCertificate =
                realCertificates.certify(let.value, ctx::exactCertificate);
        Ctx inner = ctx.child();
        if (uses == 1) {
            inner.bind(let.binder, v,
                    integerCertificate, exactCertificate);
            return realizeExpr(let.body, inner);
        }
        String alias = fresh("letValue");
        inner.bind(let.binder, new VariableExpr(alias),
                integerCertificate, exactCertificate);
        CypherExpr body = realizeExpr(let.body, inner);
        return bindOnce(alias, v, body);
    }

    /**
     * Coercion over a tagged value: bottom propagates; INTEGER_TO_REAL wraps the
     * payload in toFloat; class upcast keeps the object id but re-tags the type;
     * collection element coercion maps every tagged item and preserves the
     * outer Set/Bag kind and whole-collection bottom flag.
     */
    private CypherExpr realizeCoerce(QNode.QExpr.Coerce co, Ctx ctx) {
        if (co.type.isCollection() && co.type.elementType().isNumeric()) {
            throw numericCapability("numeric collection element coercion");
        }
        CypherExpr s = realizeExpr(co.source, ctx);
        return bindOnce("coerceSource", s, source -> switch (co.kind) {
            case INTEGER_TO_REAL -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(co.type))),
                    CypherArtifacts.taggedScalar(co.type,
                            new FunctionCall("toFloat", false, List.of(payload(source)))));
            case CLASS_UPCAST -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(co.type))),
                    CypherArtifacts.taggedScalar(co.type, payload(source)));
            case COLLECTION_ELEMENT_COERCION -> {
                String alias = fresh("coerceItem");
                CypherExpr item = new VariableExpr(alias);
                OclType targetElement = co.type.elementType();
                CypherExpr converted = new CaseExpr(List.of(
                        new WhenThen(isBottom(item),
                                CypherArtifacts.bottomLiteral(targetElement))),
                        CypherArtifacts.taggedScalar(targetElement,
                                targetElement.equals(OclType.REAL)
                                        ? new FunctionCall("toFloat", false,
                                                List.of(payload(item)))
                                        : payload(item)));
                CypherExpr mapped = new ListComprehension(alias,
                        new PropertyAccess(source, CypherArtifacts.OCL_ITEMS), null, converted);
                yield new CaseExpr(List.of(
                        new WhenThen(isBottom(source),
                                CypherArtifacts.bottomLiteral(co.type))),
                        CypherArtifacts.collectionTag(co.type, mapped));
            }
            case OPTIONAL_OBJECT_TO_SET -> new CaseExpr(List.of(
                    new WhenThen(isBottom(source),
                            CypherArtifacts.collectionTag(co.type, new ListExpr(List.of())))),
                    CypherArtifacts.collectionTag(co.type, new ListExpr(List.of(source))));
        });
    }

    /** To-one navigation: exactly one matching target, else typed bottom. */

    /** Realize every qualifier once, then expose only its bound reference. */

    /** Direct dynamic-class match used by exact tests and reflexive casts. */

    /** oclAsType: bottom or non-conforming → typed bottom; else re-tag identity. */

    /** includes/excludes/includesAll/excludesAll over a tagged collection. */

    /** Canonical includes/excludes body shared by legacy and Binary Q nodes. */

    /** size/count/sum over a tagged collection; whole-bottom propagates. */

    private CypherExpr realizeIf(QNode.QExpr.IfExpr iff, Ctx ctx) {
        CypherExpr c = realizeExpr(iff.condition, ctx);
        CypherExpr t = realizeExpr(iff.thenExpr, ctx);
        CypherExpr f = realizeExpr(iff.elseExpr, ctx);
        // CASE WHEN bottom(c) THEN bottom
        //      WHEN value(c)=true THEN t ELSE f END
        return bindOnce("ifCondition", c, condition -> new CaseExpr(List.of(
                new WhenThen(isBottom(condition), CypherArtifacts.bottomLiteral(iff.type)),
                new WhenThen(new BinaryExpr(BinaryOp.EQUAL, payload(condition),
                        new BooleanLiteral(true)), t)),
                f));
    }

    // ---- R_P : QPlan → occurrence list of tagged element maps ------------

    /** A plan is one carrier so its items and whole-bottom flag share evaluation. */
    record MaterializedPlan(CypherExpr value) {

    }

    /** Predicate tags and source-bottom guard used by exists/forAll folds. */
    record FoldedPredicates(CypherExpr value) {
    }

    // ---- predicate-list quantifiers -------------------------------------

    /** {@code any(p IN preds WHERE p.__oclBottom = false AND p.__oclValue = <value>)}. */

    // ---- binary/unary ----------------------------------------------------

    /** includesAll/excludesAll quantify over support, not Bag multiplicity. */

    /** Set union/intersection with typed equality and strict whole-bottom propagation. */

    /** Extensional Set equality and multiplicity-aware Bag equality. */

    /** Native Boolean equality for two tagged atomic values. */

    // ---- helpers ---------------------------------------------------------

    /** Discharge the stored-decimal premise for the concrete attribute read. */
    void requireStoredIntegerInt64(QNode.QExpr.ReadAttribute attribute) {
        String attributeKey = attribute.ownerClassKey + "::" + attribute.attributeName;
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
                if (value.compareTo(IntegerRangeCertificates.MIN) < 0
                        || value.compareTo(IntegerRangeCertificates.MAX) > 0) {
                    throw numericCapability("stored Integer attribute " + attributeKey
                            + " contains a value outside signed INT64");
                }
            } catch (NumberFormatException malformed) {
                throw numericCapability("stored Integer attribute " + attributeKey
                        + " has a non-canonical payload");
            }
        }
    }

    static RealizeError numericCapability(String operation) {
        return new RealizeError("R_NUMERIC_CAPABILITY",
                "No exact native range/precision certificate for " + operation);
    }

    /** Build a Boolean OR expression without relying on host-language null semantics. */

    /** Count declaration-identity uses, saturated at two for the let policy. */
    private static int referenceCount(QNode.QExpr expression, CoreDeclaration declaration) {
        if (expression instanceof QNode.QExpr.Variable variable) {
            return variable.declaration == declaration ? 1 : 0;
        }
        if (expression instanceof QNode.QExpr.Parameter
                || expression instanceof QNode.QExpr.Bottom
                || expression instanceof QNode.QExpr.Constant) {
            return 0;
        }
        if (expression instanceof QNode.QExpr.Coerce coerce) {
            return referenceCount(coerce.source, declaration);
        }
        if (expression instanceof QNode.QExpr.Let let) {
            return uses(referenceCount(let.value, declaration),
                    referenceCount(let.body, declaration));
        }
        if (expression instanceof QNode.QExpr.IfExpr conditional) {
            return uses(referenceCount(conditional.condition, declaration),
                    referenceCount(conditional.thenExpr, declaration),
                    referenceCount(conditional.elseExpr, declaration));
        }
        if (expression instanceof QNode.QExpr.ReadAttribute attribute) {
            return referenceCount(attribute.source, declaration);
        }
        if (expression instanceof QNode.QExpr.NavigateOne navigation) {
            return uses(referenceCount(navigation.source, declaration),
                    referenceCount(navigation.qualifiers, declaration));
        }
        if (expression instanceof QNode.QExpr.TypeTest test) {
            return referenceCount(test.source, declaration);
        }
        if (expression instanceof QNode.QExpr.TypeCast cast) {
            return referenceCount(cast.source, declaration);
        }
        if (expression instanceof QNode.QExpr.Unary unary) {
            return referenceCount(unary.operand, declaration);
        }
        if (expression instanceof QNode.QExpr.Binary binary) {
            return uses(referenceCount(binary.left, declaration),
                    referenceCount(binary.right, declaration));
        }
        if (expression instanceof QNode.QExpr.Exists3 exists) {
            return uses(referenceCount(exists.source, declaration),
                    referenceCount(exists.predicate, declaration));
        }
        if (expression instanceof QNode.QExpr.ForAll3 forAll) {
            return uses(referenceCount(forAll.source, declaration),
                    referenceCount(forAll.predicate, declaration));
        }
        if (expression instanceof QNode.QExpr.CollectionLiteral collection) {
            return referenceCount(collection.elements, declaration);
        }
        if (expression instanceof QNode.QExpr.IncludesFamily includes) {
            return uses(referenceCount(includes.source, declaration),
                    referenceCount(includes.element, declaration));
        }
        if (expression instanceof QNode.QExpr.CountFamily count) {
            return uses(referenceCount(count.source, declaration),
                    count.element == null ? 0 : referenceCount(count.element, declaration));
        }
        if (expression instanceof QNode.QExpr.SetAlgebra algebra) {
            return uses(referenceCount(algebra.left, declaration),
                    referenceCount(algebra.right, declaration));
        }
        if (expression instanceof QNode.QExpr.Materialize materialize) {
            return referenceCount(materialize.plan, declaration);
        }
        throw new IllegalStateException("uncovered Q expression in reference count: "
                + expression.getClass().getSimpleName());
    }

    static int referenceCount(QNode.QPlan plan, CoreDeclaration declaration) {
        if (plan instanceof QNode.QPlan.FromCollection from) {
            return referenceCount(from.collection, declaration);
        }
        if (plan instanceof QNode.QPlan.ScanClass) {
            return 0;
        }
        if (plan instanceof QNode.QPlan.NavigateMany navigation) {
            return uses(referenceCount(navigation.source, declaration),
                    referenceCount(navigation.qualifiers, declaration));
        }
        if (plan instanceof QNode.QPlan.Filter filter) {
            return uses(referenceCount(filter.source, declaration),
                    referenceCount(filter.predicate, declaration));
        }
        if (plan instanceof QNode.QPlan.Collect collect) {
            return uses(referenceCount(collect.source, declaration),
                    referenceCount(collect.body, declaration));
        }
        if (plan instanceof QNode.QPlan.Distinct distinct) {
            return referenceCount(distinct.source, declaration);
        }
        if (plan instanceof QNode.QPlan.PlanLet let) {
            return uses(referenceCount(let.value, declaration),
                    referenceCount(let.body, declaration));
        }
        throw new IllegalStateException("uncovered Q plan in reference count: "
                + plan.getClass().getSimpleName());
    }

    private static int referenceCount(List<QNode.QExpr> expressions,
                                      CoreDeclaration declaration) {
        int count = 0;
        for (QNode.QExpr expression : expressions) {
            count = uses(count, referenceCount(expression, declaration));
            if (count == 2) return count;
        }
        return count;
    }

    private static int uses(int... counts) {
        int total = 0;
        for (int count : counts) {
            total += count;
            if (total >= 2) return 2;
        }
        return total;
    }

    /**
     * Scope-safe scalar binding encoded only with admitted Cypher syntax:
     * {@code head([x IN [value] | body])}.  The value occurs exactly once in
     * the serialized query; repeated observations in the body read {@code x}.
     */
    CypherExpr bindOnce(String alias, CypherExpr value, CypherExpr body) {
        return new FunctionCall("head", false, List.of(
                new ListComprehension(alias, new ListExpr(List.of(value)), null, body)));
    }

    CypherExpr bindOnce(String base, CypherExpr value,
                                java.util.function.Function<CypherExpr, CypherExpr> body) {
        CypherExpr unwrapped = value instanceof LocatedExpr located
                ? located.expression() : value;
        if (unwrapped instanceof VariableExpr || unwrapped instanceof ParameterExpr
                || unwrapped instanceof NullLiteral || unwrapped instanceof BooleanLiteral
                || unwrapped instanceof IntegerLiteral || unwrapped instanceof FloatLiteral
                || unwrapped instanceof StringLiteral) {
            return body.apply(value);
        }
        String alias = fresh(base);
        return bindOnce(alias, value, body.apply(new VariableExpr(alias)));
    }

    CypherExpr bindPair(String leftBase, CypherExpr left,
                                String rightBase, CypherExpr right,
                                java.util.function.BiFunction<CypherExpr, CypherExpr,
                                        CypherExpr> body) {
        return bindOnce(leftBase, left, leftRef ->
                bindOnce(rightBase, right, rightRef -> body.apply(leftRef, rightRef)));
    }

    /** One generated parameter replaces every repeated model-key literal. */
    CypherExpr modelKeyExpr() {
        if (modelKeyParameter == null) {
            modelKeyParameter = "__oclModelKey";
            addParam(modelKeyParameter, "Physical:ModelKey",
                    QueryParameter.Origin.GENERATED, graph.modelKey());
        }
        return new ParameterExpr(modelKeyParameter);
    }

    List<PropertyMapEntry> modelProperty() {
        return List.of(new PropertyMapEntry(
                Uml2GraphEncodingContract.Property.MODEL_KEY, modelKeyExpr()));
    }

    List<PropertyMapEntry> withModel(PropertyMapEntry extra) {
        List<PropertyMapEntry> properties = new ArrayList<>();
        properties.add(new PropertyMapEntry(
                Uml2GraphEncodingContract.Property.MODEL_KEY, modelKeyExpr()));
        properties.add(extra);
        return properties;
    }

    void addParam(String name, String tag, QueryParameter.Origin origin, String value) {
        for (QueryParameter p : parameters) {
            if (p.name().equals(name)) {
                if (!p.logicalTypeTag().equals(tag) || p.origin() != origin
                        || !java.util.Objects.equals(p.canonicalValue(), value)) {
                    throw new RealizeError("R_PARAMETER_COLLISION",
                            "incompatible parameter declarations share name " + name);
                }
                return;
            }
        }
        parameters.add(new QueryParameter(name, tag, origin, value));
    }

    private List<QueryParameter> sortedParameters() {
        return parameters.stream()
                .sorted(java.util.Comparator.comparing(QueryParameter::name))
                .toList();
    }

    String fresh(String base) {
        return aliases.fresh(base);
    }

    /** Scope of tagged values keyed by declaration identity, never surface name. */
    static final class Binding {
        final CypherExpr value;
        final Optional<IntegerRangeCertificates.Certificate> integerCertificate;
        final Optional<ExactBinary64Certificates.Certificate> exactCertificate;
        final Optional<String> nodeAlias;

        Binding(CypherExpr value,
                Optional<IntegerRangeCertificates.Certificate> integerCertificate,
                Optional<ExactBinary64Certificates.Certificate> exactCertificate) {
            this(value, integerCertificate, exactCertificate, Optional.empty());
        }

        Binding(CypherExpr value,
                Optional<IntegerRangeCertificates.Certificate> integerCertificate,
                Optional<ExactBinary64Certificates.Certificate> exactCertificate,
                Optional<String> nodeAlias) {
            this.value = value;
            this.integerCertificate = integerCertificate;
            this.exactCertificate = exactCertificate;
            this.nodeAlias = nodeAlias;
        }
    }

    static final class Ctx {
        private final java.util.Map<org.uet.dse.ocl2cypher.core.CoreDeclaration, Binding> scope
                = new java.util.IdentityHashMap<>();

        void bind(org.uet.dse.ocl2cypher.core.CoreDeclaration declaration,
                  CypherExpr value) {
            bind(declaration, value, Optional.empty(), Optional.empty());
        }

        void bind(org.uet.dse.ocl2cypher.core.CoreDeclaration declaration,
                  CypherExpr value,
                  Optional<IntegerRangeCertificates.Certificate> integerCertificate,
                  Optional<ExactBinary64Certificates.Certificate> exactCertificate) {
            if (scope.containsKey(declaration)) {
                throw new RealizeError("R_SCOPE",
                        "declaration identity is already live: " + declaration.name());
            }
            scope.put(declaration, new Binding(value,
                    integerCertificate, exactCertificate));
        }

        void bindNode(org.uet.dse.ocl2cypher.core.CoreDeclaration declaration,
                      CypherExpr value, String nodeAlias) {
            if (scope.containsKey(declaration)) {
                throw new RealizeError("R_SCOPE",
                        "declaration identity is already live: " + declaration.name());
            }
            scope.put(declaration, new Binding(value, Optional.empty(),
                    Optional.empty(), Optional.of(nodeAlias)));
        }

        Binding lookup(org.uet.dse.ocl2cypher.core.CoreDeclaration declaration) {
            return scope.get(declaration);
        }

        Optional<IntegerRangeCertificates.Certificate> integerCertificate(
                org.uet.dse.ocl2cypher.core.CoreDeclaration declaration) {
            Binding binding = lookup(declaration);
            return binding == null ? Optional.empty() : binding.integerCertificate;
        }

        Optional<ExactBinary64Certificates.Certificate> exactCertificate(
                org.uet.dse.ocl2cypher.core.CoreDeclaration declaration) {
            Binding binding = lookup(declaration);
            return binding == null ? Optional.empty() : binding.exactCertificate;
        }

        Optional<String> nodeAlias(QNode.QExpr expression) {
            if (!(expression instanceof QNode.QExpr.Variable variable)) {
                return Optional.empty();
            }
            Binding binding = lookup(variable.declaration);
            return binding == null ? Optional.empty() : binding.nodeAlias;
        }

        Ctx child() {
            Ctx c = new Ctx();
            c.scope.putAll(scope);
            return c;
        }
    }

    static final class RealizeError extends RuntimeException {
        final String code;

        RealizeError(String code, String msg) {
            super(msg, null, false, false);
            this.code = code;
        }
    }
}
