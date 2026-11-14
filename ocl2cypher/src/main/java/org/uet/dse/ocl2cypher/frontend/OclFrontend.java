package org.uet.dse.ocl2cypher.frontend;

import static org.uet.dse.ocl2cypher.frontend.FrontendError.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.diagnostics.SourceSpan;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.omg.OmgAs;
import org.uet.dse.ocl2cypher.source.omg.OclOperation;

/**
 * Package-private recognizer/typechecker used inside {@code E_SM}.
 *
 * <p>Parse, name resolution and type analysis are one boundary here, matching
 * the specification's {@code E_SM = TypeCheck_SM ∘ Resolve_SM ∘ Parse} fusion.
 * Surface forms the grammar recognizes but {@code OCL_val} does not admit
 * ({@code null}, {@code invalid}, collection ranges) are kept as their
 * recognized AS identity so that {@code N_SM} can issue the admission failure;
 * the frontend does not decide final admission and does not invent
 * {@code BinaryExpression}/{@code UnaryExpression} metaclasses — operators
 * become resolved {@link OmgAs.OperationCallExp}s.
 *
 * <p>Precedence (weakest first), all infix levels left-associative per OMG OCL
 * 2.4: implies &lt; xor &lt; or &lt; and &lt; equality &lt; relational &lt;
 * additive &lt; multiplicative (incl. contextual {@code div}/{@code mod}) &lt;
 * unary &lt; postfix.
 */
final class OclFrontend {

    private static final List<String> ITERATORS =
            List.of("select", "reject", "exists", "forAll", "collect", "isUnique");

    private final SchemaModel sm;
    private final OclTyping typing;
    private final OclResolution resolution;
    private List<OclLexer.Tok> toks;
    private int idx;

    OclFrontend(SchemaModel sm) {
        this.sm = java.util.Objects.requireNonNull(sm, "schema model");
        this.typing = new OclTyping(sm);
        this.resolution = new OclResolution(sm, typing);
    }

    /** Public frontend product: resolved OMG-AS, never the legacy parser tree. */
    Result<List<OmgAs.OmgDocument>> elaborateOmgDocuments(String source) {
        this.toks = new OclLexer(source).tokenize();
        this.idx = 0;
        List<OmgAs.OmgDocument> docs = new ArrayList<>();
        try {
            while (!peek().isEnd()) {
                docs.add(parseDocumentEntry());
            }
            return Result.success(docs);
        } catch (FrontendError e) {
            return Result.failure(e.diagnostic());
        }
    }

    /** Public value-query product: resolved OMG-AS with declaration identity. */
    Result<OmgAs.ExpressionInOcl> elaborateOmgValueExpression(
            String source, String contextClassKey, String contextVariableName) {
        this.toks = new OclLexer(source).tokenize();
        this.idx = 0;
        try {
            if ((contextClassKey == null) != (contextVariableName == null)) {
                throw error("E_VALUE_CONTEXT",
                        "context class and variable must be present together", SourceSpan.UNKNOWN);
            }
            Scope scope = new Scope();
            OmgAs.Variable context = null;
            if (contextClassKey != null) {
                if (sm.clazz(contextClassKey) == null) {
                    throw error("E_CONTEXT_CLASS", "classifier not found: " + contextClassKey,
                            SourceSpan.UNKNOWN);
                }
                context = new OmgAs.Variable(SourceSpan.UNKNOWN, contextVariableName,
                        OclType.clazz(contextClassKey), null);
                scope.declare(context);
            }
            OmgAs.OclExpression body = parseExpression(scope);
            if (!peek().isEnd()) {
                throw error("E_PARSE", "unexpected trailing token '" + peek().text() + "'",
                        peek().span());
            }
            return Result.success(new OmgAs.ExpressionInOcl(body.span(), body, context));
        } catch (FrontendError error) {
            return Result.failure(error.diagnostic());
        }
    }

