# OCL GraphDB Coverage Test Cases

Scope: invariant and query expressions only. Operation contracts (`pre`, `post`, `body`, `def`, `@pre`, `result`, `oclIsNew`) are intentionally out of scope for this module version.

Use this file as a coverage matrix for parser, AST, multipass pattern detection, pattern-tree rewrite, Cypher generation, and Java fallback boundaries.

Recommended result fields for an automated test runner:

- `parse_ok`
- `analysis_depth`
- `matched_pattern_kinds`
- `selected_pattern_kind`
- `uses_java_fallback`
- `generated_cypher`
- `params`
- `result_matches_expected`

## P0 Core Scalars

| ID | OCL | Covers | Expected |
|---|---|---|---|
| P0-001 | `context Customer inv ageLegal: self.age >= 18` | explicit self attribute, compare | `ATTRIBUTE_COMPARE`, no Java fallback |
| P0-002 | `context Customer inv ageLegalImplicitSelf: age >= 18` | implicit self property | `IMPLICIT_SELF_PROPERTY_ACCESS` + compare |
| P0-003 | `context Customer inv booleanAttribute: self.active` | boolean attribute as predicate | `BOOLEAN_ATTRIBUTE_ACCESS` |
| P0-004 | `context Customer inv booleanImplicitSelf: active` | implicit boolean self | `IMPLICIT_SELF_PROPERTY_ACCESS`, boolean |
| P0-005 | `context Customer inv titleRule: self.isMale implies self.title = 'Mr.'` | implies, boolean attr, string compare | logical composition |
| P0-006 | `context Order inv stateRange: (self.state = 'pending') or (self.state = 'invoiced')` | parenthesis, or, string literals | logical composition |
| P0-007 | `context Order inv xorSmoke: (self.state = 'pending') xor (self.state = 'invoiced')` | xor parse/compile | logical composition |
| P0-008 | `context Product inv stockPositive: not self.stock < 0` | unary not, compare | negation/partial recursive |
| P0-009 | `context Employee inv arithmetic: (self.salary / 2) + 100 > 500` | arithmetic `/`, `+` | partial recursive |
| P0-010 | `context Employee inv divArithmetic: self.age div 2 >= 9` | `div` | partial recursive |
| P0-011 | `context Employee inv unaryMinus: -self.age < 0` | unary minus | unary/partial recursive |
| P0-012 | `context Employee inv methodAsProperty: self.age() >= 18` | zero-arg method as property | method/property fallback to attribute |

## P0 Navigation And Property Resolution

| ID | OCL | Covers | Expected |
|---|---|---|---|
| NAV-001 | `context Vehicle inv ownerAge: self.owner.age >= 18` | navigation then attribute | navigation + attribute compare |
| NAV-002 | `context Flight inv originName: self.origin.name = 'Amsterdam'` | navigation compare | navigation pushdown |
| NAV-003 | `context Mortgage inv ownerBorrower: self.security.owner = self.borrower` | node equality | navigation compare |
| NAV-004 | `context Employee inv optionalSupervisor: self.supervisor->notEmpty() implies self.age > self.supervisor.age` | optional navigation empty check | `OPTIONAL_NAVIGATION_EMPTY_CHECK` |
| NAV-005 | `context Membership inv cardColor: self.card.color = self.currentLevel.name` | multiple navigation attrs | partial recursive/compare |
| NAV-006 | `context CustomerCard inv ownerPrograms: self.owner.programs->size() >= 1` | navigation size compare | `NAVIGATION_SIZE_COMPARE` |
| NAV-007 | `context Membership inv noAccount: self.account->isEmpty()` | optional navigation isEmpty | `OPTIONAL_NAVIGATION_EMPTY_CHECK` |
| NAV-008 | `context LoyaltyProgram inv participantLimit: self.participants->size() < 10000` | collection navigation size | `NAVIGATION_SIZE_COMPARE` |

## P0 Query And Filter Terminals

