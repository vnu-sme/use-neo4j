package org.uet.dse.ocl2cypher.input.use;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tzi.use.parser.shell.ShellCommandCompiler;
import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationClass;
import org.tzi.use.uml.mm.MAssociationEnd;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MClassInvariant;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.MMultiplicity;
import org.tzi.use.uml.mm.ModelFactory;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.value.BooleanValue;
import org.tzi.use.uml.ocl.value.IntegerValue;
import org.tzi.use.uml.ocl.value.RealValue;
import org.tzi.use.uml.ocl.value.StringValue;
import org.tzi.use.uml.ocl.value.UndefinedValue;
import org.tzi.use.uml.ocl.value.UnlimitedNaturalValue;
import org.tzi.use.uml.ocl.value.Value;
import org.tzi.use.uml.sys.MLink;
import org.tzi.use.uml.sys.MLinkEnd;
import org.tzi.use.uml.sys.MLinkObject;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MSystem;
import org.tzi.use.uml.sys.MSystemException;
import org.tzi.use.uml.sys.soil.MStatement;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.Stage;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.input.ModelInputBundle;
import org.uet.dse.ocl2cypher.input.SourceInvariant;
import org.uet.dse.ocl2cypher.input.StableIdDisambiguator;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.QualifierValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.model.UmlClass;
import org.uet.dse.ocl2cypher.source.model.UmlQualifier;

/**
 * Production USE/SOIL input adapter.
 *
 * <p>Syntax and execution are delegated to USE's own generated parsers and
 * SOIL runtime. This class only validates the selected executable profile and
 * projects the resulting {@link MModel}/{@link MSystem} into the compiler's
 * independent {@link SchemaModel}/{@link Snapshot} representation. Thus valid
 * USE surface syntax is not reimplemented by regular expressions.
 */
public final class UseSoilInputAdapter {
    private static final Pattern LEGACY_CREATE = Pattern.compile(
            "(?is)^create\\s+([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)"
                    + "\\s*:\\s*([A-Za-z_]\\w*)(\\s+between\\s*\\(.*\\))?$");
    private static final Pattern NEW_WITH_LITERAL_NAME = Pattern.compile(
            "(?is)^new\\s+([A-Za-z_]\\w*)\\s*\\(\\s*'((?:[^']|'')*)'\\s*\\)(.*)$");
    private static final Pattern OPERATION_CONTEXT = Pattern.compile(
            "(?m)^\\s*context\\s+[A-Za-z_]\\w*\\s*::");
    private static final Pattern CONSTRAINT_DECLARATION = Pattern.compile(
            "^(?:constraints|context|inv|pre|post)\\b.*", Pattern.CASE_INSENSITIVE);

    private UseSoilInputAdapter() {
    }

    public static Result<ModelInputBundle> load(Path usePath, Path soilPath) {
        return load(usePath, soilPath, null);
    }

    public static Result<ModelInputBundle> load(Path usePath, Path soilPath, Path oclPath) {
        Objects.requireNonNull(usePath, "USE path");
        Objects.requireNonNull(soilPath, "SOIL path");
        Path useFile = usePath.toAbsolutePath().normalize();
        Path soilFile = soilPath.toAbsolutePath().normalize();
        Path oclFile = oclPath == null ? null : oclPath.toAbsolutePath().normalize();
        try {
            requireReadable(useFile, "USE schema", ".use");
            requireReadable(soilFile, "SOIL snapshot", ".soil");
            String source = Files.readString(useFile, StandardCharsets.UTF_8);
            if (oclFile != null) {
                requireReadable(oclFile, "OCL constraints", ".ocl");
                source += System.lineSeparator() + "constraints" + System.lineSeparator()
                        + Files.readString(oclFile, StandardCharsets.UTF_8);
            }

            MModel useModel = compileUse(useFile, source);
            SchemaModel schema = projectSchema(useFile, useModel);
            List<SourceInvariant> invariants = projectInvariants(useFile, source.lines().toList(), useModel);

            MSystem system = new MSystem(useModel);
            replaySoil(system, soilFile);
            Snapshot snapshot = projectSnapshot(soilFile, schema, system);

            Result<GraphBuilder.GraphBuildArtifact> checked =
                    GraphBuilder.build(schema, snapshot);
            if (checked.isFailure()) {
                throw new InputException("SOIL snapshot does not conform to USE schema: "
                        + checked.primaryDiagnostic().message());
            }
            return Result.success(new ModelInputBundle(useFile, soilFile,
                    schema, snapshot, invariants));
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage();
            return Result.failure(Stage.E_SM, "E_USE_SOIL_INPUT", message);
        }
    }