    /** One classifier context, optionally enclosed in a package. */
    private OmgAs.OmgDocument parseDocumentEntry() {
        if (peek().isWord("package")) {
            // Package only scopes classifier lookup; it is not a semantic OCL package.
            advance();
            String path = parsePathName();
            List<OmgAs.OmgDocument> inner = new ArrayList<>();
            while (!peek().isEnd() && !peek().isWord("endpackage")) {
                inner.add(parseDocumentEntry());
            }
            expectWord("endpackage");
            if (inner.size() != 1) {
                throw error("E_PACKAGE_CONTEXT_COUNT",
                        "package " + path + " must contain exactly one classifier context",
                        SourceSpan.UNKNOWN);
            }
            return inner.get(0);
        }
        if (peek().isWord("context")) {
            advance();
            return parseClassifierContext();
        }
        throw error("E_PARSE", "unexpected token '" + peek().text() + "'", peek().span());
    }

    private OmgAs.OmgDocument parseClassifierContext() {
        // head: pathName | simpleName ':' pathName
        String first = parseSimpleName();
        String classPath;
        String contextVar = "self";
        if (peek().isSymbol(":")) {
            advance();
            contextVar = first;
            classPath = parsePathName();
        } else {
            classPath = first;
        }
        if (sm.clazz(classPath) == null) {
            throw error("E_CONTEXT_CLASS", "classifier not found: " + classPath, peek().span());
        }
        Scope scope = new Scope();
        OmgAs.Variable context = new OmgAs.Variable(SourceSpan.UNKNOWN, contextVar,
                OclType.clazz(classPath), null);
        scope.declare(context);
        List<OmgAs.Constraint> invs = new ArrayList<>();
        while (peek().isWord("inv")) {
            advance();
            String invName = null;
            SourceSpan span = peek().span();
            if (peek().kind() == OclLexer.Kind.WORD && !peek().isSymbol(":")) {
                invName = parseSimpleName();
            }
            expectSymbol(":");
            OmgAs.OclExpression body = parseExpression(scope);
            if (body.type() == null || !body.type().equals(OclType.BOOLEAN)) {
                throw error("E_INVARIANT_TYPE",
                        "invariant body must be Boolean, found " + body.type(), span);
            }
            invs.add(new OmgAs.Constraint(invName, classPath,
                    new OmgAs.ExpressionInOcl(span, body, context)));
        }
        if (invs.isEmpty()) {
            throw error("E_PARSE", "classifier context requires at least one invariant",
                    peek().span());
        }
        return new OmgAs.OmgDocument(SourceSpan.UNKNOWN, classPath, invs);
    }

    // ---- expression precedence chain -------------------------------------

    private OmgAs.OclExpression parseExpression(Scope scope) {
        return parseImplies(scope);
    }

    /**
     * {@code implies} is RIGHT-associative, per the concrete grammar
     * (§2: {@code impliesExpression = xorExpression, [ "implies", impliesExpression ]}).
     * So {@code a implies b implies c} parses as {@code a implies (b implies c)}.
     */
    private OmgAs.OclExpression parseImplies(Scope scope) {
        OmgAs.OclExpression left = parseXor(scope);
        if (peek().isWord("implies")) {
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression right = parseImplies(scope);
            typing.requireBoolean(left.type(), sp);
            typing.requireBoolean(right.type(), sp);
            return typing.binary(OclOperation.BOOLEAN_IMPLIES, left, right, OclType.BOOLEAN, sp);
        }
        return left;
    }

    private OmgAs.OclExpression parseXor(Scope scope) {
        return parseBooleanChain(scope, this::parseOr, "xor", OclOperation.BOOLEAN_XOR);
    }

    private OmgAs.OclExpression parseOr(Scope scope) {
        return parseBooleanChain(scope, this::parseAnd, "or", OclOperation.BOOLEAN_OR);
    }

    private OmgAs.OclExpression parseAnd(Scope scope) {
        return parseBooleanChain(scope, this::parseEquality, "and", OclOperation.BOOLEAN_AND);
    }

    private OmgAs.OclExpression parseBooleanChain(Scope scope,
            java.util.function.Function<Scope, OmgAs.OclExpression> operand,
            String token, OclOperation operation) {
        var left = operand.apply(scope);
        while (peek().isWord(token)) {
            var span = advance().span();
            var right = operand.apply(scope);
            typing.requireBoolean(left.type(), span);
            typing.requireBoolean(right.type(), span);
            left = typing.binary(operation, left, right, OclType.BOOLEAN, span);
        }
        return left;
    }