| ID | OCL | Covers | Expected |
|---|---|---|---|
| QRY-001 | `context Company inv hasOlderEmployee: self.employees->select(e | e.age > 50)->notEmpty()` | select + notEmpty | `FILTERED_EXISTENCE_COMPARE` |
| QRY-002 | `context Company inv noMinorEmployee: self.employees->reject(e | e.age >= 18)->isEmpty()` | reject + isEmpty | filtered existence |
| QRY-003 | `context Project inv maxShortWork: self.worksOn->select(w | w.hours < 10)->size() <= 2` | filtered size compare | `FILTERED_SIZE_COMPARE` |
| QRY-004 | `context CustomerCard inv highPointTxn: self.transactions->select(t | t.point >= 100)->size() >= 1` | size existence optimization | `FILTERED_EXISTENCE_COMPARE` |
| QRY-005 | `context Company inv implicitIteratorSelect: self.employees->select(age > 50)->notEmpty()` | implicit iterator property | `IMPLICIT_ITERATOR_PROPERTY_ACCESS` |
| QRY-006 | `context Company inv implicitIteratorExists: self.employees->exists(firstName = 'Jack')` | implicit iterator in exists | quantifier + implicit property |
| QRY-007 | `context Company inv employeeAgeLimit: self.employees->forAll(e | e.age <= 65)` | forAll optimized | `QUANTIFIER` via `NOT EXISTS` |
| QRY-008 | `context Company inv employeeAgeLimitImplicit: self.employees->forAll(age <= 65)` | implicit iterator in forAll | quantifier + implicit |
| QRY-009 | `context Car inv redCarExists: Car.allInstances()->exists(c | c.colour = #red)` | allInstances exists, enum literal | `ALL_INSTANCES_SOURCE` + quantifier |
| QRY-010 | `context Person inv atMost100Persons: Person.allInstances()->size() <= 100` | allInstances size compare | `ALL_INSTANCES_SIZE_COMPARE` |
| QRY-011 | `context Person inv staticAllInstances: Person::allInstances()->size() <= 100` | static allInstances syntax | `STATIC_ALL_INSTANCES_SCOPE` / allInstances source |
| QRY-012 | `context Person inv propertyAllInstances: Person.allInstances->forAll(p | p.age >= 0)` | property allInstances | allInstances source |

## P0 Partial Recursive Composition

| ID | OCL | Covers | Expected |
|---|---|---|---|
| PRC-001 | `context Company inv nestedPartial: self.employees->select(e | e.age > 40)->size() > 3 and self.active` | optimized subtree + generic logical composition | one Cypher query, no Java fallback |
| PRC-002 | `context Employee inv nestedImplication: self.subordinates->isEmpty() implies self.worksOn->forAll(w | w.hours >= 10)` | empty check + quantifier + implies | partial recursive composition |
| PRC-003 | `context Customer inv complexBoolean: (self.age >= 18 and self.active) or self.vip` | boolean attrs + logical nesting | logical composition |
| PRC-004 | `context Product inv nullAware: self.stock <> Undefined` | undefined literal | no parser failure |
| PRC-005 | `context Product inv oclUndefined: self.stock <> oclUndefined(Integer)` | oclUndefined literal | no parser failure |

## P1 Multi-Variable Quantifiers

| ID | OCL | Covers | Expected |
|---|---|---|---|
| MVQ-001 | `context Person inv uniqueNameByForAll: Person.allInstances()->forAll(p1, p2 | p1 <> p2 implies p1.name <> p2.name)` | 2-var forAll | `MULTI_VAR_QUANTIFIER_OPTIMIZED` |
| MVQ-002 | `context Employee inv uniqueSSN: Employee.allInstances()->forAll(e1: Employee, e2: Employee | e1 <> e2 implies e1.SSN <> e2.SSN)` | typed multi-var forAll | optimized multi-var |
| MVQ-003 | `context Order inv uniqueId: Order.allInstances()->forAll(o1, o2 | o1 <> o2 implies o1.id <> o2.id)` | allInstances key constraint | optimized multi-var |
| MVQ-004 | `context Train inv sameWagonCount: Train.allInstances()->forAll(x: Train, y: Train | x.wagon->size() = y.wagon->size())` | multi-var with navigation size compare in body | optimized multi-var + nested size |
| MVQ-005 | `context Employee inv dependentPartialKey: self.dependents->forAll(d1, d2 | d1 <> d2 implies (d1.name <> d2.name or d1.relationship <> d2.relationship))` | multi-var on navigation source | optimized multi-var |
| MVQ-006 | `context Project inv oneShortWork: self.worksOn->one(w1, w2 | w1 <> w2 and w1.hours < 10 and w2.hours < 10)` | multi-var one | optimized count |

## P1 isUnique

