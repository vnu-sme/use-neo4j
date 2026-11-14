package org.uet.dse.ocl2cypher.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.uet.dse.ocl2cypher.cypher.CypherAst;
import org.uet.dse.ocl2cypher.core.CoreInterpreter;
import org.uet.dse.ocl2cypher.execution.Neo4jExecutionAdapter;
import org.uet.dse.ocl2cypher.execution.UseEvaluationAdapter;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.input.ModelInputCompiler;
import org.uet.dse.ocl2cypher.input.SourceInvariant;
import org.uet.dse.ocl2cypher.input.UseSoilInput;
import org.uet.dse.ocl2cypher.qcyp.QInterpreter;

/**
 * Reproducible finite experiment over the USE/SOIL subjects in {@code example}.
 *
 * <p>This is deliberately named {@code IT}: it is not part of the ordinary unit-test
 * count. Run it through {@code scripts/Run-ExampleCorpusExperiment.ps1}. The local
 * experiment compares the official USE evaluator with Core and Q-over-the-canonical-graph.
 * With explicit opt-in it additionally materializes the same graph and compares the
 * generated Cypher result on a live Neo4j database.
 */
final class ExampleCorpusExperimentIT {
    private static final Path EXAMPLES = Path.of("..", "example")
            .toAbsolutePath().normalize();
    private static final Pattern MODEL_DECLARATION = Pattern.compile(
            "(?m)^(\\s*model\\s+)[A-Za-z_][A-Za-z0-9_]*\\s*$");

    private static final List<Subject> BASE_SUBJECTS = List.of(
            subject("shop", "shop-use-soil/shop.use", "shop-use-soil/shop.soil",
                    null, null, 2),
            subject("advanced", "advanced-adapters/advanced.use",
                    "advanced-adapters/advanced.soil", null, null, 3),
            subject("company", "ocl2cypher-correctness-case-study/company.use",
                    "ocl2cypher-correctness-case-study/company.soil",
                    "ocl2cypher-correctness-case-study/invariants.ocl",
                    "ocl2cypher-correctness-case-study/expected-violations.csv", 26),
            subject("comprehensive", "uml-ocl-comprehensive-case-study/comprehensive.use",
                    "uml-ocl-comprehensive-case-study/comprehensive.soil",
                    "uml-ocl-comprehensive-case-study/invariants.ocl",
                    "uml-ocl-comprehensive-case-study/expected-violations.csv", 30),
            subject("medical", "medical/medical.use", "medical/medical.soil",
                    "medical/invariants.ocl", "medical/expected-violations.csv", 36),
            subject("carrental", "carrental/umlmm/carrentalmodel.use",
                    "carrental/umlmm/carrental-experiment.soil",
                    "carrental/umlmm/invariants-extended.ocl",
                    "carrental/umlmm/expected-violations-extended.csv", 25),
            subject("families", "families-to-persons-correctness-case-study/families/families.use",
                    "families-to-persons-correctness-case-study/families/families.soil",
                    null,
                    "families-to-persons-correctness-case-study/families/expected-violations.csv",
                    10),
            subject("persons", "families-to-persons-correctness-case-study/persons/persons.use",
                    "families-to-persons-correctness-case-study/persons/persons.soil",
                    "families-to-persons-correctness-case-study/persons/invariants.ocl",
                    "families-to-persons-correctness-case-study/persons/expected-violations.csv",
                    10));

    private static final List<Boundary> BOUNDARIES = List.of(
            new Boundary("operation-declaration", "unsupported-operations/operations.use",
                    "unsupported-operations/empty.soil", "operation declarations"),
            new Boundary("prepost", "unsupported-prepost/prepost.use",
                    "unsupported-prepost/empty.soil", "pre/postconditions"),
            new Boundary("invalid-library-link", "case-study-oclcypher/library.use",
                    "case-study-oclcypher/library.soil", "must be of type `Branch'"));

    private static final Map<String, String> EXPECTED_INVARIANT_REJECTIONS = Map.ofEntries(
            Map.entry("company/Company::AgeIntersection", "E_TYPE"),
            Map.entry("company/Person::HighRating", "R-REAL-EXACT-UNSUPPORTED"),
            Map.entry("company/Person::NumericSetMembership", "E_TYPE"),
            Map.entry("comprehensive/Department::AgeIntersection", "E_TYPE"),
            Map.entry("comprehensive/Person::HighRating", "R-REAL-EXACT-UNSUPPORTED"),
            Map.entry("comprehensive/Person::NumericSetMembership", "E_TYPE"));
    private static final Set<String> EXCLUDED_OUT_OF_SCOPE = Set.of(
            "company/Company::UniqueEmployeeNames");

