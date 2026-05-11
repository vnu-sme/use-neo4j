package org.uet.dse.neo4j.manager;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.uet.dse.neo4j.TGGLexer;
import org.uet.dse.neo4j.TGGParser;
import org.uet.dse.neo4j.model_trafo.parser.TGGBuildVisitor;
import org.uet.dse.neo4j.model_trafo.parser.TGGTransformation;
import java.io.File;
import java.nio.file.Files;

public class TggTransformationManager {
    private static TggTransformationManager instance;
    private TGGTransformation currentTransformation;
    private String tggFilePath;
    private String sourceModelName = "Families";
    private String targetModelName = "Persons";

    private TggTransformationManager() {}

    public static TggTransformationManager getInstance() {
        if (instance == null) instance = new TggTransformationManager();
        return instance;
    }

    public void loadTggFile(File file) throws Exception {
        this.tggFilePath = file.getAbsolutePath();
        String content = new String(Files.readAllBytes(file.toPath()));
        parseTggContent(content);
    }

    public void parseTggContent(String content) throws Exception {
        TGGLexer lexer = new TGGLexer(CharStreams.fromString(content));
        TGGParser parser = new TGGParser(new CommonTokenStream(lexer));
        TGGBuildVisitor builder = new TGGBuildVisitor();
        this.currentTransformation = (TGGTransformation) builder.visit(parser.tggFile());
    }

    public TGGTransformation getTransformation() { return currentTransformation; }
    public String getSourceModelName() { return sourceModelName; }
    public void setSourceModelName(String name) { this.sourceModelName = name; }
    public String getTargetModelName() { return targetModelName; }
    public void setTargetModelName(String name) { this.targetModelName = name; }
    public boolean isLoaded() { return currentTransformation != null; }
}