| ID | OCL | Covers | Expected |
|---|---|---|---|
| UNI-001 | `context Product inv idUnique: Product::allInstances()->isUnique(p | p.id)` | isUnique group-by | `IS_UNIQUE_GROUP_BY` |
| UNI-002 | `context Customer inv uniqueId: Customer.allInstances()->isUnique(c | c.id)` | class allInstances isUnique | group-by |
| UNI-003 | `context LoyaltyProgram inv uniqueAccountNumber: self.Membership.account->isUnique(acc | acc.numbers)` | navigation source isUnique | group-by if source resolved |
| UNI-004 | `context ISPCustomer inv uniqueCustomerId: ISPCustomer.allInstances()->isUnique(c | c.id)` | domain-specific class name smoke | group-by |
| UNI-005 | `context Person inv implicitIsUnique: Person.allInstances()->isUnique(name)` | implicit iterator body | group-by + implicit property |

## P1 Aggregates

| ID | OCL | Covers | Expected |
|---|---|---|---|
| AGG-001 | `context Employee inv totalHours: self.worksOn->collect(w | w.hours)->sum() >= 30` | collect + sum | `AGGREGATE_FUNCTION` with collect body |
| AGG-002 | `context Employee inv implicitTotalHours: self.worksOn->collect(hours)->sum() <= 50` | implicit collect body | aggregate + implicit property |
| AGG-003 | `context Person inv mortgageLoad: self.mortgages.monthlyPayment->sum() <= self.salary * 0.30` | chained navigation + sum | aggregate or fallback collection sum |
| AGG-004 | `context Department inv avgSalary: self.worksFor.salary->avg() > 1000` | avg | aggregate/fallback `avg` |
| AGG-005 | `context Department inv minSalary: self.worksFor.salary->min() >= 0` | min | aggregate/fallback `min` |
| AGG-006 | `context Department inv maxSalary: self.worksFor.salary->max() <= 100000` | max | aggregate/fallback `max` |
| AGG-007 | `context Account inv transactionSum: self.transactions.points->sum() = self.points` | direct collection attr sum | aggregate/fallback |

## P1 asSet And Distinct Count

| ID | OCL | Covers | Expected |
|---|---|---|---|
| SET-001 | `context ProgramPartner inv numberOfCustomers: self.programs.participants->asSet()->size() = self.numberOfCustomers` | asSet size distinct count | `AS_SET_SIZE_DISTINCT_COUNT` |
| SET-002 | `context LoyaltyAccount inv oneOwner: self.transactions.cards.owner->asSet()->size() = 1` | chained navigation distinct count | distinct count |
| SET-003 | `context Company inv distinctEmployeeNames: self.employees.name->asSet()->size() = self.employees->size()` | attr collection distinct count | distinct count/fallback |
| SET-004 | `context Provider inv offeredServices: self.customer->collect(c: Customer | c.service)->asSet()->size() <= self.services->size()` | collect + asSet size | distinct count or fallback |

## P1 includesAll / contains

| ID | OCL | Covers | Expected |
|---|---|---|---|
| INC-001 | `context Department inv managerWorksFor: self.worksFor->includes(self.manages.employee)` | includes node | membership Cypher |
| INC-002 | `context Department inv managerWorksForContains: self.worksFor->contains(self.manages.employee)` | contains alias | same as includes |
| INC-003 | `context Employee inv projectDepartment: self.worksFor.controls->includesAll(self.worksOn.project)` | includesAll anti-join | `INCLUDES_ALL_ANTI_JOIN` |
| INC-004 | `context ServiceLevel inv availableServices: self.program.partners->includesAll(self.availableServices.partner)` | includesAll navigation target | anti-join |
| INC-005 | `context Provider inv onlyOfferedService: self.services->includesAll(self.customer->collect(c: Customer | c.service)->asSet())` | includesAll with collect/asSet arg | anti-join or fallback arg collection |
| INC-006 | `context Employee inv notAllProjects: self.worksFor.controls->excludesAll(self.worksOn.project)` | excludesAll anti-join | anti-join negated membership |

## P1 Date And Time

Assumption: date attributes are stored as Neo4j date/datetime or ISO-like strings that `date()` / `datetime()` can parse.