    private OmgAs.OclExpression parseEquality(Scope scope) {
        OmgAs.OclExpression left = parseRelational(scope);
        while (peek().isSymbol("=") || peek().isSymbol("<>")) {
            boolean eq = peek().isSymbol("=");
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression right = parseRelational(scope);
            OmgAs.OclExpression[] inferred = typing.inferEmptyCollectionPair(left, right, sp);
            left = inferred[0];
            right = inferred[1];
            OclType join = typing.joinType(left.type(), right.type(), sp);
            if (join.isCollection()
                    && (typing.containsOrderedNavigation(left,
                                java.util.Collections.newSetFromMap(
                                        new java.util.IdentityHashMap<>()))
                        || typing.containsOrderedNavigation(right,
                                java.util.Collections.newSetFromMap(
                                        new java.util.IdentityHashMap<>())))) {
                throw error("E_UNSUPPORTED_ORDERED_EQUALITY",
                        "collection equality would observe order erased from an ordered "
                                + "association navigation",
                        sp);
            }
            left = typing.binary(eq ? OclOperation.VALUE_EQUAL : OclOperation.VALUE_NOT_EQUAL,
                    typing.coerce(left, join, sp), typing.coerce(right, join, sp), OclType.BOOLEAN, sp);
        }
        return left;
    }

    private OmgAs.OclExpression parseRelational(Scope scope) {
        OmgAs.OclExpression left = parseAdditive(scope);
        while (peek().isSymbol("<") || peek().isSymbol("<=")
                || peek().isSymbol(">") || peek().isSymbol(">=")) {
            String op = peek().text();
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression right = parseAdditive(scope);
            typing.requireNumeric(left.type(), sp);
            typing.requireNumeric(right.type(), sp);
            OclType join = typing.joinType(left.type(), right.type(), sp);
            OclOperation kind = switch (op) {
                case "<" -> OclOperation.LESS_THAN;
                case "<=" -> OclOperation.LESS_THAN_OR_EQUAL;
                case ">" -> OclOperation.GREATER_THAN;
                default -> OclOperation.GREATER_THAN_OR_EQUAL;
            };
            left = typing.binary(kind, typing.coerce(left, join, sp), typing.coerce(right, join, sp),
                    OclType.BOOLEAN, sp);
        }
        return left;
    }

    private OmgAs.OclExpression parseAdditive(Scope scope) {
        OmgAs.OclExpression left = parseMultiplicative(scope);
        while (peek().isSymbol("+") || peek().isSymbol("-")) {
            boolean add = peek().isSymbol("+");
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression right = parseMultiplicative(scope);
            typing.requireNumeric(left.type(), sp);
            typing.requireNumeric(right.type(), sp);
            OclType join = typing.joinType(left.type(), right.type(), sp);
            left = typing.binary(add ? OclOperation.NUMERIC_ADD : OclOperation.NUMERIC_SUBTRACT,
                    typing.coerce(left, join, sp), typing.coerce(right, join, sp), join, sp);
        }
        return left;
    }

    private OmgAs.OclExpression parseMultiplicative(Scope scope) {
        OmgAs.OclExpression left = parseUnary(scope);
        while (true) {
            String op = null;
            if (peek().isSymbol("*")) {
                op = "*";
            } else if (peek().isSymbol("/")) {
                op = "/";
            } else if (peek().isWord("div")) {
                op = "div";
            } else if (peek().isWord("mod")) {
                op = "mod";
            }
            if (op == null) {
                return left;
            }
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression right = parseUnary(scope);
            switch (op) {
                case "*" -> {
                    typing.requireNumeric(left.type(), sp);
                    typing.requireNumeric(right.type(), sp);
                    OclType join = typing.joinType(left.type(), right.type(), sp);
                    left = typing.binary(OclOperation.NUMERIC_MULTIPLY,
                            typing.coerce(left, join, sp), typing.coerce(right, join, sp), join, sp);
                }
                case "/" -> {
                    typing.requireNumeric(left.type(), sp);
                    typing.requireNumeric(right.type(), sp);
                    left = typing.binary(OclOperation.REAL_DIVIDE,
                            typing.coerce(left, OclType.REAL, sp), typing.coerce(right, OclType.REAL, sp),
                            OclType.REAL, sp);
                }
                default -> {
                    typing.requireInteger(left.type(), sp);
                    typing.requireInteger(right.type(), sp);
                    left = typing.binary("div".equals(op)
                                    ? OclOperation.INTEGER_DIVIDE : OclOperation.INTEGER_MOD,
                            left, right, OclType.INTEGER, sp);
                }
            }
        }
    }

