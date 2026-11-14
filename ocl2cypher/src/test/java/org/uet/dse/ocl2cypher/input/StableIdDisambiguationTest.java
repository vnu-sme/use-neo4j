package org.uet.dse.ocl2cypher.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.uet.dse.ocl2cypher.graph.GraphBuilder;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.Snapshot;

class StableIdDisambiguationTest {

    @Test
    void duplicateRawNameGetsCounterSuffix() throws Exception {
        assertEquals("__", StableIdDisambiguator.GENERATED_SUFFIX_SEPARATOR);
        var loaded = UseSoilInput.load(
                example("stable-id-duplicate", "dup.shop.use"),
                example("stable-id-duplicate", "dup.shop.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        Snapshot snap = loaded.value().snapshot();
        assertEquals(3, snap.objects().size());

        Set<String> ids = snap.objects().stream()
                .map(Snapshot.ObjectDef::stableId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("item1"), "first occurrence keeps raw name");
        assertTrue(ids.contains("item1__1"), "second occurrence gets __1 suffix");
        assertTrue(ids.contains("catalog1"));

        var graph = GraphBuilder.build(loaded.value().schema(), snap);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());

        // item1__1 has attribute "Magazine" — prove it was set, not the first item1
        assertEquals("Magazine",
                ((OclValue.StringValue) snap.attributeSlot("item1__1", "name")
                        .orElseThrow()).value());
    }

    @Test
    void suffixCollisionSkipsAlreadyTakenLiteral() throws Exception {
        var loaded = UseSoilInput.load(
                example("stable-id-collision", "collide.shop.use"),
                example("stable-id-collision", "collide.shop.soil"));

        assertTrue(loaded.isSuccess(), () -> loaded.diagnostics().toString());
        Snapshot snap = loaded.value().snapshot();
        assertEquals(4, snap.objects().size());

        Set<String> ids = snap.objects().stream()
                .map(Snapshot.ObjectDef::stableId)
                .collect(Collectors.toSet());
        // item1__1 stays the pre-existing literal; second 'item1' skips to __2
        assertTrue(ids.contains("item1"));
        assertTrue(ids.contains("item1__1"), "literal 'item1__1' preserved");
        assertTrue(ids.contains("item1__2"), "second duplicate skips over literal __1");
        assertTrue(ids.contains("catalog1"));

        var graph = GraphBuilder.build(loaded.value().schema(), snap);
        assertTrue(graph.isSuccess(), () -> graph.diagnostics().toString());

        // Pre-existing item1__1 still has its attribute
        assertEquals("Pre-existing literal",
                ((OclValue.StringValue) snap.attributeSlot("item1__1", "name")
                        .orElseThrow()).value());
        // Second 'item1' (item1__2) got 'Magazine'
        assertEquals("Magazine",
                ((OclValue.StringValue) snap.attributeSlot("item1__2", "name")
                        .orElseThrow()).value());
    }

    private static Path example(String directory, String fileName) {
        return Path.of("..", "example", directory, fileName)
                .toAbsolutePath().normalize();
    }
}