| ID | OCL | Covers | Expected |
|---|---|---|---|
| DAT-001 | `context Tournament inv matchTimeFrame: self.matches->forAll(m: Match | m.startDate.after(self.startDate) and m.endDate.before(self.endDate))` | method compare aliases in quantifier | date compare alias |
| DAT-002 | `context Tournament inv firstDayMatch: self.matches->exists(m: Match | m.startDate.equals(self.startDate))` | equals alias | date equality |
| DAT-003 | `context Rental inv dateOrder: self.startingDate <= self.endingDate` | direct date compare | compare |
| DAT-004 | `context Rental inv mondayClosedNumeric: self.startingDate.dayOfTheWeek() <> 1` | dayOfWeek mapping | `DATE_TIME_OPERATION_MAPPING` |
| DAT-005 | `context Rental inv minDuration: self.startingDate.durationInHours(self.endDate) >= 10` | duration in hours | date/time mapping |
| DAT-006 | `context Customer inv unpaid30Days: self.bill.send_date.durationInDays(self.bill.paid_date.today()) > 30` | today + durationInDays | date/time mapping |
| DAT-007 | `context Rental inv lastFridayClosed: self.startingDate <> self.startingDate.getMonth().last('Friday')` | month start + last weekday | date/time mapping |

## P1 Qualified Navigation

Assumption: qualifier is represented on `Link*` relationships by `sourceRole`, `targetRole`, or `qualifier`.

| ID | OCL | Covers | Expected |
|---|---|---|---|
| QNAV-001 | `context Person inv oneCurrentMarriageWife: self.marriage[wife]->select(m | m.ended = false)->size() = 1` | qualified navigation + filtered size | `QUALIFIED_NAVIGATION` + filtered size |
| QNAV-002 | `context Person inv oneCurrentMarriageHusband: self.marriage[husband]->select(m | m.ended = false)->size() = 1` | second qualifier | qualified navigation |
| QNAV-003 | `context Person inv currentSpouseWife: self.marriage[wife]->select(m | m.ended = false)->notEmpty()` | qualified nav + existence | filtered existence |
| QNAV-004 | `context Person inv noEndedCurrentMarriage: self.marriage[wife]->forAll(m | m.ended = false implies m.wife = self)` | qualified nav + forAll | quantifier |

## Type Operations And Enum Literals

| ID | OCL | Covers | Expected |
|---|---|---|---|
| TYP-001 | `context Person inv noYoungCars: self.age < 18 implies self.fleet->forAll(v | not v.oclIsKindOf(Car))` | oclIsKindOf | method type predicate |
| TYP-002 | `context PrefixedUnit inv referenceNotPrefixed: not self.referenceUnit.oclIsTypeOf(PrefixedUnit)` | oclIsTypeOf | type predicate |
| TYP-003 | `context Customer inv enumGender: self.gender = Gender::female` | enum literal `Enum::x` | literal compare |
| TYP-004 | `context Vehicle inv enumColour: self.colour = #black` | enum literal `#x` | literal compare |
| TYP-005 | `context HealthRecord inv callerCast: self.owner.treatedBy.oclAsType(OclAny)->includes(environment.caller.oclAsType(OclAny))` | oclAsType + includes | type op or fallback |
| TYP-006 | `context Person inv carsOnly: self.fleet->selectByKind(Car)->size() <= 3` | selectByKind | type filtering |
| TYP-007 | `context Person inv exactCars: self.fleet->selectByType(Car)->size() <= 3` | selectByType | type filtering |

## Let, If, Literals, Ranges

| ID | OCL | Covers | Expected |
|---|---|---|---|
| SYN-001 | `context Employee inv letTotalHours: let totHours: Integer = self.worksOn->collect(w | w.hours)->sum() in totHours >= 30 and totHours <= 50` | let binding + aggregate | no parser failure, graph or fallback |
| SYN-002 | `context Customer inv ifExpression: if self.isMale then self.title = 'Mr.' else self.title <> 'Mr.' endif` | if expression | no parser failure |
| SYN-003 | `context Order inv collectionLiteral: Set{'pending', 'invoiced'}->includes(self.state)` | Set literal + includes | collection literal |
| SYN-004 | `context Person inv rangeLiteral: Set{1..5}->includes(self.age)` | range | range + collection literal |
| SYN-005 | `context Person inv sequenceLiteral: Sequence{1, 2, 3}->size() = 3` | Sequence literal | collection literal |
| SYN-006 | `context Person inv undefinedCheck: self.name <> invalid` | invalid literal | undefined/null |
| SYN-007 | `context Person inv nullCheck: self.name <> null` | null literal | null literal |

