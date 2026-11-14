package org.uet.dse.ocl2cypher.conformance;

import java.math.BigInteger;
import org.uet.dse.ocl2cypher.runtime.OclType;
import org.uet.dse.ocl2cypher.runtime.OclValue;
import org.uet.dse.ocl2cypher.source.model.SchemaModel;
import org.uet.dse.ocl2cypher.source.model.Snapshot;
import org.uet.dse.ocl2cypher.source.model.UmlAttribute;
import org.uet.dse.ocl2cypher.source.model.UmlClass;

/**
 * Property fixtures: for each differential case the snapshot and the expected
 * invariant name place produce the surface identity the source oracle produces.
 *
 * <p>The carrier distinguishes scalar bottom, element bottom, a defined empty
 * collection, and whole-collection bottom. Filter predicates use USE's
 * occurrence-local undefined-to-false rule independently of that carrier.
 */
public final class PropertyFixtures {

    private PropertyFixtures() {
    }

    public static SchemaModel personSchema() {
        return SchemaModel.builder("m")
                .clazz(UmlClass.of("Person"))
                .attribute(UmlAttribute.of("Person", "age", OclType.INTEGER))
                .build();
    }

    public static Snapshot mixedPersons() {
        return Snapshot.builder()
                .object("alice", "Person").attribute("alice", "age", intV(20))
                .object("bob", "Person").attribute("bob", "age", intV(15))
                .object("carol", "Person") // missing age → bottom → violation
                .build();
    }

    public static OclValue intV(long n) {
        return new OclValue.IntegerValue(BigInteger.valueOf(n));
    }
}