    private OmgAs.OclExpression parseUnary(Scope scope) {
        if (peek().isWord("not")) {
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression operand = parseUnary(scope);
            if (operand.type() == null || !operand.type().equals(OclType.BOOLEAN)) {
                throw error("E_TYPE", "not requires Boolean operand", sp);
            }
            return typing.operation(sp, OclOperation.BOOLEAN_NOT, List.of(operand), OclType.BOOLEAN);
        }
        if (peek().isSymbol("-")) {
            SourceSpan sp = peek().span();
            advance();
            OmgAs.OclExpression operand = parseUnary(scope);
            typing.requireNumeric(operand.type(), sp);
            return typing.operation(sp, OclOperation.NUMERIC_NEGATE, List.of(operand), operand.type());
        }
        return parsePostfix(scope);
    }

    private OmgAs.OclExpression parsePostfix(Scope scope) {
        OmgAs.OclExpression e = parsePrimary(scope);
        while (true) {
            if (peek().isSymbol(".")) {
                SourceSpan sp = peek().span();
                advance();
                e = parseCallOn(sp, e, /*dotCall*/ true, scope);
            } else if (peek().isSymbol("->")) {
                SourceSpan sp = peek().span();
                advance();
                e = parseCallOn(sp, e, /*dotCall*/ false, scope);
            } else {
                return e;
            }
        }
    }

    private OmgAs.OclExpression parseCallOn(SourceSpan sp, OmgAs.OclExpression receiver,
                                             boolean dotCall, Scope scope) {
        String name = parseSimpleName();
        if (ITERATORS.contains(name) && !dotCall) {
            return parseIterator(sp, name, receiver, scope);
        }
        // Qualified navigation: e.r[q1, q2]
        if (peek().isSymbol("[")) {
            advance();
            List<OmgAs.OclExpression> quals = new ArrayList<>();
            if (!peek().isSymbol("]")) {
                quals.add(parseExpression(scope));
                while (peek().isSymbol(",")) {
                    advance();
                    quals.add(parseExpression(scope));
                }
            }
            expectSymbol("]");
            return resolution.resolveProperty(sp, receiver, name, quals);
        }
        if (peek().isSymbol("(")) {
            advance();
            List<OmgAs.OclExpression> args = new ArrayList<>();
            if (!peek().isSymbol(")")) {
                args.add(parseExpression(scope));
                while (peek().isSymbol(",")) {
                    advance();
                    args.add(parseExpression(scope));
                }
            }
            expectSymbol(")");
            return resolution.resolveOperation(sp, receiver, name, args, dotCall);
        }
        // Bare name access: property or zero-arg operation
        return resolution.resolveProperty(sp, receiver, name, List.of());
    }

