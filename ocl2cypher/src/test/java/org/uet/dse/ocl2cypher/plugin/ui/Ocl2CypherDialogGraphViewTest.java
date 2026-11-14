package org.uet.dse.ocl2cypher.plugin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.graph.GraphModel;

class Ocl2CypherDialogGraphViewTest {

    @Test
    void startsWithSchemaAndRevealsOnlyDirectNeighbours() {
        GraphModel graph = new GraphModel("FamiliesValidation");
        graph.addNode(node("class/Family", GraphModel.Projection.SCHEMA,
                List.of("UmlClass")));
        graph.addNode(node("class/Member", GraphModel.Projection.SCHEMA,
                List.of("UmlClass")));
        graph.addNode(node("object/homer", GraphModel.Projection.INSTANCE,
                List.of("Object", "FamilyMember")));
        graph.addNode(node("slot/homer/name", GraphModel.Projection.INSTANCE,
                List.of("AttributeValue")));
        graph.addRelationship(relationship("association", GraphModel.Projection.SCHEMA,
                "class/Family", "class/Member"));
        graph.addRelationship(relationship("typing", GraphModel.Projection.TYPING,
                "object/homer", "class/Member"));
        graph.addRelationship(relationship("slot", GraphModel.Projection.INSTANCE,
                "object/homer", "slot/homer/name"));

        Set<String> visible = GraphCanvas.initialSchemaNodeKeys(graph);
        assertEquals(Set.of("class/Family", "class/Member"), visible);
        assertEquals(List.of("object/homer"),
                GraphCanvas.hiddenNeighbourKeys(graph, "class/Member", visible));

        visible = new LinkedHashSet<>(visible);
        visible.add("object/homer");
        assertEquals(List.of("slot/homer/name"),
                GraphCanvas.hiddenNeighbourKeys(graph, "object/homer", visible));
    }

    private static GraphModel.Node node(String key, GraphModel.Projection projection,
                                        List<String> labels) {
        return new GraphModel.Node(key, "FamiliesValidation", projection,
                "TEST", labels, Map.of("modelKey", "FamiliesValidation"));
    }

    private static GraphModel.Relationship relationship(
            String key, GraphModel.Projection projection, String source, String target) {
        return new GraphModel.Relationship(key, "FamiliesValidation", projection,
                "TEST", source, target, Map.of("modelKey", "FamiliesValidation"));
    }
}
