package org.uet.dse.neo4j.oclite.expr;

import org.uet.dse.neo4j.oclite.Neo4jRepository;
import org.uet.dse.neo4j.oclite.ast.*;


import org.uet.dse.neo4j.oclite.ast.*;

import java.util.ArrayList;
import java.util.List;

public class ExpressionBinder {
    private String modelName;
    private Neo4jRepository neo4jRepository;
    public ExpressionBinder(String modelName) {
        this.neo4jRepository = new Neo4jRepository(modelName);
        this.modelName = modelName;
    }

    public ExpressionNode bind(ASTNode node) {
//        if (node instanceof ASTLiteral) {
//            return new ConstantExpression(((ASTLiteral) node).value);
//        }

        //p1
        if (node instanceof ASTVar) {
            String varName = ((ASTVar) node).name;
            if (neo4jRepository.isVariableExist(varName)) {
                return new VariableExpression(varName);
            }
            throw new RuntimeException("Semantic Error: Đối tượng '" + varName + "' không tồn tại trong Neo4j.");
        }

        //(.)
        if (node instanceof ASTProperty) {
            ASTProperty prop = (ASTProperty) node;
            ExpressionNode sourceNode = bind(prop.source);

            if (neo4jRepository.isRelationship(sourceNode, prop.name)) {
                return new NavigationExpression(sourceNode, prop.name);
            } else {
                return new PropertyExpression(sourceNode, prop.name);
            }
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

            if (bin.op.equals(">") && (bin.right instanceof ASTStringLiteral)) {
                throw new RuntimeException("nah");
            }

            return new BinaryExpression(left, bin.op, right);
        }

        if (node instanceof ASTCollectionOp) {
            ASTCollectionOp colOp = (ASTCollectionOp) node;
            ExpressionNode sourceNode = bind(colOp.source);

            List<ExpressionNode> bindedArgs = new ArrayList<>();
            for (ASTExpression arg : colOp.args) {
                bindedArgs.add((ExpressionNode) bind(arg));
            }

            return new CollectionOperationExpression(sourceNode, colOp.opName, bindedArgs);
        }

        if (node instanceof ASTIntegerLiteral) {
            return new ConstantExpression(((ASTIntegerLiteral) node).value);
        }

        if (node instanceof ASTStringLiteral) {
            return new ConstantExpression(((ASTStringLiteral) node).value);
        }

        if (node instanceof ASTNullLiteral) {
            // Trả về một ConstantExpression chứa giá trị null thực sự của Java
            return new ConstantExpression(null);
        }

        throw new RuntimeException("Không hỗ trợ chuyển đổi Node: " + node.getClass().getSimpleName());
    }

}