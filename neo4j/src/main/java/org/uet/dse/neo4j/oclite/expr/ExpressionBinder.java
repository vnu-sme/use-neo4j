package org.uet.dse.neo4j.oclite.expr;

import org.uet.dse.neo4j.oclite.Neo4jRepository;
import org.uet.dse.neo4j.oclite.ast.*;


import java.util.*;
import java.util.stream.Collectors;

public class ExpressionBinder {
    private String modelName;
    private final Deque<Set<String>> scopeStack = new ArrayDeque<>();
    private Neo4jRepository neo4jRepository;
    public ExpressionBinder(String modelName) {
        this.neo4jRepository = new Neo4jRepository(modelName);
        this.modelName = modelName;
        scopeStack.push(new HashSet<>());
    }
    private void enterScope(String varName) {
        Set<String> newScope = new HashSet<>();
        newScope.add(varName);
        scopeStack.push(newScope);
    }

    private void enterScope(Collection<String> varNames) {
        Set<String> newScope = new HashSet<>(varNames);
        scopeStack.push(newScope);
    }

    private void exitScope() {
        scopeStack.pop();
    }

    private boolean isLocalVar(String name) {
        for (Set<String> scope : scopeStack) {
            if (scope.contains(name)) return true;
        }
        return false;
    }

