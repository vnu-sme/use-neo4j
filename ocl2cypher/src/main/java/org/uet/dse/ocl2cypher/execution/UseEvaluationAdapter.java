package org.uet.dse.ocl2cypher.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.tzi.use.parser.shell.ShellCommandCompiler;
import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.uml.mm.MClassInvariant;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.ModelFactory;
import org.tzi.use.uml.ocl.expr.Evaluator;
import org.tzi.use.uml.ocl.value.BooleanValue;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.ocl.value.VarBindings;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.uml.sys.MSystemException;
import org.tzi.use.uml.sys.soil.MStatement;

/** Production bridge to USE's own parser, SOIL runtime and OCL evaluator. */
public final class UseEvaluationAdapter {

    private UseEvaluationAdapter() {
    }

    /** Evaluate invariants already declared in the USE model. */
    public static Result evaluate(Path modelFile, Path soilFile) throws Exception {
        return evaluateSource(Files.readString(modelFile, StandardCharsets.UTF_8),
                modelFile.toString(), soilFile);
    }

    /** Evaluate an external invariant file appended as a USE constraints section. */
    public static Result evaluate(Path modelFile, Path invariantFile, Path soilFile)
            throws Exception {
        String source = Files.readString(modelFile, StandardCharsets.UTF_8)
                + System.lineSeparator() + "constraints" + System.lineSeparator()
                + Files.readString(invariantFile, StandardCharsets.UTF_8);
        return evaluateSource(source, modelFile + "+" + invariantFile, soilFile);
    }

    private static Result evaluateSource(String source, String sourceName,
                                         Path soilFile) throws Exception {
        StringWriter diagnostics = new StringWriter();
        MModel model = USECompiler.compileSpecification(source, sourceName,
                new PrintWriter(diagnostics, true), new ModelFactory());
        if (model == null) {
            throw new IllegalArgumentException("USE specification compilation failed:\n"
                    + diagnostics);
        }
        MSystem system = new MSystem(model);
        replaySoil(system, soilFile);

        Map<String, Set<String>> violations = new LinkedHashMap<>();
        int objectEvaluations = 0;
        for (MClassInvariant invariant : model.classInvariants()) {
            Set<String> ids = new LinkedHashSet<>();
            for (MObject object : system.state().objectsOfClassAndSubClasses(invariant.cls())) {
                objectEvaluations++;
                VarBindings bindings = new VarBindings(system.varBindings());
                bindings.push("self", object.value());
                Value value = new Evaluator().eval(
                        invariant.bodyExpression(), system.state(), bindings);
                if (value.isUndefined()
                        || value instanceof BooleanValue booleanValue && !booleanValue.value()) {
                    ids.add(object.name());
                } else if (!(value instanceof BooleanValue)) {
                    throw new IllegalStateException("USE invariant "
                            + invariant.qualifiedName() + " returned non-Boolean value "
                            + value + " : " + value.type());
                }
            }
            if (violations.putIfAbsent(invariant.qualifiedName(),
                    Collections.unmodifiableSet(ids)) != null) {
                throw new IllegalStateException("duplicate USE invariant key: "
                        + invariant.qualifiedName());
            }
        }
        return new Result(Collections.unmodifiableMap(violations), objectEvaluations);
    }

    private static void replaySoil(MSystem system, Path soilFile) throws Exception {
        int lineNumber = 0;
        for (String rawLine : Files.readAllLines(soilFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("--")) continue;
            if (!line.startsWith("!")) {
                throw new IllegalArgumentException(soilFile + ":" + lineNumber
                        + ": expected a single-line SOIL command beginning with '!'");
            }
            String command = line.substring(line.startsWith("!!") ? 2 : 1).strip();
            StringWriter diagnostics = new StringWriter();
            MStatement statement = ShellCommandCompiler.compileShellCommand(
                    system.model(), system.state(), system.getVariableEnvironment(),
                    command, soilFile + ":" + lineNumber,
                    new PrintWriter(diagnostics, true), false);
            if (statement == null) {
                throw new IllegalArgumentException(soilFile + ":" + lineNumber
                        + ": USE rejected SOIL command `" + command + "`:\n"
                        + diagnostics);
            }
            try {
                system.execute(statement);
            } catch (MSystemException exception) {
                throw new IllegalArgumentException(soilFile + ":" + lineNumber
                        + ": USE failed to execute SOIL command `" + command + "`",
                        exception);
            }
        }
    }

    public record Result(Map<String, Set<String>> violations, int objectEvaluations) {
        /** Resolve one invariant by exact qualified name or unique simple name. */
        public Set<String> violationsFor(String name) {
            Set<String> exact = violations.get(name);
            if (exact != null) return exact;
            var matches = violations.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("::" + name))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException(matches.isEmpty()
                        ? "USE invariant not found: " + name
                        : "ambiguous USE invariant name: " + name);
            }
            return matches.get(0).getValue();
        }
    }
}