    private static MModel compileUse(Path path, String source) {
        if (OPERATION_CONTEXT.matcher(source).find()) {
            throw profile(path, "operation contexts and pre/postconditions are outside "
                    + "the executable invariant profile");
        }
        StringWriter diagnostics = new StringWriter();
        MModel model = USECompiler.compileSpecification(
                source, path.toString(),
                new PrintWriter(diagnostics, true), new ModelFactory());
        if (model == null) {
            throw new InputException("USE specification compilation failed:\n"
                    + diagnostics.toString().strip());
        }
        return model;
    }

    private static SchemaModel projectSchema(Path path, MModel model) {
        if (!model.prePostConditions().isEmpty()) {
            throw profile(path, "pre/postconditions are outside the executable "
                    + "invariant profile");
        }
        for (MClass cls : model.classes()) {
            if (!cls.operations().isEmpty()) {
                throw profile(path, "operation declarations are outside the executable "
                        + "USE/SOIL profile (" + cls.name() + ")");
            }
            if (!cls.getOwnedProtocolStateMachines().isEmpty()) {
                throw profile(path, "protocol state machines are outside the executable "
                        + "USE/SOIL profile (" + cls.name() + ")");
            }
        }
        if (!model.dataTypes().isEmpty()) {
            throw profile(path, "USE data types are outside the executable profile");
        }
        if (!model.enumTypes().isEmpty()) {
            throw profile(path, "enumerations are outside the executable OCL_val profile");
        }

        SchemaModel.Builder builder = SchemaModel.builder(model.name());
        List<MClass> classes = model.classes().stream()
                .sorted(Comparator.comparing(MClass::name)).toList();
        for (MClass cls : classes) {
            List<String> parents = cls.parents().stream().map(MClass::name)
                    .sorted().toList();
            builder.clazz(new UmlClass(cls.name(), cls.name(), cls.isAbstract(),
                    cls instanceof MAssociationClass, parents));
        }
        if (classes.isEmpty()) {
            throw profile(path, "USE schema declares no class");
        }

        for (MClass cls : classes) {
            for (MAttribute attribute : cls.attributes()) {
                if (attribute.isDerived()) {
                    throw profile(path, "derived attribute is not materializable: "
                            + attribute.qualifiedName());
                }
                builder.attribute(UmlAttribute.of(cls.name(), attribute.name(),
                        scalarType(path, attribute.type(), "attribute "
                                + attribute.qualifiedName())));
            }
        }

        List<MAssociation> associations = model.associations().stream()
                .sorted(Comparator.comparing(MAssociation::name)).toList();
        for (MAssociation association : associations) {
            builder.association(projectAssociation(path, association));
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw profile(path, e.getMessage());
        }
    }

    private static UmlAssociation projectAssociation(Path path,
                                                      MAssociation association) {
        List<MAssociationEnd> ends = association.associationEnds();
        if (ends.size() != 2) {
            throw profile(path, "association " + association.name() + " has "
                    + ends.size() + " ends; only binary associations are supported");
        }
        if (association.isDerived() || association.isUnion()
                || association.isRedefining() || !association.getSubsets().isEmpty()
                || !association.getSubsettedBy().isEmpty()) {
            throw profile(path, "derived/union/subset/redefined association is outside "
                    + "the executable profile: " + association.name());
        }

        MAssociationEnd source = ends.get(0);
        MAssociationEnd target = ends.get(1);
        if (source.isOrdered() != target.isOrdered()) {
            throw profile(path, "association " + association.name()
                    + " has asymmetric ordered ends, which SchemaModel cannot represent");
        }
        Bounds sourceBounds = bounds(path, association.name(), source.multiplicity());
        Bounds targetBounds = bounds(path, association.name(), target.multiplicity());
        List<UmlQualifier> sourceQualifiers = source.getQualifiers().stream()
                        .map(q -> UmlQualifier.typed(q.name(), scalarType(path, q.type(),
                                "qualifier " + association.name() + "::" + q.name())))
                        .toList();
        List<UmlQualifier> targetQualifiers = target.getQualifiers().stream()
                .map(q -> UmlQualifier.typed(q.name(), scalarType(path, q.type(),
                        "qualifier " + association.name() + "::" + q.name())))
                .toList();
        int aggregationKind = Math.max(source.aggregationKind(), target.aggregationKind());
        UmlAssociation.AssociationKind kind = aggregationKind == 2
                ? UmlAssociation.AssociationKind.COMPOSITION
                : aggregationKind == 1 ? UmlAssociation.AssociationKind.AGGREGATION
                        : UmlAssociation.AssociationKind.ASSOCIATION;

        return new UmlAssociation(association.name(), association.name(),
                source.cls().name(), source.nameAsRolename(),
                sourceBounds.lower(), sourceBounds.upper(),
                target.cls().name(), target.nameAsRolename(),
                targetBounds.lower(), targetBounds.upper(), targetQualifiers,
                sourceQualifiers, source.isOrdered(), true, kind);
    }

