package org.uet.dse.ocl2cypher.trace;

import org.uet.dse.ocl2cypher.api.*;
import org.uet.dse.ocl2cypher.core.*;
import org.uet.dse.ocl2cypher.cypher.*;
import org.uet.dse.ocl2cypher.cypher.CypherAst.GeneratedArtifact;
import org.uet.dse.ocl2cypher.cypher.CypherAst.ResultShape;
import org.uet.dse.ocl2cypher.cypher.Serializer.Serialized;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.RuleId;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.qcyp.*;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.omg.OmgAs;

/** Optional observer; core compilation never depends on trace collection. */
public final class TracingCompiler {
    private TracingCompiler() { }

    public static Result<java.util.List<OmgAs.OmgDocument>> compile(
            String source, SchemaModel sm, TraceCollector traces) {
        Result<java.util.List<OmgAs.OmgDocument>> result = FrontendCompiler.compile(source, sm);
        if (result.isSuccess()) {
            for (OmgAs.OmgDocument document : result.value()) {
                String documentId = "omg-document:" + document.contextClassKey;
                traces.record(Trace.Stage.E_SM, document.span, documentId, RuleId.E_DOC);
                for (OmgAs.Constraint constraint : document.constraints) {
                    String name = constraint.name == null ? "<anonymous>" : constraint.name;
                    traces.record(Trace.Stage.E_SM, constraint.specification.span,
                            documentId + ":constraint:" + name, RuleId.E_CONTEXT);
                }
            }
        }
        return result;
    }

    public static Result<OmgAs.ExpressionInOcl> compileValueQuery(
            ValueQueryRequest request, SchemaModel sm, TraceCollector traces) {
        Result<OmgAs.ExpressionInOcl> result = FrontendCompiler.compileValueQuery(request, sm);
        if (result.isSuccess()) {
            traces.record(Trace.Stage.E_SM, result.value().span,
                    "omg-value-query", RuleId.E_DOC);
        }
        return result;
    }

    public static Result<CoreInvariant> lower(SchemaModel sm,
                                              OmgAs.OmgDocument document,
                                              OmgAs.Constraint invariant,
                                              TraceCollector traces) {
        Result<CoreInvariant> result = CoreLowering.lower(sm, document, invariant);
        if (result.isSuccess()) {
            String name = invariant.name == null ? "<anonymous>" : invariant.name;
            traces.record(Trace.Stage.N_SM,
                    "omg-document:" + document.contextClassKey + ":constraint:" + name,
                    "core-invariant:" + document.contextClassKey + "::" + name,
                    RuleId.N_INVARIANT);
        }
        return result;
    }

    public static Result<CoreQuery> lowerValueQuery(SchemaModel sm,
                                                    OmgAs.ExpressionInOcl query,
                                                    TraceCollector traces) {
        Result<CoreQuery> result = CoreLowering.lowerValueQuery(sm, query);
        if (result.isSuccess()) {
            traces.record(Trace.Stage.N_SM, "omg-value-query", "core-value-query",
                    RuleId.N_INVARIANT);
        }
        return result;
    }

    public static Result<QQuery> translate(CoreInvariant invariant, TraceCollector traces) {
        Result<QQuery> result = QCypTranslator.translate(invariant);
        if (result.isSuccess()) {
            String name = invariant.invariantName() == null
                    ? "<anonymous>" : invariant.invariantName();
            traces.record(Trace.Stage.T_G,
                    "core-invariant:" + invariant.contextClassKey() + "::" + name,
                    "q-violations:" + invariant.contextClassKey() + "::" + name,
                    RuleId.T_Q_VIOLATIONS);
        }
        return result;
    }

    public static Result<QQuery> translate(CoreQuery query, TraceCollector traces) {
        Result<QQuery> result = QCypTranslator.translate(query);
        if (result.isSuccess()) {
            String rule = result.value().planBody() == null
                    ? RuleId.T_Q_VALUE_EXPR : RuleId.T_Q_VALUE_PLAN;
            traces.record(Trace.Stage.T_G, "core-value-query", "q-value-query", rule);
        }
        return result;
    }

    public static Result<GeneratedArtifact> realize(QQuery q, GraphModel graph,
                                                    CypherAst.Dialect dialect,
                                                    TraceCollector traces) {
        Result<GeneratedArtifact> result = Realization.realize(q, graph, dialect);
        if (result.isSuccess()) {
            String rule = q.mode() == QQuery.QueryMode.VIOLATIONS
                    ? RuleId.R_Q_VIOLATIONS : RuleId.R_Q_VALUE_SCALAR;
            String sourceId = q.mode() == QQuery.QueryMode.VIOLATIONS
                    ? "q-violations:" + q.contextClassKey() + "::<anonymous>"
                    : "q-value-query";
            // Named invariants are not retained by QQuery. Match the exact T_G
            // target whenever possible through the collector's latest target.
            if (q.mode() == QQuery.QueryMode.VIOLATIONS) {
                sourceId = traces.traces().stream()
                        .filter(t -> t.stage() == Trace.Stage.T_G)
                        .reduce((a, b) -> b).map(Trace::targetId).orElse(sourceId);
            }
            traces.record(Trace.Stage.R, sourceId,
                    "cypher-artifact:" + q.mode().name().toLowerCase(), rule);
        }
        return result;
    }

    public static Serialized serialize(GeneratedArtifact artifact, TraceCollector traces) {
        Serialized serialized = Serializer.serialize(artifact);
        traces.record(Trace.Stage.S,
                "cypher-artifact:" + (artifact.contract().shape() == ResultShape.IDS
                        ? "violations" : "value"),
                "serialized-cypher",
                RuleId.S_ARTIFACT);
        return serialized;
    }
}