    private OmgAs.OclExpression parseIterator(SourceSpan sp, String name,
                                               OmgAs.OclExpression source, Scope scope) {
        expectSymbol("(");
        if (source.type() == null || !source.type().isCollection()) {
            throw error("E_INVALID_CALL_SHAPE",
                    "iterator source must be Set or Bag, found " + source.type(), sp);
        }
        OclType elementType = source.type().elementType();
        String varName = null;
        OclType declared = null;
        OmgAs.OclExpression body = null;
        OmgAs.Variable iteratorVariable = null;
        if (peek().isSymbol("|")) {
            throw error("E_ARITY", "iterator requires a body", peek().span());
        }
        // iteratorDeclaration: simpleName [':' type] '|' expression
        if (peek().kind() == OclLexer.Kind.WORD) {
            int save = idx;
            String candidate = parseSimpleName();
            if (peek().isSymbol(":")) {
                advance();
                declared = parseType();
                expectSymbol("|");
                varName = candidate;
            } else if (peek().isSymbol("|")) {
                expectSymbol("|");
                varName = candidate;
            } else {
                // Not a declaration: parse the already-read token as the body
                // of the zero-iterator shorthand (implicit `it`).  The old
                // implementation restored the token index but then expected a
                // second opening parenthesis, rejecting valid forms such as
                // `xs->forAll(age >= 18)`.
                idx = save;
                Scope inner = scope.child();
                iteratorVariable = new OmgAs.Variable(sp, "it", elementType, null);
                inner.declare(iteratorVariable);
                body = parseExpression(inner);
                varName = "it";
            }
        } else {
            expectSymbol("|");
            varName = null;
        }
        if (body == null && varName != null) {
            OclType binder = declared == null ? elementType : declared;
            if (declared != null && !typing.conforms(elementType, declared)) {
                throw error("E_TYPE", "iterator binder type " + declared
                        + " is not compatible with element type " + elementType, sp);
            }
            Scope inner = scope.child();
            iteratorVariable = new OmgAs.Variable(sp, varName, binder, null);
            inner.declare(iteratorVariable);
            body = parseExpression(inner);
        } else if (body == null) {
            // implicit iterator name `it`
            Scope inner = scope.child();
            iteratorVariable = new OmgAs.Variable(sp, "it", elementType, null);
            inner.declare(iteratorVariable);
            body = parseExpression(inner);
            varName = "it";
        }
        expectSymbol(")");
        OclType result;
        if (name.equals("select") || name.equals("reject")) {
            if (body.type() == null || !body.type().equals(OclType.BOOLEAN)) {
                throw error("E_TYPE", name + " body must be Boolean", sp);
            }
            result = source.type();
        } else if (name.equals("exists") || name.equals("forAll")) {
            if (body.type() == null || !body.type().equals(OclType.BOOLEAN)) {
                throw error("E_TYPE", name + " body must be Boolean", sp);
            }
            result = OclType.BOOLEAN;
        } else if (name.equals("isUnique")) {
            if (body.type() == null || !body.type().isAtomic()) {
                throw error("N_UNSUPPORTED_CONSTRUCT",
                        "production isUnique extension requires an atomic body", sp);
            }
            result = OclType.BOOLEAN;
        } else {
            if (body.type() == null || !body.type().isAtomic()) {
                throw error("N_UNSUPPORTED_CONSTRUCT",
                        "collect body must be atomic in OCL_val", sp);
            }
            result = OclType.bag(body.type());
        }
        return new OmgAs.IteratorExp(sp, name, source, List.of(iteratorVariable), body, result);
    }