    private static Bounds bounds(Path path, String association,
                                 MMultiplicity multiplicity) {
        if (multiplicity.getRanges().size() != 1) {
            throw profile(path, "association " + association
                    + " uses a disjoint multiplicity, which is not representable");
        }
        MMultiplicity.Range range = multiplicity.getRanges().get(0);
        return new Bounds(range.getLower(), range.getUpper());
    }

    private static List<SourceInvariant> projectInvariants(Path path, List<String> sourceLines, MModel model) {
        List<SourceInvariant> result = new ArrayList<>();
        for (MClassInvariant invariant : model.classInvariants().stream()
                .sorted(Comparator.comparing(MClassInvariant::qualifiedName)).toList()) {
            if (!(invariant.cls() instanceof MClass)) {
                throw profile(path, "invariant context is not a class: "
                        + invariant.qualifiedName());
            }
            if (invariant.isExistential()) {
                throw profile(path, "existential USE invariant is outside the executable "
                        + "invariant profile: " + invariant.qualifiedName());
            }
            if (invariant.hasVar()) {
                List<VarDecl> variables = new ArrayList<>();
                for (int i = 0; i < invariant.vars().size(); i++) {
                    variables.add(invariant.vars().varDecl(i));
                }
                if (variables.size() != 1 || !"self".equals(variables.get(0).name())) {
                    throw profile(path, "explicit/multiple invariant context variables are "
                            + "outside the executable profile: "
                            + invariant.qualifiedName());
                }
            }
            result.add(new SourceInvariant(invariant.cls().name(), invariant.name(),
                    invariantSourceBody(path, sourceLines, invariant)));
        }
        return List.copyOf(result);
    }

    /**
     * USE keeps the compiled expression but its pretty-printer omits syntax
     * that is significant to our frontend (notably {@code ()} and qualifier
     * arguments). The official parser supplies the exact body start line; this
     * method retains the validated source lexemes up to the next constraint.
     */
    private static String invariantSourceBody(Path path, List<String> lines,
                                              MClassInvariant invariant) {
        int start = invariant.getPositionInModel() - 1;
        if (start < 0 || start >= lines.size()) {
            throw profile(path, "invalid source position for invariant "
                    + invariant.qualifiedName());
        }
        String first = stripUseComment(lines.get(start)).strip();
        StringBuilder body = new StringBuilder();
        int colon = first.indexOf(':');
        String firstBody = colon >= 0 && first.substring(0, colon).contains("inv")
                ? first.substring(colon + 1).strip() : first;
        if (!firstBody.isEmpty()) body.append(firstBody);

        for (int i = start + 1; i < lines.size(); i++) {
            String line = stripUseComment(lines.get(i)).strip();
            if (line.isEmpty()) continue;
            if (CONSTRAINT_DECLARATION.matcher(line).matches()
                    || line.startsWith("@")) {
                break;
            }
            if (body.length() > 0) body.append(' ');
            body.append(line);
        }
        if (body.isEmpty()) {
            throw profile(path, "empty source body for invariant "
                    + invariant.qualifiedName());
        }
        return body.toString();
    }

