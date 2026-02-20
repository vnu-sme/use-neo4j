package org.uet.dse.neo4j.ocl;

import org.neo4j.driver.Driver;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.value.VarBindings;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class Neo4jOCLEvaluator implements IOCLEvaluator {
    private final org.tzi.use.main.Session useSession;
    private final Driver neo4jDriver;

    public Neo4jOCLEvaluator(org.tzi.use.main.Session session, Driver driver) {
        this.useSession = session;
        this.neo4jDriver = driver;
    }

    @Override
    public EvaluationResult evaluate(String oclExpression) {
        MModel model = useSession.system().model();

        StringWriter errorWriter = new StringWriter();
        Expression expr = OCLCompiler.compileExpression(
            model,
            useSession.system().state(),
            oclExpression,
            "Neo4jPlugin",
            new PrintWriter(errorWriter),
            new VarBindings()
        );

        if (expr == null) {
            return EvaluationResult.failure("Syntax/Semantic Error: " + errorWriter.toString());
        }

        try {
            return safeEvaluate(expr);
        } catch (StackOverflowError ex) {
            return EvaluationResult.failure("OCL is too deep or contains infinite recursion.");
        } catch (Exception ex) {
            return EvaluationResult.failure("Execution Error: " + ex.getMessage());
        }
    }


    private EvaluationResult safeEvaluate(Expression expr) {
        try (org.neo4j.driver.Session dbSession = neo4jDriver.session()) {

            Neo4jStepVisitor visitor = new Neo4jStepVisitor(dbSession);
            expr.processWithVisitor(visitor);

            Object result = visitor.getResult();
            return EvaluationResult.success(
                result != null ? result.toString() : "null",
                expr.type().toString();
            );
        }
    }
}