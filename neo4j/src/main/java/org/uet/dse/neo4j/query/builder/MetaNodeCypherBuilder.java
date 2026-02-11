package org.uet.dse.neo4j.query.builder;



import org.uet.dse.neo4j.query.model.MetaNodeDescriptor;

import java.util.Collection;
import java.util.stream.Collectors;

public class MetaNodeCypherBuilder {

    public static String buildMerge(Collection<MetaNodeDescriptor> nodes) {
        return nodes.stream()
                .map(n -> String.format(
                        "MERGE (:MetaNode {name: '%s', label: '%s'})",
                        n.name(), n.label()
                ))
                .collect(Collectors.joining(" "));
    }
}