    @TempDir
    Path temporaryDirectory;

    @Test
    void evaluateExampleCorpus() throws Exception {
        List<Subject> subjects = subjects();
        int sourceOnlySnapshots = 0;
        boolean live = Boolean.parseBoolean(env("OCL2CYPHER_EXPERIMENT_LIVE", "false"));
        String runId = System.getProperty("ocl2cypher.experimentRunId",
                "manual-" + UUID.randomUUID().toString().replace("-", ""));
        Path output = Path.of(System.getProperty("ocl2cypher.experimentOutput",
                        "target/experiments/" + runId))
                .toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.createDirectories(output.resolve("queries"));

        List<InvariantRow> invariantRows = new ArrayList<>();
        List<SubjectRow> subjectRows = new ArrayList<>();
        List<BoundaryRow> boundaryRows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        // local, live, query texts, expected rejections, CSV mismatches, exclusions
        int[] counters = new int[6];

        Driver driver = null;
        Session session = null;
        try {
            if (live) {
                driver = GraphDatabase.driver(requiredEnv("NEO4J_URI"),
                        AuthTokens.basic(requiredEnv("NEO4J_USER"),
                                requiredEnv("NEO4J_PASSWORD")));
                driver.verifyConnectivity();
                session = driver.session(SessionConfig.forDatabase(requiredEnv("NEO4J_DB")));
                writeNeo4jEnvironment(output, session);
            }

            for (Subject subject : subjects) {
                evaluateSubject(subject, runId, live, session, output,
                        invariantRows, subjectRows, boundaryRows, failures, counters);
            }
            evaluateBoundaries(boundaryRows, failures);
            if (Boolean.getBoolean("ocl2cypher.allSnapshots")) {
                try {
                    sourceOnlySnapshots = evaluateSourceOnlySnapshots(output);
                } catch (Exception | AssertionError error) {
                    failures.add("source-only: " + error.getMessage());
                    throw error;
                }
            }
        } finally {
            if (session != null) session.close();
            if (driver != null) driver.close();
            writeReports(output, runId, live, invariantRows, subjectRows,
                    boundaryRows, failures, counters, subjects, sourceOnlySnapshots);
        }

        int expectedRejections = profileEntries(subjects, EXPECTED_INVARIANT_REJECTIONS.keySet());
        int expectedExclusions = profileEntries(subjects, EXCLUDED_OUT_OF_SCOPE);
        int expectedAdmitted = subjects.stream().mapToInt(Subject::expectedInvariants).sum()
                - expectedRejections - expectedExclusions;
        assertEquals(expectedAdmitted,
                counters[0], "unexpected number of successful USE/Q comparisons");
        assertEquals(expectedRejections, counters[3],
                "unexpected number of validated invariant rejections");
        assertEquals(2, counters[4],
                "reviewed CSV audit changed; inspect the USE oracle differences");
        assertEquals(expectedExclusions, counters[5],
                "unexpected number of out-of-scope exclusions");
        if (live) {
            assertEquals(counters[0], counters[1],
                    "every local comparison must also execute on Neo4j in live mode");
        } else {
            assertEquals(0, counters[1]);
        }
        assertTrue(failures.isEmpty(), () -> "experiment failures:\n"
                + String.join("\n", failures) + "\nevidence=" + output);
    }

