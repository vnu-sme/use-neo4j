package org.uet.dse.neo4j.model_trafo;

import java.util.ArrayList;
import java.util.List;

public class TGGBlock {
    public List<TGGElement> context = new ArrayList<>();   // inside (...)
    public List<TGGElement> produce = new ArrayList<>();   // inside{...}
}
