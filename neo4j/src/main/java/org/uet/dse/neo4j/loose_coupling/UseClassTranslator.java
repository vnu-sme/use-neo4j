package org.uet.dse.neo4j.loose_coupling;

import org.tzi.use.uml.mm.MAssociationClass;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MOperation;
import org.uet.dse.neo4j.mm.core.node.*;

import java.util.List;
import java.util.stream.Collectors;

public class UseClassTranslator implements IModelTranslator<MClass, AbstractClassNode> {

    @Override
    public AbstractClassNode translate(MClass source) {
        AbstractClassNode node;

        if (source instanceof MAssociationClass) {
            node = new NodeAssociationClass(source.name());
        } else if (source.isAbstract()) {
            node = new NodeAbstractClass(source.name());
        } else {
            node = new NodeConcreteClass(source.name());
        }

        node.setHasParent(!source.parents().isEmpty());
        node.setHasChildren(!source.children().isEmpty());
        node.setAbstract(source.isAbstract());

        return node;
    }
}
