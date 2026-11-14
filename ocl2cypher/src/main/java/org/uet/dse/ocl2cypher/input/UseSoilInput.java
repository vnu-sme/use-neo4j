package org.uet.dse.ocl2cypher.input;

import java.nio.file.Path;
import org.uet.dse.ocl2cypher.diagnostics.Result;
import org.uet.dse.ocl2cypher.input.use.UseSoilInputAdapter;

/** Public file-input boundary for a USE schema and a SOIL snapshot. */
public final class UseSoilInput {
    private UseSoilInput() {
    }

    public static Result<ModelInputBundle> load(Path usePath, Path soilPath) {
        return UseSoilInputAdapter.load(usePath, soilPath);
    }

    /** Load additional invariants from an optional .ocl file. */
    public static Result<ModelInputBundle> load(Path usePath, Path soilPath, Path oclPath) {
        return UseSoilInputAdapter.load(usePath, soilPath, oclPath);
    }
}
