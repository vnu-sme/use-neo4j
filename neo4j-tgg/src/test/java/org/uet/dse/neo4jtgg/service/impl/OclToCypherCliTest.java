package org.uet.dse.neo4jtgg.service.impl;

import org.junit.jupiter.api.Test;
import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.ModelFactory;
import org.uet.dse.neo4jtgg.model.OclFileCompilationResult;
import org.uet.dse.neo4jtgg.model.OclRuleCompilationResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OclToCypherCliTest {

    @Test
    void compilesOclFileFromSystemProperties() throws IOException {
        String useFileProperty = System.getProperty("useFile");
        String oclFileProperty = System.getProperty("oclFile");

        assertNotNull(useFileProperty, "Missing -DuseFile=/path/to/model.use");
        assertNotNull(oclFileProperty, "Missing -DoclFile=/path/to/rules.ocl");

        Path useFile = Path.of(useFileProperty).toAbsolutePath().normalize();
        Path oclFile = Path.of(oclFileProperty).toAbsolutePath().normalize();

        StringWriter buffer = new StringWriter();
        MModel model = compileUseModel(useFile, buffer);
        assertNotNull(model, buffer.toString());

        String oclText = Files.readString(oclFile);
        DefaultOclToCypherCompiler compiler = new DefaultOclToCypherCompiler(model);
        OclFileCompilationResult result = compiler.compileFile(oclText);

        System.out.println("USE model: " + useFile);
        System.out.println("OCL file : " + oclFile);
        System.out.println();
        System.out.println(result.toDisplayText());

        assertFalse(result.getRuleResults().isEmpty(), "No OCL rules were found in file: " + oclFile);

        for (OclRuleCompilationResult rule : result.getRuleResults()) {
            assertFalse(!rule.isSupported(), () ->
                    "Unsupported rule `" + rule.getInvariantName() + "`: " + rule.getReason());
        }
    }

    private MModel compileUseModel(Path useFile, StringWriter buffer) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(useFile)) {
            return USECompiler.compileSpecification(input, useFile.toString(),
                    new PrintWriter(buffer, true), new ModelFactory());
        }
    }
}