    private OmgAs.OclExpression parsePrimary(Scope scope) {
        OclLexer.Tok t = peek();
        SourceSpan sp = t.span();
        if (t.isSymbol("(")) {
            advance();
            OmgAs.OclExpression e = parseExpression(scope);
            expectSymbol(")");
            return e;
        }
        if (t.isWord("if")) {
            advance();
            OmgAs.OclExpression c = parseExpression(scope);
            expectWord("then");
            OmgAs.OclExpression thenE = parseExpression(scope);
            expectWord("else");
            OmgAs.OclExpression elseE = parseExpression(scope);
            expectWord("endif");
            if (c.type() == null || !c.type().equals(OclType.BOOLEAN)) {
                throw error("E_TYPE", "if condition must be Boolean", sp);
            }
            OmgAs.OclExpression[] inferred = typing.inferEmptyCollectionPair(thenE, elseE, sp);
            thenE = inferred[0];
            elseE = inferred[1];
            OclType join = typing.joinType(thenE.type(), elseE.type(), sp);
            return new OmgAs.IfExp(sp, c, typing.coerce(thenE, join, sp),
                    typing.coerce(elseE, join, sp), join);
        }
        if (t.isWord("let")) {
            advance();
            String name = parseSimpleName();
            OclType declared = null;
            if (peek().isSymbol(":")) {
                advance();
                declared = parseType();
            }
            expectSymbol("=");
            OmgAs.OclExpression init = parseExpression(scope);
            expectWord("in");
            if (declared != null) {
                init = typing.coerce(init, declared, sp);
            } else if (init.type() == null) {
                throw error("E_UNINFERRED_EMPTY_COLLECTION_TYPE",
                        "empty collection literal requires a declared let type", sp);
            }
            OclType binderType = declared == null ? init.type() : declared;
            Scope inner = scope.child();
            OmgAs.Variable binder = new OmgAs.Variable(sp, name, binderType, init);
            inner.declare(binder);
            OmgAs.OclExpression inExpr = parseExpression(inner);
            return new OmgAs.LetExp(sp, binder, inExpr);
        }
        if (t.kind() == OclLexer.Kind.INTEGER) {
            advance();
            return new OmgAs.IntegerLiteralExp(sp, t.intValue());
        }
        if (t.kind() == OclLexer.Kind.REAL) {
            advance();
            return new OmgAs.RealLiteralExp(sp, t.realValue());
        }
        if (t.kind() == OclLexer.Kind.STRING) {
            advance();
            return new OmgAs.StringLiteralExp(sp, t.text());
        }
        if (t.isWord("true")) {
            advance();
            return new OmgAs.BooleanLiteralExp(sp, true);
        }
        if (t.isWord("false")) {
            advance();
            return new OmgAs.BooleanLiteralExp(sp, false);
        }
        if (t.isWord("null")) {
            advance();
            return new OmgAs.NullLiteralExp(sp);
        }
        if (t.isWord("invalid")) {
            advance();
            return new OmgAs.InvalidLiteralExp(sp);
        }
        if (t.isWord("Set") || t.isWord("Bag")) {
            return parseCollectionLiteral(scope);
        }
        if (t.kind() == OclLexer.Kind.WORD) {
            // pathName possibly ending in a call or a variable / classifier
            int save = idx;
            List<String> segs = parseQualifiedNameSegments();
            String joined = String.join("::", segs);
            // self / variable lookup
            if (segs.size() == 1) {
                Optional<OmgAs.Variable> var = scope.lookup(segs.get(0));
                if (var.isPresent()) {
                    // segments already consumed by parseQualifiedNameSegments
                    return new OmgAs.VariableExp(sp, var.get());
                }
            }
            if (sm.hasClass(joined)) {
                return new OmgAs.TypeExp(sp, joined);
            }
            idx = save;
            String name = parseSimpleName();
            if (peek().isSymbol("(")) {
                advance();
                List<OmgAs.OclExpression> args = new ArrayList<>();
                if (!peek().isSymbol(")")) {
                    args.add(parseExpression(scope));
                    while (peek().isSymbol(",")) {
                        advance();
                        args.add(parseExpression(scope));
                    }
                }
                expectSymbol(")");
                // implicit-operation-call on self
                OmgAs.Variable self = scope.lookup("self").orElseThrow(() ->
                        error("E_RESOLUTION", "implicit operation requires self", sp));
                OmgAs.OclExpression selfRef = new OmgAs.VariableExp(sp, self);
                return resolution.resolveOperation(sp, selfRef, name, args, true);
            }
            Optional<OmgAs.Variable> var = scope.lookup(name);
            if (var.isEmpty()) {
                throw error("E_RESOLUTION", "unresolved name: " + name, sp);
            }
            return new OmgAs.VariableExp(sp, var.get());
        }
        throw error("E_PARSE", "unexpected token '" + t.text() + "'", sp);
    }

    private OmgAs.OclExpression parseCollectionLiteral(Scope scope) {
        SourceSpan sp = peek().span();
        boolean isSet = peek().isWord("Set");
        advance();
        expectSymbol("{");
        List<OmgAs.CollectionLiteralPart> parts = new ArrayList<>();
        if (!peek().isSymbol("}")) {
            while (true) {
                OmgAs.OclExpression first = parseExpression(scope);
                if (peek().isSymbol("..")) {
                    advance();
                    OmgAs.OclExpression last = parseExpression(scope);
                    if (!OclType.INTEGER.equals(first.type())
                            || !OclType.INTEGER.equals(last.type())) {
                        throw error("E_TYPE",
                                "collection range endpoints must be Integer, found "
                                        + first.type() + " and " + last.type(), sp);
                    }
                    parts.add(new OmgAs.CollectionRange(first.span(), first, last));
                } else {
                    parts.add(new OmgAs.CollectionItem(first.span(), first));
                }
                if (!peek().isSymbol(",")) {
                    break;
                }
                advance();
            }
        }
        expectSymbol("}");
        if (parts.isEmpty()) {
            return new OmgAs.CollectionLiteralExp(sp, isSet ? OmgAs.OmgCollectionKind.SET
                    : OmgAs.OmgCollectionKind.BAG, List.of(), null);
        }
        OclType join = null;
        for (OmgAs.CollectionLiteralPart part : parts) {
            if (part.type() == null) {
                throw error("E_TYPE", "collection element is unadmitted", sp);
            }
            join = join == null ? part.type() : typing.joinType(join, part.type(), sp);
        }
        if (join.isCollection()) {
            throw error("E_UNSUPPORTED_NESTED_COLLECTION",
                    "nested collections are outside OCL_val", sp);
        }
        List<OmgAs.CollectionLiteralPart> coercedParts = new ArrayList<>(parts.size());
        for (OmgAs.CollectionLiteralPart part : parts) {
            if (part instanceof OmgAs.CollectionItem item) {
                OmgAs.OclExpression coerced = typing.coerce(item.item, join, sp);
                coercedParts.add(new OmgAs.CollectionItem(coerced.span(), coerced));
            } else {
                // A range denotes Integer elements. Keep the resolved range in
                // OMG-AS so N_SM can enforce the executable-slice boundary.
                coercedParts.add(part);
            }
        }
        OclType type = isSet ? OclType.set(join) : OclType.bag(join);
        return new OmgAs.CollectionLiteralExp(sp, isSet ? OmgAs.OmgCollectionKind.SET
                : OmgAs.OmgCollectionKind.BAG, coercedParts, type);
    }