    private void evaluateSubject(Subject subject, String runId, boolean live,
            Session session, Path output, List<InvariantRow> invariantRows,
            List<SubjectRow> subjectRows, List<BoundaryRow> boundaryRows,
            List<String> failures, int[] counters) {
        String namespace = namespace(subject.id(), runId);
        long sourceStarted = System.nanoTime();
        int classCount = 0;
        int attributeCount = 0;
        int associationCount = 0;
        int objectCount = 0;
        int linkCount = 0;
        int graphNodeCount = 0;
        int graphRelationshipCount = 0;
        int sourceEvaluations = 0;
        int admitted = 0;
        int matched = 0;
        String status = "PASS";
        String diagnostic = "";
        try {
            PreparedInput prepared = prepare(subject, namespace);
            UseEvaluationAdapter.Result use = UseEvaluationAdapter.evaluate(
                    prepared.useFile(), prepared.soilFile());
            sourceEvaluations = use.objectEvaluations();

            Map<String, Set<String>> reviewed = subject.expectedCsv() == null
                    ? Map.of() : readExpected(subject.expectedCsv());
            if (!subject.id().equals(subject.profile())
                    && !reviewed.keySet().equals(use.violations().keySet())) {
                throw new IllegalStateException("new snapshot oracle has missing or extra invariants");
            }

            var loaded = UseSoilInput.load(prepared.useFile(), prepared.soilFile());
            if (loaded.isFailure()) {
                throw new IllegalStateException(loaded.primaryDiagnostic().toString());
            }
            var input = loaded.value();
            if (input.invariants().size() != subject.expectedInvariants()) {
                throw new IllegalStateException("expected " + subject.expectedInvariants()
                        + " invariants but loaded " + input.invariants().size());
            }
            classCount = input.schema().classes().size();
            attributeCount = input.schema().attributes().size();
            associationCount = input.schema().associations().size();
            objectCount = input.snapshot().objects().size();
            linkCount = input.snapshot().links().size();

            var built = GraphBuilder.build(input.schema(), input.snapshot());
            if (built.isFailure()) {
                throw new IllegalStateException(built.primaryDiagnostic().toString());
            }
            graphNodeCount = built.value().graph().nodes().size();
            graphRelationshipCount = built.value().graph().relationships().size();

            if (live) {
                Neo4jExecutionAdapter.materializeGraph(session, built.value().graph());
            }
            try {
                for (SourceInvariant invariant : input.invariants()) {
                    String key = invariant.contextClassKey() + "::" + invariant.name();
                    Set<String> useIds = use.violationsFor(key);
                    String reviewedStatus = reviewed.isEmpty() ? "NOT_AVAILABLE"
                            : reviewed.getOrDefault(key, Set.of()).equals(useIds)
                                    ? "PASS" : "MISMATCH";
                    String experimentKey = subject.profile() + "/" + key;
                    if (EXCLUDED_OUT_OF_SCOPE.contains(experimentKey)) {
                        counters[5]++;
                        invariantRows.add(InvariantRow.excluded(subject.id(), key,
                                ids(useIds), reviewedStatus,
                                "Outside Supported: isUnique is an implementation extension"));
                        continue;
                    }
                    if (reviewedStatus.equals("MISMATCH")) {
                        counters[4]++;
                        if (!subject.id().equals(subject.profile())) {
                            failures.add(subject.id() + "/" + key + ": new snapshot oracle mismatch");
                        }
                    }
                    String expectedRejection = EXPECTED_INVARIANT_REJECTIONS.get(experimentKey);
                    long compileStarted = System.nanoTime();
                    var compiled = ModelInputCompiler.compile(input, key,
                            CypherAst.Dialect.CYPHER_5, built.value());
                    long compileNanos = System.nanoTime() - compileStarted;
                    if (compiled.isFailure()) {
                        String message = compiled.primaryDiagnostic().toString();
                        String actualCode = compiled.primaryDiagnostic().code();
                        if (actualCode.equals(expectedRejection)) {
                            counters[3]++;
                            boundaryRows.add(new BoundaryRow(experimentKey, "PASS",
                                    actualCode, compiled.primaryDiagnostic().message()));
                            invariantRows.add(InvariantRow.rejection(subject.id(), key,
                                    ids(useIds), reviewedStatus, message, compileNanos));
                        } else {
                            failures.add(experimentKey + ": " + message);
                            invariantRows.add(InvariantRow.failure(subject.id(), key,
                                    ids(useIds), reviewedStatus, message, compileNanos));
                        }
                        continue;
                    }
                    if (expectedRejection != null) {
                        failures.add(experimentKey + ": expected rejection "
                                + expectedRejection + " but compilation succeeded");
                    }
                    admitted++;

                    Set<String> coreIds = Set.copyOf(CoreInterpreter.violations(
                            input.schema(), input.snapshot(), compiled.value().core()));
                    long qStarted = System.nanoTime();
                    Set<String> qIds = Set.copyOf(QInterpreter.violations(
                            input.schema(), compiled.value().graph().graph(),
                            compiled.value().core(), compiled.value().query()));
                    long qNanos = System.nanoTime() - qStarted;
                    boolean localAgreement = useIds.equals(coreIds) && useIds.equals(qIds);
                    if (!localAgreement) {
                        failures.add(subject.id() + "/" + key
                                + ": USE=" + useIds + ", Core=" + coreIds + ", Q=" + qIds);
                    } else {
                        counters[0]++;
                    }

                    String cypher = compiled.value().serializedCypher().cypherText();
                    Path queryDirectory = output.resolve("queries").resolve(subject.id());
                    Files.createDirectories(queryDirectory);
                    Files.writeString(queryDirectory.resolve(fileName(key) + ".cypher"),
                            cypher, StandardCharsets.UTF_8);
                    Files.write(queryDirectory.resolve(fileName(key) + ".ids.tsv"),
                            List.of("engine\tids", "USE\t" + ids(useIds),
                                    "Core\t" + ids(coreIds), "Q\t" + ids(qIds)), StandardCharsets.UTF_8);
                    counters[2]++;

                    String liveStatus = "NOT_RUN";
                    Set<String> neo4jIds = Set.of();
                    long neo4jNanos = 0;
                    if (live) {
                        long neo4jStarted = System.nanoTime();
                        var executed = Neo4jExecutionAdapter.execute(session,
                                compiled.value().cypherAst(), Map.of(),
                                compiled.value().graph().graph());
                        neo4jNanos = System.nanoTime() - neo4jStarted;
                        if (executed.isFailure()) {
                            liveStatus = "ERROR";
                            failures.add(subject.id() + "/" + key + ": Neo4j "
                                    + executed.primaryDiagnostic());
                        } else {
                            neo4jIds = Set.copyOf(executed.value().violationIds());
                            if (useIds.equals(neo4jIds)) {
                                liveStatus = "PASS";
                                counters[1]++;
                            } else {
                                liveStatus = "MISMATCH";
                                failures.add(subject.id() + "/" + key
                                        + ": USE=" + useIds + ", Neo4j=" + neo4jIds);
                            }
                        }
                    }
                    if (localAgreement && (!live || liveStatus.equals("PASS"))) matched++;
                    invariantRows.add(new InvariantRow(subject.id(), key, "PASS",
                            ids(useIds), ids(qIds), ids(neo4jIds), reviewedStatus,
                            Boolean.toString(localAgreement), liveStatus,
                            cypher.length(), compileNanos, qNanos, neo4jNanos, ""));
                }
            } finally {
                if (live) {
                    session.run("MATCH (n {modelKey:$modelKey}) DETACH DELETE n",
                            Map.of("modelKey", namespace)).consume();
                }
            }
        } catch (Exception error) {
            status = "FAIL";
            diagnostic = safeMessage(error);
            failures.add(subject.id() + ": " + diagnostic);
        }
        subjectRows.add(new SubjectRow(subject.id(), namespace, status,
                subject.expectedInvariants(), admitted, matched, sourceEvaluations,
                classCount, attributeCount, associationCount, objectCount, linkCount,
                graphNodeCount, graphRelationshipCount,
                System.nanoTime() - sourceStarted, diagnostic));
    }

