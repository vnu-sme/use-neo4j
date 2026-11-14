package org.uet.dse.ocl2cypher.graph;

import java.util.Map;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.UmlAssociation;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.model.UmlQualifier;
import org.uet.dse.ocl2cypher.graph.ValidRepChecker.Witness;
import static org.uet.dse.ocl2cypher.graph.ValidRepChecker.failure;

/** Repository/schema projection observed by Cypher. */
final class RepositorySchemaValidator {
    private RepositorySchemaValidator() { }

    /**
     * Check the complete repository/schema projection used by generated Cypher.
     * This is deliberately structural: Q's in-memory interpreter can consult
     * {@link SchemaModel}, whereas the target query follows {@code Extends},
     * {@code HasAttribute}, and declaration nodes in the materialized graph.
     * Omitting these checks would allow a graph to witness ValidRep while the
     * two evaluators observe different schemas.
     */
    static Result<Witness> checkRepositoryAndSchema(
            SchemaModel sm, GraphModel g) {
        MetamodelMapping mapping = MetamodelMapping.canonical();
        GraphModel.Node root = node(g, GraphKey.model(g.modelKey()));
        var rootBinding = mapping.node(MetamodelMapping.NodeKind.MODEL);
        if (root.projection() != rootBinding.projection()
                || !rootBinding.observationRole().equals(root.observationRole())
                || !root.labels().containsAll(rootBinding.labels())
                || !GraphModel.ENCODING_PROFILE.equals(
                        root.properties().get("encodingProfile"))
                || !GraphModel.ENCODING_VERSION.equals(
                        root.properties().get("encodingVersion"))) {
            return failure("VALIDREP_MODEL_ROOT", "model root differs from profile "
                    + GraphModel.ENCODING_VERSION);
        }
        long rootCount = g.nodes().stream()
                .filter(n -> rootBinding.observationRole().equals(n.observationRole()))
                .count();
        if (rootCount != 1) {
            return failure("VALIDREP_MODEL_ROOT", "graph must contain exactly one model root");
        }

        int generalizationCount = 0;
        var classBinding = mapping.node(MetamodelMapping.NodeKind.CLASS);
        for (var classifier : sm.classes()) {
            GraphModel.Node declaration = node(g,
                    GraphKey.clazz(g.modelKey(), classifier.key()));
            if (declaration.projection() != classBinding.projection()
                    || !classBinding.observationRole().equals(
                            declaration.observationRole())
                    || !declaration.labels().containsAll(classBinding.labels())
                    || !classifier.key().equals(
                            declaration.properties().get("classKey"))
                    || !classifier.qualifiedName().equals(
                            declaration.properties().get("qualifiedName"))
                    || !String.valueOf(classifier.isAbstract()).equals(
                            declaration.properties().get("isAbstract"))
                    || !String.valueOf(classifier.isAssociationClass()).equals(
                            declaration.properties().get("isAssociationClass"))) {
                return failure("VALIDREP_CLASS_DECLARATION",
                        "class declaration differs: " + classifier.key());
            }
            for (String superclass : classifier.directSuperclassKeys()) {
                generalizationCount++;
                GraphModel.Relationship edge = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.GENERALIZATION,
                                classifier.key(), superclass));
                if (!GraphModel.EXTENDS.equals(edge.physicalType())
                        || edge.projection() != GraphModel.Projection.SCHEMA
                        || !GraphKey.clazz(g.modelKey(), classifier.key())
                                .equals(edge.sourceKey())
                        || !GraphKey.clazz(g.modelKey(), superclass)
                                .equals(edge.targetKey())) {
                    return failure("VALIDREP_GENERALIZATION",
                            "generalization differs: " + classifier.key()
                                    + " -> " + superclass);
                }
            }
        }
        long actualClasses = g.nodes().stream()
                .filter(n -> classBinding.observationRole().equals(n.observationRole()))
                .count();
        if (actualClasses != sm.classes().size()) {
            return failure("VALIDREP_CLASS_DECLARATION",
                    "source and graph class counts differ");
        }
        long actualGeneralizations = g.relationships().stream()
                .filter(r -> GraphModel.EXTENDS.equals(r.physicalType())).count();
        if (actualGeneralizations != generalizationCount) {
            return failure("VALIDREP_GENERALIZATION",
                    "source and graph generalization counts differ");
        }

        var attributeBinding = mapping.node(MetamodelMapping.NodeKind.ATTRIBUTE);
        for (UmlAttribute attribute : sm.attributes()) {
            GraphModel.Node declaration = node(g,
                    GraphKey.attribute(g.modelKey(), attribute.key()));
            if (declaration.projection() != attributeBinding.projection()
                    || !attributeBinding.observationRole().equals(
                            declaration.observationRole())
                    || !declaration.labels().containsAll(attributeBinding.labels())
                    || !attribute.key().equals(
                            declaration.properties().get("attributeKey"))
                    || !attribute.ownerClassKey().equals(
                            declaration.properties().get("ownerClassKey"))
                    || !attribute.name().equals(declaration.properties().get("name"))
                    || !attribute.declaredType().toString().equals(
                            declaration.properties().get("declaredType"))) {
                return failure("VALIDREP_ATTRIBUTE_DECLARATION",
                        "attribute declaration differs: " + attribute.key());
            }
            GraphModel.Relationship ownership = relationship(g,
                    GraphKey.of(g.modelKey(), GraphKey.Kind.CLASS_ATTRIBUTE,
                            attribute.ownerClassKey(), attribute.key()));
            if (!GraphModel.HAS_ATTRIBUTE.equals(ownership.physicalType())
                    || ownership.projection() != GraphModel.Projection.SCHEMA
                    || !GraphKey.clazz(g.modelKey(), attribute.ownerClassKey())
                            .equals(ownership.sourceKey())
                    || !declaration.stableKey().equals(ownership.targetKey())) {
                return failure("VALIDREP_ATTRIBUTE_DECLARATION",
                        "attribute ownership differs: " + attribute.key());
            }
        }
        long actualAttributes = g.nodes().stream()
                .filter(n -> attributeBinding.observationRole().equals(n.observationRole()))
                .count();
        long actualOwnership = g.relationships().stream()
                .filter(r -> GraphModel.HAS_ATTRIBUTE.equals(r.physicalType())).count();
        if (actualAttributes != sm.attributes().size()
                || actualOwnership != sm.attributes().size()) {
            return failure("VALIDREP_ATTRIBUTE_DECLARATION",
                    "source and graph attribute declaration counts differ");
        }

        return checkSchemaAssociations(sm, g);
    }

    private static Result<Witness> checkSchemaAssociations(
            SchemaModel sm, GraphModel g) {
        if (g.nodes().stream().anyMatch(n ->
                "ASSOCIATION_DECLARATION".equals(n.observationRole())
                        || n.labels().contains("Association"))) {
            return failure("VALIDREP_ASSOCIATION_SCHEMA",
                    "ordinary association declarations must not be reified as nodes");
        }

        int expectedRelationships = 0;
        for (UmlAssociation association : sm.associations()) {
            boolean associationClass = sm.clazz(association.key()) != null
                    && sm.clazz(association.key()).isAssociationClass();
            if (associationClass) {
                expectedRelationships += 2;
                GraphModel.Relationship source = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.ASSOCIATION_END,
                                association.key(), "source"));
                GraphModel.Relationship target = relationship(g,
                        GraphKey.of(g.modelKey(), GraphKey.Kind.ASSOCIATION_END,
                                association.key(), "target"));
                String classKey = GraphKey.clazz(g.modelKey(), association.key());
                if (!GraphModel.ASSOCIATION_CLASS_SCHEMA_SOURCE.equals(source.physicalType())
                        || !GraphKey.clazz(g.modelKey(), association.sourceClassKey())
                                .equals(source.sourceKey())
                        || !classKey.equals(source.targetKey())
                        || !GraphModel.ASSOCIATION_CLASS_SCHEMA_TARGET.equals(
                                target.physicalType())
                        || !classKey.equals(target.sourceKey())
                        || !GraphKey.clazz(g.modelKey(), association.targetClassKey())
                                .equals(target.targetKey())
                        || !"source".equals(source.properties().get("endPosition"))
                        || !"target".equals(target.properties().get("endPosition"))
                        || !associationMetadataMatches(association, source, true)
                        || !associationMetadataMatches(association, target, true)) {
                    return failure("VALIDREP_ASSOCIATION_SCHEMA",
                            "association-class schema differs: " + association.key());
                }
            } else {
                expectedRelationships++;
                GraphModel.Relationship declaration = relationship(g,
                        GraphKey.association(g.modelKey(), association.key()));
                if (!MetamodelMapping.schemaRelationshipType(association.associationKind()).equals(declaration.physicalType())
                        || !GraphKey.clazz(g.modelKey(), association.sourceClassKey())
                                .equals(declaration.sourceKey())
                        || !GraphKey.clazz(g.modelKey(), association.targetClassKey())
                                .equals(declaration.targetKey())
                        || !associationMetadataMatches(association, declaration, false)) {
                    return failure("VALIDREP_ASSOCIATION_SCHEMA",
                            "binary association schema differs: " + association.key());
                }
            }
        }
        long actualRelationships = g.relationships().stream().filter(r ->
                GraphModel.ASSOCIATE_WITH.equals(r.physicalType())
                        || GraphModel.AGGREGATES.equals(r.physicalType())
                        || GraphModel.COMPOSE_OF.equals(r.physicalType())
                        || GraphModel.ASSOCIATION_CLASS_SCHEMA_SOURCE.equals(r.physicalType())
                        || GraphModel.ASSOCIATION_CLASS_SCHEMA_TARGET.equals(r.physicalType()))
                .count();
        if (actualRelationships != expectedRelationships) {
            return failure("VALIDREP_ASSOCIATION_SCHEMA",
                    "source and graph schema association counts differ");
        }
        return Result.success(new Witness(Map.of()));
    }

    static GraphModel.Relationship relationship(GraphModel graph, String stableKey) {
        return graph.relationships().stream()
                .filter(relationship -> stableKey.equals(relationship.stableKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "missing schema relationship " + stableKey));
    }

    static GraphModel.Node node(GraphModel graph, String stableKey) {
        return graph.nodes().stream()
                .filter(node -> stableKey.equals(node.stableKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "missing graph node " + stableKey));
    }

    private static boolean associationMetadataMatches(
            UmlAssociation association, GraphModel.Relationship relationship,
            boolean associationClass) {
        Map<String, String> properties = relationship.properties();
        if (!(association.key().equals(properties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_KEY))
                && association.name().equals(properties.get(
                        Uml2GraphEncodingContract.Property.ASSOCIATION_NAME))
                && association.sourceClassKey().equals(properties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_CLASS_KEY))
                && association.sourceRole().equals(properties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_ROLE))
                && String.valueOf(association.sourceLower()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_LOWER))
                && String.valueOf(association.sourceUpper()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.SOURCE_UPPER))
                && association.targetClassKey().equals(properties.get(
                        Uml2GraphEncodingContract.Property.TARGET_CLASS_KEY))
                && association.targetRole().equals(properties.get(
                        Uml2GraphEncodingContract.Property.TARGET_ROLE))
                && String.valueOf(association.targetLower()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.TARGET_LOWER))
                && String.valueOf(association.targetUpper()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.TARGET_UPPER))
                && String.valueOf(association.isOrdered()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.ORDERED))
                && String.valueOf(association.isUnique()).equals(properties.get(
                        Uml2GraphEncodingContract.Property.UNIQUE))
                && association.associationKind().name().equals(
                        properties.get(Uml2GraphEncodingContract.Property.ASSOCIATION_KIND))
                && association.qualifierEnd().name().equals(properties.get(
                        Uml2GraphEncodingContract.Property.QUALIFIER_END))
                && String.valueOf(association.sourceEndQualifiers().size())
                        .equals(properties.get(
                                Uml2GraphEncodingContract.Property.SOURCE_QUALIFIER_COUNT))
                && String.valueOf(association.targetEndQualifiers().size())
                        .equals(properties.get(
                                Uml2GraphEncodingContract.Property.TARGET_QUALIFIER_COUNT))
                && String.valueOf(associationClass).equals(
                        properties.get(Uml2GraphEncodingContract.Property.IS_ASSOCIATION_CLASS)))) {
            return false;
        }
        for (int index = 0; index < association.sourceEndQualifiers().size(); index++) {
            UmlQualifier qualifier = association.sourceEndQualifiers().get(index);
            if (!qualifier.name().equals(properties.get(
                    "sourceQualifier::" + index + "::name"))
                    || !qualifier.declaredType().toString().equals(properties.get(
                            "sourceQualifier::" + index + "::type"))) {
                return false;
            }
        }
        for (int index = 0; index < association.targetEndQualifiers().size(); index++) {
            UmlQualifier qualifier = association.targetEndQualifiers().get(index);
            if (!qualifier.name().equals(properties.get(
                    "targetQualifier::" + index + "::name"))
                    || !qualifier.declaredType().toString().equals(properties.get(
                            "targetQualifier::" + index + "::type"))) {
                return false;
            }
        }
        return true;
    }

}