    // ---- resolution helpers ----------------------------------------------

    /** Resolve once at E_SM and reject unrelated inherited declarations as ambiguous. */

    // ---- typing helpers ---------------------------------------------------

    // ---- token helpers ----------------------------------------------------

    private OclLexer.Tok peek() {
        return toks.get(idx);
    }

    private OclLexer.Tok advance() {
        OclLexer.Tok t = toks.get(idx);
        if (t.kind() != OclLexer.Kind.EOF) {
            idx++;
        }
        return t;
    }

    private void expectSymbol(String s) {
        if (!peek().isSymbol(s)) {
            throw error("E_PARSE", "expected '" + s + "', found '" + peek().text() + "'",
                    peek().span());
        }
        advance();
    }

    private void expectWord(String w) {
        if (!peek().isWord(w)) {
            throw error("E_PARSE", "expected '" + w + "', found '" + peek().text() + "'",
                    peek().span());
        }
        advance();
    }

    private String parseSimpleName() {
        OclLexer.Tok t = peek();
        if (t.kind() != OclLexer.Kind.WORD) {
            throw error("E_PARSE", "expected a name, found '" + t.text() + "'", t.span());
        }
        advance();
        return t.text();
    }

    private List<String> parseQualifiedNameSegments() {
        List<String> segs = new ArrayList<>();
        segs.add(parseSimpleName());
        while (peek().isSymbol("::")) {
            advance();
            segs.add(parseSimpleName());
        }
        return segs;
    }

    private String parsePathName() {
        return String.join("::", parseQualifiedNameSegments());
    }

    private OclType parseType() {
        OclLexer.Tok t = peek();
        SourceSpan sp = t.span();
        if (t.isWord("Boolean") || t.isWord("Integer") || t.isWord("Real") || t.isWord("String")) {
            advance();
            return switch (t.text()) {
                case "Boolean" -> OclType.BOOLEAN;
                case "Integer" -> OclType.INTEGER;
                case "Real" -> OclType.REAL;
                default -> OclType.STRING;
            };
        }
        if (t.isWord("Set") || t.isWord("Bag")) {
            boolean set = t.isWord("Set");
            advance();
            expectSymbol("(");
            OclType el = parseType();
            expectSymbol(")");
            return set ? OclType.set(el) : OclType.bag(el);
        }
        String path = parsePathName();
        if (sm.clazz(path) == null) {
            throw error("E_TYPE", "unknown type " + path, sp);
        }
        return OclType.clazz(path);
    }

    /** Parser control-flow carrier; never a runtime value. */

    /** Lexical scope: declaration lookup is by name but each binder is fresh. */
    static final class Scope {
        private final java.util.Map<String, OmgAs.Variable> map =
                new java.util.LinkedHashMap<>();
        private final Scope parent;

        Scope() {
            this.parent = null;
        }

        private Scope(Scope parent) {
            this.parent = parent;
        }

        Scope child() {
            return new Scope(this);
        }

        void declare(OmgAs.Variable declaration) {
            map.put(declaration.name, declaration);
        }

        Optional<OmgAs.Variable> lookup(String name) {
            Scope s = this;
            while (s != null) {
                OmgAs.Variable declaration = s.map.get(name);
                if (declaration != null) {
                    return Optional.of(declaration);
                }
                s = s.parent;
            }
            return Optional.empty();
        }
    }
}
