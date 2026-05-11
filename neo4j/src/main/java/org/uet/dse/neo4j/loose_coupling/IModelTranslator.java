package org.uet.dse.neo4j.loose_coupling;

public interface IModelTranslator<S, T> {
    T translate(S source);
}