## Direct Query Expressions For Evaluator

These are not invariants. They cover the "user enters OCL in evaluator" mode.

| ID | OCL | Covers | Expected |
|---|---|---|---|
| EVA-001 | `Customer.allInstances()->select(c | c.age >= 18)->size()` | scalar query | `SCALAR_QUERY` |
| EVA-002 | `Customer.allInstances()->exists(c | c.age >= 18)` | scalar boolean query | allInstances quantifier |
| EVA-003 | `Person::allInstances()->isUnique(p | p.name)` | static allInstances scalar | isUnique group-by |
| EVA-004 | `Company.allInstances()->select(c | c.employees->size() > 10)->notEmpty()` | nested query expression | filtered existence |
| EVA-005 | `self.age >= 18` | evaluator with bound self | object binding / context-less |
| EVA-006 | `dr1.age > 40` | evaluator with bound object variable | explicit object binding |
| EVA-007 | `dr1.fleet->size() <= 3` | bound variable navigation size | navigation size compare |

## Fallback Boundary Tests

These should parse, but may intentionally use legacy or Java fallback until a dedicated pattern exists.

| ID | OCL | Covers | Expected |
|---|---|---|---|
| FB-001 | `context Employee inv collectNested: self.subordinates->collectNested(e | e.worksOn)->size() >= 0` | collectNested | fallback allowed |
| FB-002 | `context Employee inv sortedBy: self.subordinates->sortedBy(e | e.age)->first().age >= 0` | sortedBy | fallback allowed |
| FB-003 | `context Employee inv closure: self.subordinates->closure(e | e.subordinates)->excludes(self)` | closure | fallback allowed |
| FB-004 | `context Employee inv iterateTotal: self.worksOn->iterate(w: Work; total: Integer = 0 | total + w.hours) >= 30` | iterate | fallback allowed |
| FB-005 | `context Person inv recursiveUnion: self.children->union(self.children->collect(c | c.children))->asSet()->size() >= self.children->size()` | union + collect | fallback allowed |

## Minimal Expected Pattern Coverage Checklist

The test set should trigger at least these pattern kinds:

- `ATTRIBUTE_ACCESS`
- `BOOLEAN_ATTRIBUTE_ACCESS`
- `IMPLICIT_SELF_PROPERTY_ACCESS`
- `IMPLICIT_ITERATOR_PROPERTY_ACCESS`
- `NAVIGATION_ACCESS`
- `QUALIFIED_NAVIGATION`
- `ATTRIBUTE_COMPARE`
- `NAVIGATION_COMPARE`
- `LOGICAL_COMPOSITION`
- `LOGICAL_WITH_BOOLEAN_ATTRIBUTE`
- `NEGATION`
- `ALL_INSTANCES_SOURCE`
- `NAVIGATION_SOURCE`
- `FILTER`
- `QUANTIFIER`
- `FILTERED_TERMINAL`
- `FILTERED_SIZE_COMPARE`
- `FILTERED_EXISTENCE_COMPARE`
- `NAVIGATION_SIZE_COMPARE`
- `ALL_INSTANCES_SIZE_COMPARE`
- `OPTIONAL_NAVIGATION_EMPTY_CHECK`
- `MULTI_VAR_QUANTIFIER_OPTIMIZED`
- `IS_UNIQUE_GROUP_BY`
- `AGGREGATE_FUNCTION`
- `AS_SET_SIZE_DISTINCT_COUNT`
- `INCLUDES_ALL_ANTI_JOIN`
- `DATE_TIME_OPERATION_MAPPING`
- `PARTIAL_RECURSIVE_COMPOSITION`
- `CONTEXT_INVARIANT`
- `SCALAR_QUERY`

## High-Value Regression Assertions

For each optimized test, assert:

- Generated Cypher has exactly one top-level query string.
- No Java collection filtering is required after Neo4j returns.
- `MATCH ()-[rel]-()` patterns do not duplicate undirected relationships unless the pattern deliberately counts both directions.
- `count(DISTINCT x)` is used where source rows may duplicate.
- `EXISTS { ... }` / `NOT EXISTS { ... }` is used for existence/forall anti-join where possible.
- `WITH *` scopes preserve `self` and iterator variables across nested subqueries.
- Parameter map is populated for string literals, class names, role names, qualifiers, and enum-like values.
