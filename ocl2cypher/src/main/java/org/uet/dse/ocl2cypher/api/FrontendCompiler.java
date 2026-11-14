package org.uet.dse.ocl2cypher.api;

import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.Stage;
import org.uet.dse.ocl2cypher.frontend.OmgFrontend;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.omg.OmgAs;

/**
 * Single-call frontend facade so that callers never choose between
 * "parse" and "resolve/type" independently — exactly the {@code E_SM} fusion
 * demanded by Rule 01.
 */
public final class FrontendCompiler {

    private FrontendCompiler() {
    }

    public static Result<java.util.List<OmgAs.OmgDocument>> compile(String source,
                                                                    SchemaModel sm) {
        try {
            return new OmgFrontend(sm).elaborateDocuments(source);
        } catch (RuntimeException e) {
            return Result.failure(Stage.E_SM, "E_PARSE", e.getMessage());
        }
    }

    /** Compile an independent value expression, optionally bound to a context object. */
    public static Result<OmgAs.ExpressionInOcl> compileValueQuery(
            ValueQueryRequest request, SchemaModel sm) {
        try {
            return new OmgFrontend(sm).elaborateValueExpression(request.expression(),
                    request.contextClassKey(), request.contextVariableName()).bind(expression -> {
                        if (request.expectedResultType() != null
                                && !request.expectedResultType().equals(
                                        expression.bodyExpression.type)) {
                            return Result.failure(Stage.E_SM, "E_VALUE_RESULT_TYPE",
                                    "value query declares result type "
                                            + request.expectedResultType()
                                            + " but the expression has type "
                                            + expression.bodyExpression.type);
                        }
                        return Result.success(expression);
                    });
        } catch (RuntimeException e) {
            return Result.failure(Stage.E_SM, "E_PARSE", e.getMessage());
        }
    }

}
