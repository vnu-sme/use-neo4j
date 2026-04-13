grammar TGG;

@header {
    package org.uet.dse.neo4j;
}
// initial rule
tggFile : transformationHeader directionHeader ruleSpec+ EOF;

transformationHeader : 'transformation' ID;
directionHeader : 'direction' ID;

ruleSpec : 'rule' ID
           sourceBlock
           targetBlock
           corrBlock
           'end';

// component block
sourceBlock : 'checkSource' '(' listElements? ')' '{' listElements? '}';
targetBlock : 'checkTarget' '(' listElements? ')' '{' listElements? '}';
corrBlock   : 'checkCorr'   '(' listElements? ')' '{' listElements? '}';

// element list
listElements : element (element)*;

element : nodeDecl
        | edgeDecl
        | correspondenceDecl
        | constraint
        | attributeCode
        | correlationLogic
        ;

// Node declearetion: fr: FamilyRegister hoặc fm*: Family
nodeDecl : ID ('*')? ':' ID;

// Association declaration: (fr, fm): FamilyRegistration
edgeDecl : '(' ID ',' ID ')' ':' ID;

// Correspondence declaration: (fr, pr) as (fr, pr) in fr2pr:FR2PR
correspondenceDecl : '(' idWithStar ',' idWithStar ')' 'as' '(' ID ',' ID ')' 'in' ID ':' ID;

idWithStar : ID ('*')?;

//ocl test
constraint : (ID ':')? '[' ( ~( ']' ) )* ']';

// `self.mp.name := ...` test
attributeCode : '`' ( ~( '`' ) )* '`';

correlationLogic : ID ':' '[' ( ~( ']' ) )* ']';

// Lexer
ID : [a-zA-Z_][a-zA-Z0-9_]*;
WS : [ \t\r\n]+ -> skip;
COMMENT : '/*' .*? '*/' -> skip;
LINE_COMMENT : '//' ~[\r\n]* -> skip;