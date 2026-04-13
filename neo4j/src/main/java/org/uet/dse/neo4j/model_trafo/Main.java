package org.uet.dse.neo4j.model_trafo;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.uet.dse.neo4j.TGGLexer;
import org.uet.dse.neo4j.TGGParser;

public class Main {
    public static void main(String[] args) {
        String inputData = """
        transformation Families2Persons
        direction forward
        
        rule Register2Register
        checkSource(){
            fr: FamilyRegister
        }checkTarget(){
            pr: PersonRegister
        }checkCorr(){
            (fr, pr) as (fr, pr) in fr2pr:FR2PR
        }
        end
        
        rule Father2Male
        checkSource(
            fr: FamilyRegister
            fm: Family
            (fr, fm): FamilyRegistration
        ){
            father: FamilyMember
            (fm, father): Father
        }checkTarget(
            pr: PersonRegister
        ){
            mp: Male
            (pr, mp): PersonRegistration
        }checkCorr(
            (fr, pr) as (fr, pr) in fr2pr:FR2PR
        ){
            (father, mp) as (f, mp) in f2mp:F2MP
            (fm*, mp) as (fm, mp) in fm2mp:FM2MP
        
        }
        end
        """;

        try {
            TGGLexer lexer = new TGGLexer(CharStreams.fromString(inputData));
            CommonTokenStream tokens = new CommonTokenStream(lexer);

            TGGParser parser = new TGGParser(tokens);

            ParseTree tree = parser.tggFile();

            TGGBuildVisitor builder = new TGGBuildVisitor();
            TGGTransformation transformation = (TGGTransformation) builder.visit(tree);

            System.out.println("--- OUTPUT PARSE TGG ---");
            System.out.println(transformation);
            for (TGGRule rule : transformation.rules) {
                System.out.println("Rule Name: " + rule.name);
                System.out.println("  Source Context elements: " + rule.source.context.size());
                System.out.println("  Source Produce elements: " + rule.source.produce.size());
                System.out.println("  Target Produce elements: " + rule.target.produce.size());
                System.out.println("-------------------------");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}