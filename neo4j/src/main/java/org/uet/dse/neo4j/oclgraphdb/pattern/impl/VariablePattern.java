package org.uet.dse.neo4j.oclgraphdb.pattern.impl;

import java.util.ArrayList;
import java.util.List;
import org.uet.dse.neo4j.oclgraphdb.pattern.OclPattern;
import org.uet.dse.neo4j.oclgraphdb.pattern.PatternPlanner;
import org.uet.dse.neo4j.oclgraphdb.plan.*;
import org.uet.dse.neo4j.oclite.ast.ASTNode;
import org.uet.dse.neo4j.oclite.ast.ASTVar;

public class VariablePattern implements OclPattern {
  @Override
  public String name() {
    return "variable";
  }

  @Override
  public boolean supports(ASTNode node) {
    return node instanceof ASTVar;
  }

  @Override
  public List<PlanCandidate> match(ASTNode node, CompileContext context, ExpectedKind expected, PatternPlanner planner) {
    String name = ((ASTVar) node).name;

    if (context.hasObjectBinding(name)) {
      List<String> prelude = new ArrayList<>();
      String idParam = context.addParam("objectId", context.objectBinding(name));
      String cls = context.nextName("cls");
      prelude.add("MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(" + cls + ")");
      prelude.add("MATCH (" + name + " {use_id: " + idParam + "})-[:ObjectInstanceOf]->(" + cls + ")");
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE, prelude, 1, 1), 980));
    }

    if (context.isLocalVariable(name)) {
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE), 950));
    }

    if (context.resolver().isClass(name)) {
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.TYPE), 900));
    }

    if (context.resolver().isObject(name)) {
      List<String> prelude = new ArrayList<>();
      String idParam = context.addParam("objectId", name);
      String cls = context.nextName("cls");
      prelude.add("MATCH (m:ManageModel {name: $modelName})-[:DefineMetamodels]->()<-[:InstanceOf]-(" + cls + ")");
      prelude.add("MATCH (" + name + " {use_id: " + idParam + "})-[:ObjectInstanceOf]->(" + cls + ")");
      return List.of(PlanCandidate.exact(name(), new CypherExpression(name, ExpressionKind.NODE, prelude, 1, 1), 850));
    }

    return List.of();
  }
}
