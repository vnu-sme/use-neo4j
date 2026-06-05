grammar OCL;

@header {
    package org.uet.dse.neo4j;
}

oclFile : (declaration | expression)* EOF ;

declaration : 'context' contextVariable? className=Identifier 'inv' invName=Identifier? ':' expression ;

contextVariable : Identifier ':' ;

expression
    : 'let' letBinding (',' letBinding)* 'in' expression            #LetExp
    | 'if' condition=expression 'then' thenExpression=expression 'else' elseExpression=expression 'endif' #IfExp
    | op=('not' | '+' | '-') expression                             #UnaryExp
    | primary                                                       #PrimaryExp
    | left=expression op=('*' | '/' | 'div') right=expression       #MultiplicativeExp
    | left=expression op=('+' | '-') right=expression                #AdditiveExp
    | left=expression op=('=' | '<>' | '<' | '>' | '>=' | '<=') right=expression #ComparisonExp
    | left=expression op=('and' | 'or' | 'xor') right=expression    #LogicalExp
    | left=expression op='implies' right=expression                 #LogicalImpliesExp
    ;

primary
    : literal                                      #LiteralExpr
    | collectionLiteral                            #CollectionLiteralExpr
    | Identifier '::' Identifier '(' argList? ')'  #StaticMethodCallExpr
    | Identifier                                   #IdExpr
    | primary '.' Identifier '(' argList? ')'     #MethodCallExpr
    | primary '.' Identifier '[' expression ']'    #QualifiedNavigationExpr
    | primary '.' Identifier                       #NavigationExpr
    | primary '->' 'iterate' '(' iterateArgs ')'   #IterateExpr
    | primary '->' Identifier '(' queryArgs ')'    #QueryExpr
    | primary '->' Identifier '(' argList? ')'     #CollectionOpExpr
    | primary '->' Identifier                      #CollectionPropertyExpr
    | '(' expression ')'                           #ParenExpr
    ;

argList : expression (',' expression)* ;

queryArgs : (varDeclList '|')? expression ;

iterateArgs : varDecl ';' accumulator=letBinding '|' expression ;

varDeclList : varDecl (',' varDecl)* ;

varDecl : Identifier (':' typeRef)? ;

letBinding : Identifier (':' typeRef)? '=' expression ;

typeRef : Identifier ('::' Identifier)* ('(' typeRef (',' typeRef)* ')')? ;

literal
    : Number
    | StringLiteral
    | 'true'
    | 'false'
    | 'self'
    | 'null'
    | 'Undefined'
    | 'invalid'
    | 'oclUndefined' '(' typeRef? ')'
    | '#' Identifier
    | Identifier '::' Identifier
    ;

collectionLiteral
    : collectionKind '{' (collectionItem (',' collectionItem)*)? '}'
    ;

collectionKind
    : 'Set'
    | 'Sequence'
    | 'Bag'
    | 'OrderedSet'
    ;

collectionItem : expression ('..' expression)? ;

Identifier : [a-zA-Z_][a-zA-Z0-9_]* ;
Number : [0-9]+ ('.' [0-9]+)? ;
StringLiteral : '\'' (~['])* '\'' ;
WS : [ \t\r\n]+ -> skip ;
