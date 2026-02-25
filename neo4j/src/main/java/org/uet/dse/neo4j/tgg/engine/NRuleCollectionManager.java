package org.uet.dse.neo4j.tgg.engine;

import org.neo4j.driver.Session;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAssociationImpl;
import org.tzi.use.uml.sys.MLinkEnd;
import org.tzi.use.uml.sys.MLinkImpl;
import org.tzi.use.uml.sys.MObject;
import org.tzi.use.uml.sys.MObjectImpl;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import org.uet.dse.rtlplus.Main;
import org.uet.dse.rtlplus.mm.MPattern;
import org.uet.dse.rtlplus.mm.MRule;
import org.uet.dse.rtlplus.mm.MRuleCollection;
import org.uet.dse.rtlplus.mm.MTggRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NRuleCollectionManager {

  public void iterateThroughMRuleCollection() {
    MRuleCollection mRuleCollection = Main.getTggRuleCollection();
    MRuleCollection.TransformationType direction = mRuleCollection.getType();

    switch (direction) {
      case FORWARD -> forwardDirection(mRuleCollection);
      case BACKWARD -> fallBack();
      case COEVOLUTION -> fallBack();
    }

  }

  public void forwardDirection(MRuleCollection mRuleCollection) {
    List<MTggRule> rules = mRuleCollection.getRuleList();

    for (MTggRule rule : rules) {
      System.out.println("Resolving rule: {}" + rule.getName());

      String query = getForwardQueryFindMatching(rule);

      try(Session session = Neo4jDriverManager.getInstance().openSession()) {

      }



    }

  }

  public String getForwardQueryFindMatching(MTggRule tggRule) {
    MRule src = tggRule.getSrcRule();
    MPattern src_lhs = src.getLhs(); //need
    MPattern src_rhs = src.getRhs(); //need

    MRule cor = tggRule.getCorrRule();
    MPattern cor_lhs = cor.getLhs(); //need
    MPattern cor_rhs = cor.getRhs();

    MRule tar = tggRule.getTrgRule();
    MPattern tar_lhs = tar.getLhs(); //need
    MPattern tar_rhs = tar.getRhs();
    List<MPattern> required = new ArrayList<>();
    required.add(src_lhs);
    required.add(src_rhs);
    required.add(cor_lhs);
    required.add(tar_lhs);
    List<MObjectImpl> singleClass = new ArrayList<>();
    List<LinkedClass> linkedClass = new ArrayList<>();

    resolveClasses(required, singleClass, linkedClass);

    String matchQuery = computeFindMatchQuery(singleClass, linkedClass);

    System.out.println(matchQuery);
    return matchQuery;

  }

  public String computeFindMatchQuery(List<MObjectImpl> singleClass,
      List<LinkedClass> linkedClass) {

    StringBuilder query = new StringBuilder("MATCH\n");

    List<String> patterns = new ArrayList<>();
    int relIndex = 1;
    int singleIndex = 1;

    for (LinkedClass lnk : linkedClass) {

      String label1 = lnk.c1.cls().name();
      String label2 = lnk.c2.cls().name();

      String relType;
      int kind = lnk.link.association().aggregationKind();
      boolean directed = false;
      switch (kind) {
        case 0 -> { // Association
          relType = "LinkAssociateWith";
          directed = false;
        }
        case 1 -> { // Aggregation
          relType = "LinkAggregateWith";
          directed = true;
        }
        case 2 -> { // Composition
          relType = "LinkComposeOf";
          directed = true;
        }
        default -> throw new IllegalArgumentException("Unknown link kind: " + kind);
      }

      String alias1 = "n" + relIndex + "a";
      String alias2 = "n" + relIndex + "b";
      String relAlias = "r" + relIndex;

      String pattern;

      if (directed) {
        pattern = String.format("  (%s:%s)-[%s:%s]->(%s:%s)",
            alias1, label1,
            relAlias, relType,
            alias2, label2);
      } else {
        pattern = String.format("  (%s:%s)-[%s:%s]-(%s:%s)",
            alias1, label1,
            relAlias, relType,
            alias2, label2);
      }

      patterns.add(pattern);
      relIndex++;
    }

    for (MObjectImpl item : singleClass) {

      String label = item.name();
      String alias = "dump" + singleIndex;

      String pattern = String.format(
          "  ()<-[:ObjectInstanceOf]-(%s:%s)",
          alias, label
      );

      patterns.add(pattern);
      singleIndex++;
    }

    query.append(String.join(",\n", patterns));

    query.append("\nRETURN *\nLIMIT 250;");

    return query.toString();
  }


  public void resolveClasses(List<MPattern> required, List<MObjectImpl> singleClass, List<LinkedClass> linkedClassssss) {
    List<MObjectImpl> allObject =
        required.stream()
            .flatMap(p -> p.getObjectList().stream())
            .map(o -> (MObjectImpl) o)
            .collect(Collectors.toList());

    List<MLinkImpl> allLinks =
        required.stream()
            .flatMap(p -> p.getLinkList().stream())
            .map(o -> (MLinkImpl) o)
            .collect(Collectors.toList());

    for (MLinkImpl link : allLinks) {
      MAssociationImpl association = (MAssociationImpl) link.association();
      Set<MLinkEnd> linkEnds = link.linkEnds();
      List<MObjectImpl> linkedObj = link.linkedObjects().stream().map(o -> (MObjectImpl) o).toList();
      LinkedClass lcls = new LinkedClass();
      lcls.link = link;
      lcls.c1 = linkedObj.get(0);
      lcls.c2 = linkedObj.get(1);

      linkedClassssss.add(lcls);
      for (MObjectImpl obj : linkedObj) {
        String obj_name = obj.name();
        allObject.removeIf(o -> o.name().equals(obj_name));
      }
    }
    singleClass.addAll(singleClass);
  }

  class LinkedClass {
    public MObjectImpl c1;
    public MObjectImpl c2;
    public MLinkImpl link;
  }

  public void fallBack() {
    System.out.println("ouch");

  }
}