    private PreparedInput prepare(Subject subject, String namespace) throws Exception {
        String source = Files.readString(subject.useFile(), StandardCharsets.UTF_8);
        Matcher matcher = MODEL_DECLARATION.matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("model declaration not found in "
                    + subject.useFile());
        }
        source = matcher.replaceFirst(Matcher.quoteReplacement(
                matcher.group(1) + namespace));
        if (subject.oclFile() != null) {
            source += System.lineSeparator() + "constraints" + System.lineSeparator()
                    + Files.readString(subject.oclFile(), StandardCharsets.UTF_8);
        }
        Path directory = temporaryDirectory.resolve(subject.id());
        Files.createDirectories(directory);
        Path use = directory.resolve(subject.id() + ".use");
        Path soil = directory.resolve(subject.id() + ".soil");
        Files.writeString(use, source, StandardCharsets.UTF_8);
        Files.copy(subject.soilFile(), soil);
        return new PreparedInput(use, soil);
    }

    private static void evaluateBoundaries(List<BoundaryRow> rows,
            List<String> failures) {
        for (Boundary boundary : BOUNDARIES) {
            var result = UseSoilInput.load(EXAMPLES.resolve(boundary.useRelative()),
                    EXAMPLES.resolve(boundary.soilRelative()));
            String code = result.isFailure() ? result.primaryDiagnostic().code() : "ACCEPTED";
            String message = result.isFailure() ? result.primaryDiagnostic().message() : "";
            boolean pass = result.isFailure()
                    && code.equals("E_USE_SOIL_INPUT")
                    && message.contains(boundary.messageFragment());
            rows.add(new BoundaryRow(boundary.id(), pass ? "PASS" : "FAIL",
                    code, message));
            if (!pass) {
                failures.add("boundary/" + boundary.id() + ": expected E_USE_SOIL_INPUT containing '"
                        + boundary.messageFragment() + "', got " + code + ": " + message);
            }
        }
    }

    private static Map<String, Set<String>> readExpected(Path csv) throws Exception {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("empty expected CSV: " + csv);
        List<String> header = parseCsv(lines.get(0));
        int invariantColumn = header.indexOf("invariant");
        int idsColumn = header.indexOf("ids");
        if (idsColumn < 0) idsColumn = header.indexOf("violation_ids");
        if (invariantColumn < 0 || idsColumn < 0) {
            throw new IllegalArgumentException("unsupported expected CSV header: " + header);
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            List<String> fields = parseCsv(line);
            String key = fields.get(invariantColumn).trim();
            Set<String> values = splitIds(fields.get(idsColumn));
            if (result.putIfAbsent(key, values) != null) {
                throw new IllegalArgumentException("duplicate expected invariant: " + key);
            }
        }
        return result;
    }

    private static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) throw new IllegalArgumentException("unclosed CSV quote: " + line);
        fields.add(field.toString());
        return fields;
    }

    private static Set<String> splitIds(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Pattern.compile("[;,|]").splitAsStream(text)
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void writeReports(Path output, String runId, boolean live,
            List<InvariantRow> invariantRows, List<SubjectRow> subjectRows,
            List<BoundaryRow> boundaryRows, List<String> failures, int[] counters,
            List<Subject> subjects, int sourceOnlySnapshots)
            throws Exception {
        writeCsv(output.resolve("subjects.csv"), SubjectRow.header(), subjectRows);
        writeCsv(output.resolve("invariants.csv"), InvariantRow.header(), invariantRows);
        writeCsv(output.resolve("boundaries.csv"), BoundaryRow.header(), boundaryRows);
        String json = "{\n"
                + "  \"runId\": \"" + json(runId) + "\",\n"
                + "  \"completedUtc\": \"" + Instant.now() + "\",\n"
                + "  \"liveNeo4j\": " + live + ",\n"
                + "  \"subjects\": " + subjectRows.size() + ",\n"
                + "  \"expectedInvariantEvaluations\": "
                + subjects.stream().mapToInt(Subject::expectedInvariants).sum() + ",\n"
                + "  \"sourceOnlySnapshots\": " + sourceOnlySnapshots + ",\n"
                + "  \"localUseQComparisonsPassed\": " + counters[0] + ",\n"
                + "  \"localUseCoreQComparisonsPassed\": " + counters[0] + ",\n"
                + "  \"liveUseNeo4jComparisonsPassed\": " + counters[1] + ",\n"
                + "  \"generatedQueries\": " + counters[2] + ",\n"
                + "  \"expectedInvariantRejectionsPassed\": " + counters[3] + ",\n"
                + "  \"reviewedCsvMismatches\": " + counters[4] + ",\n"
                + "  \"excludedOutOfScope\": " + counters[5] + ",\n"
                + "  \"boundaryRejectionsPassed\": "
                + boundaryRows.stream().filter(row -> row.status().equals("PASS")).count() + ",\n"
                + "  \"failureCount\": " + failures.size() + ",\n"
                + "  \"status\": \"" + (failures.isEmpty() ? "PASS" : "FAIL") + "\",\n"
                + "  \"interpretation\": \"Finite correctness evidence only; single-run timings are audit metadata, not a latency benchmark.\"\n"
                + "}\n";
        Files.writeString(output.resolve("summary.json"), json, StandardCharsets.UTF_8);
        Files.write(output.resolve("failures.txt"), failures, StandardCharsets.UTF_8);
    }

    private static void writeNeo4jEnvironment(Path output, Session session) throws Exception {
        List<String> components = session.run("CALL dbms.components() YIELD name, versions, edition "
                        + "RETURN name, versions, edition")
                .list(record -> record.get("name").asString() + "|"
                        + record.get("versions").asList() + "|"
                        + record.get("edition").asString());
        Files.write(output.resolve("neo4j-components.txt"), components,
                StandardCharsets.UTF_8);
    }

    private static <T extends CsvRow> void writeCsv(Path path, String header,
            List<T> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        rows.stream().map(CsvRow::csv).forEach(lines::add);
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static List<String[]> snapshotRows() throws Exception {
        List<String> lines = Files.readAllLines(EXAMPLES.resolve("snapshot-manifest.tsv"),
                StandardCharsets.UTF_8);
        if (!lines.get(0).equals("id\tprofile\tscope\tmodel\tsoil\tocl\texpected\tinvariants\trejections\texclusions")) {
            throw new IllegalArgumentException("unexpected snapshot manifest header");
        }
        List<String[]> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split("\t", -1);
            if (row.length != 10 || !row[0].matches("[A-Za-z][A-Za-z0-9_-]+") || !ids.add(row[0])
                    || !Set.of("PIPELINE", "USE_ONLY").contains(row[2])) {
                throw new IllegalArgumentException("invalid snapshot manifest row: " + line);
            }
            for (int index : new int[]{3, 4, 5, 6}) {
                if (row[index].isEmpty() && index == 5) continue;
                Path file = EXAMPLES.resolve(row[index]).normalize();
                if (!file.startsWith(EXAMPLES) || !Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("snapshot file missing or outside example: " + file);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Subject> subjects() throws Exception {
        List<Subject> result = new ArrayList<>(BASE_SUBJECTS);
        if (Boolean.getBoolean("ocl2cypher.allSnapshots")) {
            for (String[] row : snapshotRows()) {
                if (!row[2].equals("PIPELINE")) continue;
                result.add(new Subject(row[0], row[1], EXAMPLES.resolve(row[3]),
                        EXAMPLES.resolve(row[4]), row[5].isEmpty() ? null : EXAMPLES.resolve(row[5]),
                        EXAMPLES.resolve(row[6]), Integer.parseInt(row[7])));
            }
        }
        return result;
    }

    private static int profileEntries(List<Subject> subjects, Set<String> entries) {
        return subjects.stream().mapToInt(subject -> (int) entries.stream()
                .filter(key -> key.startsWith(subject.profile() + "/")).count()).sum();
    }

    private static int evaluateSourceOnlySnapshots(Path output) throws Exception {
        List<String> results = new ArrayList<>(List.of("snapshot,invariants,objectEvaluations,status"));
        for (String[] row : snapshotRows()) {
            if (!row[2].equals("USE_ONLY")) continue;
            var use = UseEvaluationAdapter.evaluate(EXAMPLES.resolve(row[3]),
                    EXAMPLES.resolve(row[5]), EXAMPLES.resolve(row[4]));
            assertEquals(Integer.parseInt(row[7]), use.violations().size(), row[0]);
            assertEquals(readExpected(EXAMPLES.resolve(row[6])), use.violations(), row[0]);
            var input = UseSoilInput.load(EXAMPLES.resolve(row[3]), EXAMPLES.resolve(row[4]));
            assertTrue(input.isFailure(), row[0] + " must remain outside the compiler input profile");
            assertEquals("E_USE_SOIL_INPUT", input.primaryDiagnostic().code(), row[0]);
            results.add(csv(row[0], use.violations().size(), use.objectEvaluations(), "USE_ONLY_PASS"));
        }
        Files.write(output.resolve("source-only.csv"), results, StandardCharsets.UTF_8);
        return results.size() - 1;
    }

    private static Subject subject(String id, String use, String soil, String ocl,
            String expected, int invariantCount) {
        return new Subject(id, id, EXAMPLES.resolve(use), EXAMPLES.resolve(soil),
                ocl == null ? null : EXAMPLES.resolve(ocl),
                expected == null ? null : EXAMPLES.resolve(expected), invariantCount);
    }

    private static String namespace(String subject, String runId) {
        return "Exp" + UUID.nameUUIDFromBytes((subject + "\u0000" + runId)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static String ids(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining("|"));
    }

    private static String fileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnv(String key) {
        String value = env(key, null);
        if (value == null) throw new IllegalStateException("live experiment requires " + key);
        return value;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getName() : message.replaceAll("[\\r\\n]+", " ");
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String csv(Object... values) {
        return java.util.Arrays.stream(values).map(value -> {
            String text = String.valueOf(value == null ? "" : value);
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }).collect(Collectors.joining(","));
    }

    private sealed interface CsvRow permits SubjectRow, InvariantRow, BoundaryRow {
        String csv();
    }

    private record Subject(String id, String profile, Path useFile, Path soilFile, Path oclFile,
                           Path expectedCsv, int expectedInvariants) {
    }

    private record Boundary(String id, String useRelative, String soilRelative,
                            String messageFragment) {
    }

    private record PreparedInput(Path useFile, Path soilFile) {
    }

    private record SubjectRow(String subject, String modelKey, String status,
                              int expectedInvariants, int admittedInvariants,
                              int matchedInvariants, int useObjectEvaluations,
                              int classes, int attributes, int associations,
                              int objects, int links, int graphNodes,
                              int graphRelationships, long totalNanos,
                              String diagnostic) implements CsvRow {
        static String header() {
            return "subject,modelKey,status,expectedInvariants,admittedInvariants,"
                    + "matchedInvariants,useObjectEvaluations,classes,attributes,associations,"
                    + "objects,links,graphNodes,graphRelationships,totalNanos,diagnostic";
        }

        @Override public String csv() {
            return ExampleCorpusExperimentIT.csv(subject, modelKey, status,
                    expectedInvariants, admittedInvariants, matchedInvariants,
                    useObjectEvaluations, classes, attributes, associations, objects,
                    links, graphNodes, graphRelationships, totalNanos, diagnostic);
        }
    }

    private record InvariantRow(String subject, String invariant, String compileStatus,
                                String useIds, String qIds, String neo4jIds,
                                String reviewedCsvStatus,
                                String localAgreement, String liveStatus,
                                int queryCharacters, long compileNanos,
                                long qEvaluationNanos, long neo4jExecutionNanos,
                                String diagnostic) implements CsvRow {
        static InvariantRow failure(String subject, String invariant, String useIds,
                String reviewedCsvStatus, String diagnostic, long compileNanos) {
            return new InvariantRow(subject, invariant, "FAIL", useIds, "", "",
                    reviewedCsvStatus, "false", "NOT_RUN", 0, compileNanos,
                    0, 0, diagnostic);
        }

        static InvariantRow rejection(String subject, String invariant, String useIds,
                String reviewedCsvStatus, String diagnostic, long compileNanos) {
            return new InvariantRow(subject, invariant, "EXPECTED_REJECTION", useIds,
                    "", "", reviewedCsvStatus, "NOT_APPLICABLE", "NOT_RUN", 0,
                    compileNanos, 0, 0, diagnostic);
        }

        static InvariantRow excluded(String subject, String invariant, String useIds,
                String reviewedCsvStatus, String diagnostic) {
            return new InvariantRow(subject, invariant, "EXCLUDED_OUT_OF_SCOPE", useIds,
                    "", "", reviewedCsvStatus, "NOT_APPLICABLE", "NOT_RUN", 0,
                    0, 0, 0, diagnostic);
        }

        static String header() {
            return "subject,invariant,compileStatus,useIds,qIds,neo4jIds,reviewedCsvStatus,"
                    + "localAgreement,liveStatus,queryCharacters,compileNanos,qEvaluationNanos,"
                    + "neo4jExecutionNanos,diagnostic";
        }

        @Override public String csv() {
            return ExampleCorpusExperimentIT.csv(subject, invariant, compileStatus,
                    useIds, qIds, neo4jIds, reviewedCsvStatus, localAgreement,
                    liveStatus, queryCharacters, compileNanos, qEvaluationNanos,
                    neo4jExecutionNanos, diagnostic);
        }
    }

    private record BoundaryRow(String boundary, String status, String diagnosticCode,
                               String diagnosticMessage) implements CsvRow {
        static String header() {
            return "boundary,status,diagnosticCode,diagnosticMessage";
        }

        @Override public String csv() {
            return ExampleCorpusExperimentIT.csv(boundary, status,
                    diagnosticCode, diagnosticMessage);
        }
    }
}
