package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MGeneralization;
import org.uet.dse.neo4j.mm.core.edge.Extends;

public class UseGeneralizationTranslator implements IModelTranslator<MGeneralization, Extends> {

    @Override
    public Extends translate(MGeneralization source) {
        return new Extends(
            source.child().name(),
            source.parent().name()
        );
    }
}