    private static String stripUseComment(String text) {
        boolean quoted = false;
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                if (quoted && text.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                quoted = !quoted;
            } else if (!quoted && c == '-' && text.charAt(i + 1) == '-') {
                return text.substring(0, i);
            }
        }
        return text;
    }

    private static void replaySoil(MSystem system, Path path) throws IOException {
        StableIdDisambiguator ids = new StableIdDisambiguator();
        for (ShellSource command : shellCommands(path)) {
            NormalizedCommand normalized = normalizeCreation(command.text(), ids);
            execute(system, path, command.line(), normalized.text());
            // Full SOIL blocks/conditionals/loops can create objects below the
            // top-level command. Reserve those runtime names so a later
            // top-level duplicate still follows the canonical __n policy.
            system.state().allObjects().stream().map(MObject::name)
                    .forEach(ids::reserve);
            for (Alias alias : normalized.aliases()) {
                MObject object = system.state().objectByName(alias.physicalName());
                if (object == null) {
                    throw soilError(path, command.line(), "creation did not produce object `"
                            + alias.physicalName() + "`");
                }
                system.getVariableEnvironment().assign(alias.logicalName(), object.value());
            }
        }
    }

    private static void execute(MSystem system, Path path, int lineNumber,
                                String command) {
        StringWriter diagnostics = new StringWriter();
        MStatement statement = ShellCommandCompiler.compileShellCommand(
                system.model(), system.state(), system.getVariableEnvironment(), command,
                path + ":" + lineNumber, new PrintWriter(diagnostics, true), false);
        if (statement == null) {
            throw soilError(path, lineNumber, "USE rejected SOIL command `" + command
                    + "`:\n" + diagnostics.toString().strip());
        }
        try {
            system.execute(statement);
        } catch (MSystemException e) {
            throw new InputException(path.getFileName() + ":" + lineNumber
                    + ": USE failed to execute SOIL command `" + command + "`: "
                    + e.getMessage(), e);
        }
    }

    /** Mirrors USE shell command-file handling, including its multiline form. */
    private static List<ShellSource> shellCommands(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<ShellSource> result = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            String line = lines.get(i).strip();
            int sourceLine = i + 1;
            i++;
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("//")) {
                continue;
            }
            if (line.equals("\\")) {
                StringBuilder multiline = new StringBuilder();
                sourceLine = i + 1;
                boolean closed = false;
                while (i < lines.size()) {
                    String part = lines.get(i++);
                    if (part.equals(".")) {
                        closed = true;
                        break;
                    }
                    multiline.append(part).append(System.lineSeparator());
                }
                if (!closed) {
                    throw soilError(path, sourceLine,
                            "unterminated multiline command; expected a line containing `.`");
                }
                line = multiline.toString().strip();
            }
            if (!line.startsWith("!")) {
                throw soilError(path, sourceLine,
                        "expected a SOIL command beginning with `!` or `!!`");
            }
            String command = line.substring(line.startsWith("!!") ? 2 : 1).strip();
            if (command.isEmpty()) {
                throw soilError(path, sourceLine, "empty SOIL command");
            }
            result.add(new ShellSource(sourceLine, command));
        }
        return List.copyOf(result);
    }

    private static NormalizedCommand normalizeCreation(
            String command, StableIdDisambiguator ids) {
        Matcher legacy = LEGACY_CREATE.matcher(command);
        if (legacy.matches()) {
            String[] rawNames = legacy.group(1).split("\\s*,\\s*");
            List<String> physicalNames = new ArrayList<>();
            List<Alias> aliases = new ArrayList<>();
            for (String raw : rawNames) {
                String physical = ids.disambiguate(raw);
                physicalNames.add(physical);
                if (!raw.equals(physical)) aliases.add(new Alias(raw, physical));
            }
            String tail = legacy.group(3) == null ? "" : legacy.group(3);
            return new NormalizedCommand("create " + String.join(", ", physicalNames)
                    + " : " + legacy.group(2) + tail, List.copyOf(aliases));
        }

        Matcher modern = NEW_WITH_LITERAL_NAME.matcher(command);
        if (modern.matches()) {
            String raw = modern.group(2).replace("''", "'");
            String physical = ids.disambiguate(raw);
            String escaped = physical.replace("'", "''");
            List<Alias> aliases = raw.equals(physical) ? List.of()
                    : List.of(new Alias(raw, physical));
            return new NormalizedCommand("new " + modern.group(1) + "('" + escaped
                    + "')" + modern.group(3), aliases);
        }
        return new NormalizedCommand(command, List.of());
    }

    private static Snapshot projectSnapshot(Path path, SchemaModel schema,
                                            MSystem system) {
        Snapshot.Builder builder = Snapshot.builder();
        List<MObject> objects = system.state().allObjects().stream()
                .sorted(Comparator.comparing(MObject::name)).toList();
        for (MObject object : objects) {
            builder.object(object.name(), object.cls().name());
        }
        for (MObject object : objects) {
            for (Map.Entry<MAttribute, Value> slot
                    : object.state(system.state()).attributeValueMap().entrySet()) {
                Value value = slot.getValue();
                if (value == null || value instanceof UndefinedValue || !value.isDefined()) {
                    continue;
                }
                builder.attribute(object.name(), slot.getKey().name(),
                        scalarValue(path, value, "attribute value " + object.name() + "."
                                + slot.getKey().name()));
            }
        }

        List<MLink> links = system.state().allLinks().stream()
                .filter(link -> !link.isVirtual())
                .sorted(Comparator.comparing((MLink link) -> link.association().name())
                        .thenComparing(link -> link.linkedObjects().toString()))
                .toList();
        for (MLink link : links) {
            MAssociation association = link.association();
            if (association.associationEnds().size() != 2) {
                throw profile(path, "runtime link for non-binary association "
                        + association.name());
            }
            MLinkEnd source = link.getLinkEnd(0);
            MLinkEnd target = link.getLinkEnd(1);
            LinkQualifierValues qualifiers = qualifierValues(path, schema, association,
                    source, target);
            if (link instanceof MLinkObject linkObject) {
                builder.associationClassLinkByEnds(linkObject.name(), association.name(),
                        source.object().name(), target.object().name(),
                        qualifiers.sourceEnd(), qualifiers.targetEnd());
            } else {
                builder.linkByEnds(association.name(), source.object().name(),
                        target.object().name(), qualifiers.sourceEnd(), qualifiers.targetEnd());
            }
        }
        return builder.build();
    }

    private record LinkQualifierValues(List<QualifierValue> sourceEnd,
                                       List<QualifierValue> targetEnd) {
        private LinkQualifierValues {
            sourceEnd = List.copyOf(sourceEnd);
            targetEnd = List.copyOf(targetEnd);
        }
    }

    private static LinkQualifierValues qualifierValues(
            Path path, SchemaModel schema, MAssociation association,
            MLinkEnd source, MLinkEnd target) {
        UmlAssociation projected = schema.associationByName(association.name());
        return new LinkQualifierValues(
                qualifierValuesForEnd(path, association.name(), target,
                        projected.sourceEndQualifiers(), "source"),
                qualifierValuesForEnd(path, association.name(), source,
                        projected.targetEndQualifiers(), "target"));
    }

    private static List<QualifierValue> qualifierValuesForEnd(
            Path path, String associationName, MLinkEnd end,
            List<UmlQualifier> declarations, String endName) {
        List<Value> values = end.getQualifierValues();
        if (values.size() != declarations.size()) {
            throw profile(path, "runtime qualifier arity mismatch for "
                    + associationName + " " + endName + " end");
        }
        List<QualifierValue> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            result.add(new QualifierValue(declarations.get(i).name(),
                    scalarValue(path, values.get(i), "qualifier value "
                            + associationName)));
        }
        return List.copyOf(result);
    }

    private static OclType scalarType(Path path, Type type, String label) {
        if (type.isTypeOfBoolean()) return OclType.BOOLEAN;
        if (type.isTypeOfInteger() || type.isTypeOfUnlimitedNatural()) {
            return OclType.INTEGER;
        }
        if (type.isTypeOfReal()) return OclType.REAL;
        if (type.isTypeOfString()) return OclType.STRING;
        throw profile(path, label + " has unsupported non-scalar type " + type);
    }

    private static OclValue scalarValue(Path path, Value value, String label) {
        if (value instanceof BooleanValue booleanValue) {
            return new OclValue.BooleanValue(OclType.BOOLEAN,
                    booleanValue.value() ? OclValue.BooleanValue.Bool3.TRUE
                            : OclValue.BooleanValue.Bool3.FALSE);
        }
        if (value instanceof IntegerValue integerValue) {
            return new OclValue.IntegerValue(BigInteger.valueOf(integerValue.value()));
        }
        if (value instanceof UnlimitedNaturalValue naturalValue) {
            return new OclValue.IntegerValue(BigInteger.valueOf(naturalValue.value()));
        }
        if (value instanceof RealValue realValue) {
            double number = realValue.value();
            if (!Double.isFinite(number)) {
                throw profile(path, label + " is not a finite Real");
            }
            return new OclValue.RealValue(BigDecimal.valueOf(number));
        }
        if (value instanceof StringValue stringValue) {
            return new OclValue.StringValue(stringValue.value());
        }
        throw profile(path, label + " has unsupported value " + value
                + " : " + value.type());
    }

    private static void requireReadable(Path path, String label, String extension) throws IOException {
        if (path.getFileName() == null
                || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new InputException(label + " must be a " + extension + " file: " + path);
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException(label + " is not a readable file: " + path);
        }
    }

    private static InputException profile(Path path, String message) {
        return new InputException(path.getFileName() + ": " + message);
    }

    private static InputException soilError(Path path, int line, String message) {
        return new InputException(path.getFileName() + ":" + line + ": " + message);
    }

    private record Bounds(int lower, int upper) { }
    private record ShellSource(int line, String text) { }
    private record Alias(String logicalName, String physicalName) { }
    private record NormalizedCommand(String text, List<Alias> aliases) { }

    private static final class InputException extends IllegalArgumentException {
        private InputException(String message) {
            super(message);
        }

        private InputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
