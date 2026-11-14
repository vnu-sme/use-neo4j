package org.uet.dse.ocl2cypher.cypher;

import static org.uet.dse.ocl2cypher.cypher.CypherArtifacts.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.ocl2cypher.cypher.CypherAst.*;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.graph.GraphValueCodec;
import org.uet.dse.ocl2cypher.graph.Uml2GraphEncodingContract;
import org.uet.dse.ocl2cypher.qcyp.QNode;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.cypher.Realization.*;

/** Navigation rules using the shared compilation context. */
final class NavigationRealization {
    private final Realization owner;
    private final GraphModel graph;

    NavigationRealization(Realization owner, GraphModel graph) {
        this.owner = owner;
        this.graph = graph;
    }

    CypherExpr realizeNavigateOne(QNode.QExpr.NavigateOne nav, Ctx ctx) {
        if (nav.associationClass) {
            return realizeAssociationClassOne(nav, ctx);
        }
        if (nav.viaAssociationClass) {
            return realizeParticipantThroughAssociationClassOne(nav, ctx);
        }
        CypherExpr src = owner.realizeExpr(nav.source, ctx);
        Optional<String> boundSourceAlias = ctx.nodeAlias(nav.source);
        return owner.bindOnce("navigationSource", src, sourceValue ->
                bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
        String roleParam = owner.fresh("__oclRole");
        owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
        String o = owner.fresh("o");
        String t = owner.fresh("t");
        NodePattern sourceNode = boundSourceAlias
                .map(alias -> new NodePattern(alias, List.of(), List.of()))
                .orElseGet(() -> new NodePattern(o, List.of("Object"),
                        owner.withModel(new PropertyMapEntry("use_id", payload(sourceValue)))));
        Pattern navP = new Pattern(List.of(new PathPattern(
                List.of(sourceNode, new NodePattern(t, List.of("Object"), owner.modelProperty())),
                List.of(new RelPattern(owner.fresh("rel"), binaryLinkType(nav.associationKey),
                        nav.reverse ? RelDirection.INCOMING : RelDirection.OUTGOING,
                        navigationProperties(roleParam, nav.associationKey, nav.reverse,
                                qualifierValues))))));
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(navP, null),
                new ReturnClause(false, List.of(new ProjectionItem(
                        new PropertyAccess(new VariableExpr(t), "use_id"), null)))),
                true);
        CypherExpr xs = new CollectSubquery(sub);
        return owner.bindOnce("navigationMatches", xs, matches -> {
        CypherExpr sizeIsOne = new BinaryExpr(BinaryOp.EQUAL,
                new FunctionCall("size", false, List.of(matches)),
                new IntegerLiteral(java.math.BigInteger.ONE));
        CypherExpr target = CypherArtifacts.taggedScalar(nav.type,
                new FunctionCall("head", false, List.of(matches)));
        return new CaseExpr(List.of(
                new WhenThen(or(isBottom(sourceValue), qualifierBottom(qualifierValues)),
                        CypherArtifacts.bottomLiteral(nav.type)),
                new WhenThen(sizeIsOne, target)),
                CypherArtifacts.bottomLiteral(nav.type));
        });
        }));
    }

    private CypherExpr realizeAssociationClassOne(QNode.QExpr.NavigateOne nav, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(nav.source, ctx);
        String roleParam = owner.fresh("__oclRole");
        owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
        return owner.bindOnce("associationSource", src, source ->
                bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
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
                        associationClassProperties(roleParam, nav.associationKey,
                                nav.reverse, qualifierValues))))));
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(pattern, null),
                new ReturnClause(false, List.of(new ProjectionItem(
                        new PropertyAccess(new VariableExpr(occurrence), "use_id"), null)))),
                true);
        CypherExpr matches = new CollectSubquery(sub);
        return owner.bindOnce("associationMatches", matches, matchValues -> {
            CypherExpr exactlyOne = new BinaryExpr(BinaryOp.EQUAL,
                    new FunctionCall("size", false, List.of(matchValues)),
                    new IntegerLiteral(java.math.BigInteger.ONE));
            CypherExpr target = CypherArtifacts.taggedScalar(nav.type,
                    new FunctionCall("head", false, List.of(matchValues)));
            return new CaseExpr(List.of(
                    new WhenThen(or(isBottom(source),
                            qualifierBottom(qualifierValues)),
                            CypherArtifacts.bottomLiteral(nav.type)),
                    new WhenThen(exactlyOne, target)),
                    CypherArtifacts.bottomLiteral(nav.type));
        });
        }));
    }

    List<PropertyMapEntry> associationClassProperties(String roleParam,
                                                               String associationKey,
                                                               boolean receiverIsTarget,
                                                               List<CypherExpr> qualifiers) {
        List<PropertyMapEntry> properties = new ArrayList<>();
        properties.add(new PropertyMapEntry(
                Uml2GraphEncodingContract.Property.ASSOCIATION_KEY,
                new StringLiteral(associationKey)));
        properties.add(new PropertyMapEntry("modelKey",
                owner.modelKeyExpr()));
        properties.add(new PropertyMapEntry(receiverIsTarget
                        ? Uml2GraphEncodingContract.Property.TARGET_ROLE
                        : Uml2GraphEncodingContract.Property.SOURCE_ROLE,
                new ParameterExpr(roleParam)));
        for (int i = 0; i < qualifiers.size(); i++) {
            CypherExpr qualifier = qualifiers.get(i);
            properties.add(new PropertyMapEntry(qualifierPrefix(receiverIsTarget) + i,
                    new FunctionCall("toString", false, List.of(payload(qualifier)))));
        }
        return properties;
    }

    private CypherExpr realizeParticipantThroughAssociationClassOne(
            QNode.QExpr.NavigateOne nav, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(nav.source, ctx);
        String roleParam = owner.fresh("__oclRole");
        owner.addParam(roleParam, "Physical:Role", QueryParameter.Origin.GENERATED, nav.roleName);
        return owner.bindOnce("associationSource", src, sourceValue ->
                bindQualifiersOnce(nav.qualifiers, ctx, qualifierValues -> {
        String source = owner.fresh("participant");
        String occurrence = owner.fresh("associationClass");
        String target = owner.fresh("target");
        Pattern pattern = associationClassParticipantPath(source, occurrence, target,
                sourceValue, roleParam, nav.associationKey, nav.reverse, qualifierValues);
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(pattern, null),
                new ReturnClause(false, List.of(new ProjectionItem(
                        new PropertyAccess(new VariableExpr(target), "use_id"), null)))), true);
        CypherExpr matches = new CollectSubquery(sub);
        return owner.bindOnce("associationMatches", matches, matchValues -> {
            CypherExpr exactlyOne = new BinaryExpr(BinaryOp.EQUAL,
                    new FunctionCall("size", false, List.of(matchValues)),
                    new IntegerLiteral(java.math.BigInteger.ONE));
            return new CaseExpr(List.of(
                    new WhenThen(or(isBottom(sourceValue),
                            qualifierBottom(qualifierValues)),
                            CypherArtifacts.bottomLiteral(nav.type)),
                    new WhenThen(exactlyOne, CypherArtifacts.taggedScalar(nav.type,
                            new FunctionCall("head", false, List.of(matchValues))))),
                    CypherArtifacts.bottomLiteral(nav.type));
        });
        }));
    }

    Pattern associationClassParticipantPath(String source, String occurrence,
                                                    String target, CypherExpr src,
                                                    String roleParam, String associationKey,
                                                    boolean reverse,
                                                    List<CypherExpr> qualifiers) {
        String sourceType = reverse
                ? GraphModel.ASSOCIATION_CLASS_TARGET_PARTICIPANT
                : GraphModel.ASSOCIATION_CLASS_SOURCE_PARTICIPANT;
        String targetType = reverse
                ? GraphModel.ASSOCIATION_CLASS_SOURCE_PARTICIPANT
                : GraphModel.ASSOCIATION_CLASS_TARGET_PARTICIPANT;
        List<PropertyMapEntry> sourceProperties = associationClassEdgeProperties(
                associationKey, null, null, reverse, qualifiers);
        List<PropertyMapEntry> targetProperties = associationClassEdgeProperties(
                associationKey, reverse
                        ? Uml2GraphEncodingContract.Property.SOURCE_ROLE
                        : Uml2GraphEncodingContract.Property.TARGET_ROLE,
                roleParam, reverse,
                qualifiers);
        return new Pattern(List.of(new PathPattern(
                List.of(new NodePattern(source, List.of("Object"),
                                owner.withModel(new PropertyMapEntry("use_id", payload(src)))),
                        new NodePattern(occurrence, List.of("AssociationClassObject"),
                                owner.modelProperty()),
                        new NodePattern(target, List.of("Object"), owner.modelProperty())),
                List.of(new RelPattern(owner.fresh("sourceParticipant"), sourceType,
                                RelDirection.INCOMING, sourceProperties),
                        new RelPattern(owner.fresh("targetParticipant"), targetType,
                                RelDirection.OUTGOING, targetProperties)))));
    }

    private List<PropertyMapEntry> associationClassEdgeProperties(
            String associationKey, String roleProperty, String roleParam,
            boolean reverse, List<CypherExpr> qualifiers) {
        List<PropertyMapEntry> properties = new ArrayList<>();
        properties.add(new PropertyMapEntry(
                Uml2GraphEncodingContract.Property.ASSOCIATION_KEY,
                new StringLiteral(associationKey)));
        properties.add(new PropertyMapEntry("modelKey", owner.modelKeyExpr()));
        if (roleProperty != null) {
            properties.add(new PropertyMapEntry(roleProperty, new ParameterExpr(roleParam)));
        }
        for (int i = 0; i < qualifiers.size(); i++) {
            CypherExpr qualifier = qualifiers.get(i);
            properties.add(new PropertyMapEntry(qualifierPrefix(reverse) + i,
                    new FunctionCall("toString", false, List.of(payload(qualifier)))));
        }
        return properties;
    }

    List<PropertyMapEntry> navigationProperties(String roleParam,
                                                         String associationKey,
                                                         boolean reverse,
                                                         List<CypherExpr> qualifiers) {
        List<PropertyMapEntry> props = new ArrayList<>();
        props.add(new PropertyMapEntry(Uml2GraphEncodingContract.Property.ASSOCIATION_KEY,
                new StringLiteral(associationKey)));
        props.add(new PropertyMapEntry("modelKey", owner.modelKeyExpr()));
        props.add(new PropertyMapEntry(reverse
                        ? Uml2GraphEncodingContract.Property.SOURCE_ROLE
                        : Uml2GraphEncodingContract.Property.TARGET_ROLE,
                new ParameterExpr(roleParam)));
        for (int i = 0; i < qualifiers.size(); i++) {
            CypherExpr q = qualifiers.get(i);
            props.add(new PropertyMapEntry(qualifierPrefix(reverse) + i,
                    new FunctionCall("toString", false, List.of(payload(q)))));
        }
        return props;
    }

    private static String qualifierPrefix(boolean reverse) {
        return reverse ? "sourceQualifier::" : "targetQualifier::";
    }

    String binaryLinkType(String associationKey) {
        Optional<String> instanceType = graph.relationships().stream()
                .filter(relationship -> associationKey.equals(
                        relationship.properties().get(
                                Uml2GraphEncodingContract.Property.ASSOCIATION_KEY)))
                .map(GraphModel.Relationship::physicalType)
                .filter(type -> GraphModel.LINK_ASSOCIATE_WITH.equals(type)
                        || GraphModel.LINK_AGGREGATES.equals(type)
                        || GraphModel.LINK_COMPOSE_OF.equals(type))
                .findFirst();
        if (instanceType.isPresent()) return instanceType.orElseThrow();
        Optional<String> schemaType = graph.relationships().stream()
                .filter(relationship -> associationKey.equals(
                        relationship.properties().get(
                                Uml2GraphEncodingContract.Property.ASSOCIATION_KEY)))
                .map(GraphModel.Relationship::physicalType)
                .filter(type -> GraphModel.ASSOCIATE_WITH.equals(type)
                        || GraphModel.AGGREGATES.equals(type)
                        || GraphModel.COMPOSE_OF.equals(type))
                .findFirst()
                .map(type -> GraphModel.COMPOSE_OF.equals(type)
                        ? GraphModel.LINK_COMPOSE_OF
                        : GraphModel.AGGREGATES.equals(type)
                                ? GraphModel.LINK_AGGREGATES
                                : GraphModel.LINK_ASSOCIATE_WITH);
        return schemaType.orElseThrow(() -> new RealizeError("R_OBSERVER_CONTRACT",
                "no binary-link representation binding for association key "
                        + associationKey));
    }

    CypherExpr qualifierBottom(List<CypherExpr> qualifiers) {
        CypherExpr result = new BooleanLiteral(false);
        for (CypherExpr q : qualifiers) {
            result = new BinaryExpr(BinaryOp.OR, result,
                    isBottom(q));
        }
        return result;
    }

    CypherExpr bindQualifiersOnce(List<QNode.QExpr> qualifiers, Ctx ctx,
            java.util.function.Function<List<CypherExpr>, CypherExpr> body) {
        return bindQualifierAt(qualifiers, ctx, 0, List.of(), body);
    }

    private CypherExpr bindQualifierAt(List<QNode.QExpr> qualifiers, Ctx ctx, int index,
            List<CypherExpr> realized,
            java.util.function.Function<List<CypherExpr>, CypherExpr> body) {
        if (index == qualifiers.size()) {
            return body.apply(realized);
        }
        CypherExpr value = owner.realizeExpr(qualifiers.get(index), ctx);
        return owner.bindOnce("navigationQualifier", value, reference -> {
            List<CypherExpr> next = new ArrayList<>(realized);
            next.add(reference);
            return bindQualifierAt(qualifiers, ctx, index + 1, List.copyOf(next), body);
        });
    }

    /**
     * oclIsTypeOf/oclIsKindOf: a typed bottom is tested using its carried class
     * type; defined objects use the graph's direct/conformance observers.
     */
    CypherExpr realizeTypeTest(QNode.QExpr.TypeTest tt, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(tt.source, ctx);
        String ckParam = owner.fresh("__oclTestClass");
        owner.addParam(ckParam, "Physical:ClassKey", QueryParameter.Origin.GENERATED, tt.targetClassKey);
        return owner.bindOnce("typeTestSource", src, source -> {
        String o = owner.fresh("o");
        String c = owner.fresh("c");
        // exact: direct ObjectInstanceOf; conformance: chain via Extends* (variable length
        // is outside the profile, so we express conformance as a bounded EXISTS over the
        // schema closure the graph already materialized as direct Extends edges + self).
        boolean exact = tt.testKind == org.uet.dse.ocl2cypher.core.CoreExpr.TypeTestKind.EXACT_TYPE;
        Pattern typing = new Pattern(List.of(new PathPattern(
                 List.of(new NodePattern(o, List.of("Object"),
                                owner.withModel(new PropertyMapEntry("use_id", payload(source)))),
                        new NodePattern(c, List.of("UmlClass"),
                                List.of(new PropertyMapEntry("modelKey", owner.modelKeyExpr()),
                                        new PropertyMapEntry("classKey",
                                        new ParameterExpr(ckParam))))),
                List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                        RelDirection.OUTGOING, List.of())))));
        // matchesExact = EXISTS { direct typing to that class }
        CypherExpr matches = existsMatch(typing, o);
        // conformance: also accept a strict superclass reached by Extends+;
        // direct self-conformance is already covered by the first EXISTS.
        // Variable-length is only permitted here because the schema Extends graph is finite and
        // acyclic (WF); the serializer emits it as a bounded pattern.
        CypherExpr result = matches;
        if (!exact) {
            result = new BinaryExpr(BinaryOp.OR, matches, conformanceExists(source, ckParam));
        }
        CypherExpr bottomMatch = bottomTypeTest(tt, source);
        return new CaseExpr(List.of(
                new WhenThen(isBottom(source),
                        CypherArtifacts.taggedScalar(OclType.BOOLEAN, bottomMatch))),
                CypherArtifacts.taggedScalar(OclType.BOOLEAN, result));
        });
    }

    private CypherExpr bottomTypeTest(QNode.QExpr.TypeTest tt, CypherExpr src) {
        CypherExpr carriedType = new PropertyAccess(src, CypherArtifacts.OCL_TYPE);
        if (tt.testKind == org.uet.dse.ocl2cypher.core.CoreExpr.TypeTestKind.EXACT_TYPE) {
            return new BinaryExpr(BinaryOp.EQUAL, carriedType,
                    new StringLiteral(CypherArtifacts.typeTag(OclType.clazz(tt.targetClassKey))));
        }
        List<CypherExpr> acceptedTypes = new ArrayList<>();
        for (GraphModel.Node target : graph.nodes()) {
            if ("UML_CLASS".equals(target.observationRole())
                    && tt.targetClassKey.equals(target.properties().get("classKey"))) {
                for (String key : graph.subclassClosure(target.stableKey())) {
                    String name = graph.node(key).properties().get("classKey");
                    acceptedTypes.add(new StringLiteral(CypherArtifacts.typeTag(OclType.clazz(name))));
                }
            }
        }
        return new BinaryExpr(BinaryOp.IN, carriedType, new ListExpr(acceptedTypes));
    }

    private CypherExpr conformanceExists(CypherExpr src, String ckParam) {
        String o = owner.fresh("o");
        String d = owner.fresh("d");
        String sup = owner.fresh("sup");
        // (o)-[:ObjectInstanceOf]->(d)-[:Extends+]->(sup {classKey:$target})
        Pattern p = new Pattern(List.of(new PathPattern(
                List.of(new NodePattern(o, List.of("Object"),
                                owner.withModel(new PropertyMapEntry("use_id", payload(src)))),
                        new NodePattern(d, List.of("UmlClass"), owner.modelProperty()),
                        new NodePattern(sup, List.of("UmlClass"),
                                List.of(new PropertyMapEntry("modelKey", owner.modelKeyExpr()),
                                        new PropertyMapEntry("classKey",
                                        new ParameterExpr(ckParam))))),
                List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                                RelDirection.OUTGOING, List.of()),
                        new RelPattern(null, GraphModel.EXTENDS,
                                RelDirection.OUTGOING, List.of(), 1,
                                Math.max(1, inheritanceBound()))))));
        return existsMatch(p, o);
    }

    private CypherExpr directTypeExists(CypherExpr src, String ckParam) {
        String o = owner.fresh("o");
        String c = owner.fresh("c");
        Pattern p = new Pattern(List.of(new PathPattern(
                List.of(new NodePattern(o, List.of("Object"),
                                owner.withModel(new PropertyMapEntry("use_id", payload(src)))),
                        new NodePattern(c, List.of("UmlClass"),
                                List.of(new PropertyMapEntry("modelKey",
                                                owner.modelKeyExpr()),
                                        new PropertyMapEntry("classKey",
                                                new ParameterExpr(ckParam))))),
                List.of(new RelPattern(null, GraphModel.OBJECT_INSTANCE_OF,
                        RelDirection.OUTGOING, List.of())))));
        return existsMatch(p, o);
    }

    private CypherExpr existsMatch(Pattern pattern, String projectedVariable) {
        return new ExistsSubquery(new CypherQuery(
                List.of(new MatchClause(pattern, null),
                        new ReturnClause(false, List.of(new ProjectionItem(
                                new VariableExpr(projectedVariable), null)))),
                true));
    }

    int inheritanceBound() {
        int classes = 0;
        for (GraphModel.Node n : graph.nodes()) {
            if ("UML_CLASS".equals(n.observationRole())) {
                classes++;
            }
        }
        return Math.max(0, classes);
    }

    CypherExpr realizeTypeCast(QNode.QExpr.TypeCast tc, Ctx ctx) {
        CypherExpr src = owner.realizeExpr(tc.source, ctx);
        String ckParam = owner.fresh("__oclCastClass");
        owner.addParam(ckParam, "Physical:ClassKey", QueryParameter.Origin.GENERATED, tc.targetClassKey);
        return owner.bindOnce("typeCastSource", src, source -> {
        CypherExpr conforms = new BinaryExpr(BinaryOp.OR,
                directTypeExists(source, ckParam), conformanceExists(source, ckParam));
        return new CaseExpr(List.of(
                new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(tc.type)),
                new WhenThen(conforms, CypherArtifacts.taggedScalar(tc.type, payload(source)))),
                CypherArtifacts.bottomLiteral(tc.type));
        });
    }

    CypherExpr realizeAttribute(QNode.QExpr.ReadAttribute ar, Ctx ctx) {
        if (ar.type.equals(OclType.INTEGER)) {
            owner.requireStoredIntegerInt64(ar);
        }
        CypherExpr src = owner.realizeExpr(ar.source, ctx);
        Optional<String> boundSourceAlias = ctx.nodeAlias(ar.source);
        String akParam = owner.fresh("__oclAttr");
        owner.addParam(akParam, "Physical:AttributeKey", QueryParameter.Origin.GENERATED,
                ar.ownerClassKey + "::" + ar.attributeName);
        return owner.bindOnce("attributeSource", src, source -> {
        String o = owner.fresh("o");
        String s = owner.fresh("s");
        // Slot path: (o:Object {use_id: src.value})-[:ObjectHasAttribute]->
        //            (s:AttributeValue {attributeKey: $ak}).  Collect the
        // tagged storage carrier, not only s.value: a defined String equal to
        // the old __BOTTOM__ sentinel must remain distinguishable from bottom.
        NodePattern sourceNode = boundSourceAlias
                .map(alias -> new NodePattern(alias, List.of(), List.of()))
                .orElseGet(() -> new NodePattern(o, List.of("Object"),
                        owner.withModel(new PropertyMapEntry("use_id", payload(source)))));
        Pattern slotPattern = new Pattern(List.of(new PathPattern(
                List.of(sourceNode, new NodePattern(s, List.of("AttributeValue"),
                                List.of(new PropertyMapEntry("modelKey", owner.modelKeyExpr()),
                                        new PropertyMapEntry("attributeKey",
                                        new ParameterExpr(akParam))))),
                List.of(new RelPattern(null, GraphModel.OBJECT_HAS_ATTRIBUTE,
                        RelDirection.OUTGOING, List.of())))));
        CypherQuery sub = new CypherQuery(List.of(
                new MatchClause(slotPattern, null),
                new ReturnClause(false, List.of(new ProjectionItem(
                        new MapExpr(List.of(
                                new MapEntry("state", new PropertyAccess(
                                        new VariableExpr(s), GraphValueCodec.VALUE_STATE)),
                                new MapEntry("type", new PropertyAccess(
                                        new VariableExpr(s), GraphValueCodec.VALUE_TYPE)),
                                new MapEntry("codec", new PropertyAccess(
                                        new VariableExpr(s), GraphValueCodec.CODEC_ID)),
                                new MapEntry("payload", new PropertyAccess(
                                        new VariableExpr(s), GraphValueCodec.PAYLOAD)))), null)))),
                true);
        CypherExpr xs = new CollectSubquery(sub);
        return owner.bindOnce("attributeSlots", xs, slotList -> {
        // CASE WHEN IsBottom(src) THEN bottom
        //      WHEN size(xs)=1 THEN Defined(decode(head(xs)))
        //      ELSE bottom END   (missing / multiplicity-corrupt → bottom)
        CypherExpr sizeIsOne = new BinaryExpr(BinaryOp.EQUAL,
                new FunctionCall("size", false, List.of(slotList)),
                new IntegerLiteral(java.math.BigInteger.ONE));
        CypherExpr stored = new FunctionCall("head", false, List.of(slotList));
        return owner.bindOnce("attributeSlot", stored, storedValue -> {
        CypherExpr storedIsDefined = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(storedValue, "state"),
                new StringLiteral(GraphValueCodec.DEFINED));
        CypherExpr storedHasExpectedType = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(storedValue, "type"), new StringLiteral(ar.type.toString()));
        CypherExpr storedHasExpectedCodec = new BinaryExpr(BinaryOp.EQUAL,
                new PropertyAccess(storedValue, "codec"),
                new StringLiteral(GraphValueCodec.codecId(ar.type)));
        CypherExpr exactlyOneDefined = new BinaryExpr(BinaryOp.AND, sizeIsOne,
                new BinaryExpr(BinaryOp.AND, storedIsDefined,
                        new BinaryExpr(BinaryOp.AND, storedHasExpectedType,
                                storedHasExpectedCodec)));
        CypherExpr decoded = owner.scalars.decodeScalar(ar.type,
                new PropertyAccess(storedValue, "payload"));
        return new CaseExpr(List.of(
                new WhenThen(isBottom(source), CypherArtifacts.bottomLiteral(ar.type)),
                new WhenThen(exactlyOneDefined,
                        CypherArtifacts.taggedScalar(ar.type, decoded))),
                CypherArtifacts.bottomLiteral(ar.type));
        });
        });
        });
    }
}