    public ExpressionNode bind(ASTNode node) {
//        if (node instanceof ASTLiteral) {
//            return new ConstantExpression(((ASTLiteral) node).value);
//        }
        if (node instanceof ASTContext) {
            ASTContext context = (ASTContext) node;
            List<String> aliases = new ArrayList<>();
            aliases.add("self");
            if (context.contextVariableName != null && !context.contextVariableName.isBlank()) {
                aliases.add(context.contextVariableName);
            }
            enterScope(aliases);
            ExpressionNode expression = bind(context.expression);
            exitScope();
            return expression;
        }

        //p1
        if (node instanceof ASTVar) {
            String varName = ((ASTVar) node).name;

            if (varName.equals("self") || isLocalVar(varName)) {
                return new VariableExpression(varName);
            }
            if (isLocalVar(varName)) {
                return new VariableExpression(varName);
            }

            if (neo4jRepository.checkClassExistsInDb(varName)) {
                return new TypeExpression(varName);
            }
            if (neo4jRepository.isVariableExist(varName)) {
                return new VariableExpression(varName);
            }
            throw new RuntimeException("Semantic Error: Đối tượng '" + varName + "' không tồn tại trong Neo4j.");
        }

        if (node instanceof ASTMethodCall) {
            ASTMethodCall mc = (ASTMethodCall) node;
            ExpressionNode sourceExec = bind(mc.source);

            if (mc.methodName.equalsIgnoreCase("allInstances") || mc.methodName.equalsIgnoreCase("allInstance")) {
                // Kiểm tra xem vế trước có phải là một Class không
                if (sourceExec instanceof TypeExpression) {
                    String className = ((TypeExpression) sourceExec).getTypeName();
                    return new AllInstancesExpression(className);
                } else {
                    throw new RuntimeException("Lỗi: allInstances() chỉ được gọi trên một Class.");
                }
            }
            String mName = mc.methodName;
            if (mName.equals("equals") || mName.equals("after") || mName.equals("before")) {
                if (mc.args.size() != 1) {
                    throw new RuntimeException(mName + "() expects one argument.");
                }
                String operator = switch (mName) {
                    case "after" -> ">";
                    case "before" -> "<";
                    default -> "=";
                };
                return new BinaryExpression(sourceExec, operator, bind(mc.args.get(0)));
            }
            if (mName.equals("isDefined")) {
                return new IsDefinedExpression(sourceExec, true);
            }
            else if (mName.equals("isUndefined")) {
                return new IsDefinedExpression(sourceExec, false);
            }
            else if (mName.equals("oclIsTypeOf") || mName.equals("oclIsKindOf")) {
                String typeName = typeNameArg(mc.args);
                if (typeName == null) {
                    throw new RuntimeException(mName + " expects a type name.");
                }
                return new TypeCheckExpression(sourceExec, typeName, mName.equals("oclIsKindOf"));
            }
            else if (mName.equals("oclAsType")) {
                String typeName = typeNameArg(mc.args);
                if (typeName == null) {
                    throw new RuntimeException(mName + " expects a type name.");
                }
                return new TypeOperationExpression(sourceExec, typeName, true, false);
            }
            else if (mName.equals("selectByType") || mName.equals("selectByKind")) {
                String typeName = typeNameArg(mc.args);
                if (typeName == null) {
                    throw new RuntimeException(mName + " expects a type name.");
                }
                return new TypeOperationExpression(sourceExec, typeName, mName.equals("selectByKind"), true);
            }
            else if (mc.args.isEmpty()) {
                if (neo4jRepository.isRelationship(sourceExec, mName)) {
                    return new NavigationExpression(sourceExec, mName);
                }
                return new PropertyExpression(sourceExec, mName);
            }


        }

        //(.)
        if (node instanceof ASTProperty) {
            ASTProperty prop = (ASTProperty) node;
            ExpressionNode sourceNode = bind(prop.source);

            if (sourceNode instanceof TypeExpression
                && (prop.name.equalsIgnoreCase("allInstances") || prop.name.equalsIgnoreCase("allInstance"))) {
                return new AllInstancesExpression(((TypeExpression) sourceNode).getTypeName());
            }

            if (neo4jRepository.isRelationship(sourceNode, prop.name)) {
                return new NavigationExpression(sourceNode, prop.name);
            } else {
                return new PropertyExpression(sourceNode, prop.name);
            }
        }

        if (node instanceof ASTQualifiedNavigation) {
            ASTQualifiedNavigation navigation = (ASTQualifiedNavigation) node;
            ExpressionNode sourceNode = bind(navigation.source);
            if (neo4jRepository.isRelationship(sourceNode, navigation.roleName)) {
                return new NavigationExpression(sourceNode, navigation.roleName);
            }
            return new PropertyExpression(sourceNode, navigation.roleName);
        }

//        if (node instanceof ASTBinary) {
//            ASTBinary bin = (ASTBinary) node;
//            ExpressionNode left = bind(bin.left);
//            ExpressionNode right = bind(bin.right);
//            return new BinaryExpression(left, bin.op, right);
//        }
        if (node instanceof ASTBinary) {
            ASTBinary bin = (ASTBinary) node;
            ExpressionNode left = bind(bin.left);
            ExpressionNode right = bind(bin.right);

            return new BinaryExpression(left, bin.op, right);
        }

        if (node instanceof ASTIntegerLiteral) {
            return new ConstantExpression(((ASTIntegerLiteral) node).value);
        }

        if (node instanceof ASTRealLiteral) {
            return new ConstantExpression(((ASTRealLiteral) node).value);
        }

        if (node instanceof ASTStringLiteral) {
            return new ConstantExpression(((ASTStringLiteral) node).value);
        }

        if (node instanceof ASTBooleanLiteral) {
            return new ConstantExpression(((ASTBooleanLiteral)node).value);
        }

        if (node instanceof ASTNullLiteral) {
            return new ConstantExpression(null);
        }

        if (node instanceof ASTUndefinedLiteral) {
            return new ConstantExpression(null);
        }

        if (node instanceof ASTEnumLiteral) {
            return new ConstantExpression(((ASTEnumLiteral) node).value());
        }

        if (node instanceof ASTTypeLiteral) {
            return new TypeExpression(((ASTTypeLiteral) node).typeName);
        }

        if (node instanceof ASTCollectionOp) {
            ASTCollectionOp colAST = (ASTCollectionOp) node;
            ExpressionNode sourceNode = bind(colAST.source);

            if (colAST.opName.equals("selectByType") || colAST.opName.equals("selectByKind")) {
                String typeName = typeNameArg(colAST.args);
                if (typeName == null) {
                    throw new RuntimeException(colAST.opName + " expects a type name.");
                }
                return new TypeOperationExpression(sourceNode, typeName, colAST.opName.equals("selectByKind"), true);
            }

            List<ExpressionNode> bindedArgs = new ArrayList<>();
            for (ASTNode arg : colAST.args) {
                bindedArgs.add((ExpressionNode) bind(arg));
            }

            return new CollectionOpExpression(sourceNode, colAST.opName, bindedArgs);
        }

        if (node instanceof ASTLet) {
            ASTLet let = (ASTLet) node;
            List<LetExpression.Binding> bindings = new ArrayList<>();
            int openedScopes = 0;
            for (ASTLetBinding binding : let.bindings) {
                bindings.add(new LetExpression.Binding(binding.variable.name, bind(binding.value)));
                enterScope(binding.variable.name);
                openedScopes++;
            }
            ExpressionNode body = bind(let.body);
            for (int i = 0; i < openedScopes; i++) {
                exitScope();
            }
            return new LetExpression(bindings, body);
        }

        if (node instanceof ASTIf) {
            ASTIf ifNode = (ASTIf) node;
            return new IfExpression(bind(ifNode.condition), bind(ifNode.thenExpression), bind(ifNode.elseExpression));
        }

        if (node instanceof ASTUnary) {
            ASTUnary unary = (ASTUnary) node;
            return new UnaryExpression(unary.operator, bind(unary.expression));
        }

        if (node instanceof ASTCollectionLiteral) {
            ASTCollectionLiteral literal = (ASTCollectionLiteral) node;
            List<ExpressionNode> items = new ArrayList<>();
            for (ASTExpression item : literal.items) {
                items.add(bind(item));
            }
            return new CollectionLiteralExpression(literal.kind, items);
        }

        if (node instanceof ASTRange) {
            ASTRange range = (ASTRange) node;
            return new RangeExpression(bind(range.start), bind(range.end));
        }

        if (node instanceof ASTQuery) {
            ASTQuery query = (ASTQuery) node;
            ExpressionNode sourceNode = bind(query.source);
            List<String> varNames = query.variables.stream().map(v -> v.name).collect(Collectors.toList());
            if (!varNames.isEmpty()) {
                enterScope(varNames);
            } else {
                enterScope("it");
            }
            ExpressionNode bodyNode = bind(query.body);
            exitScope();
            return new QueryExpression(sourceNode, query.operation, varNames, bodyNode);
        }

        if (node instanceof ASTIterate) {
            ASTIterate iterate = (ASTIterate) node;
            ExpressionNode sourceNode = bind(iterate.source);
            ExpressionNode accumulatorInit = bind(iterate.accumulator.value);
            enterScope(List.of(iterate.iterator.name, iterate.accumulator.variable.name));
            ExpressionNode bodyNode = bind(iterate.body);
            exitScope();
            return new IterateExpression(sourceNode, iterate.iterator.name, iterate.accumulator.variable.name, accumulatorInit, bodyNode);
        }

        if (node instanceof ASTIterator) {
            ASTIterator itAST = (ASTIterator) node;
            ExpressionNode sourceNode = bind(itAST.source);
            enterScope(itAST.iteratorName);
            ExpressionNode bodyNode = bind(itAST.body);
            exitScope();

            switch (itAST.operation.toLowerCase()) {
                case "select":
                    return new SelectExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "reject":
                    return new RejectExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "forall":
                    return new ForAllExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "exists":
                    return new ExistsExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "collect":
                    return new CollectExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "collectnested":
                    return new CollectionOpExpression(
                        new CollectExpression(sourceNode, itAST.iteratorName, bodyNode),
                        "flatten",
                        Collections.emptyList()
                    );
                case "any":
                    return new AnyExpression(sourceNode, itAST.iteratorName, bodyNode);
                case "one":
                    return new OneExpression(sourceNode, itAST.iteratorName, bodyNode);
                default:
                    throw new RuntimeException("Chưa hỗ trợ Iterator: " + itAST.operation);
            }
        }
        if (node instanceof ASTNot) {
            ASTNot notAST = (ASTNot) node;
            ExpressionNode innerExec = bind(notAST.expression);
            return new NotExpression(innerExec);
        }


        throw new RuntimeException("Không hỗ trợ chuyển đổi Node: " + node.getClass().getSimpleName());
    }

    private String typeNameArg(List<ASTExpression> args) {
        if (args.size() != 1) return null;
        ASTExpression arg = args.get(0);
        if (arg instanceof ASTTypeLiteral) {
            return ((ASTTypeLiteral) arg).typeName;
        }
        if (arg instanceof ASTVar) {
            return ((ASTVar) arg).name;
        }
        if (arg instanceof ASTStringLiteral) {
            return ((ASTStringLiteral) arg).value;
        }
        return null;
    }

}
