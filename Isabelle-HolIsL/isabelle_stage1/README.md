# Stage: OCL validation and OCL -> graph-query compilation

This package extends the previous class/snapshot/graph builder formalization.

## Architecture

```text
MM_Class
   ^
   | model_conforms
   M = classes + attrs + associations + inheritance + OCL invariants
   |
   | OCL validation / resolution against M
   v
Surface OCL AST
   |
   | Front M
   v
Resolved Core AST
   |
   | lower Sigma
   v
Graph Query AST Q
```

The graph branch remains:

```text
(M,S,Sigma) --build--> G
```

The query branch is now:

```text
(M,O_i,Sigma) --compile_invariant--> q_i
```

## Why there are two OCL layers

`ocl_expr` is the surface language. It contains names such as:

- `age`
- `employee`
- `Person`
- iterator variables such as `e`

Those names are not trusted.

`infer_type M C Gamma e` checks every occurrence against the actual model M.
It returns `None` for an invalid OCL expression.

`front M I` runs only for a valid Boolean invariant and resolves names to model keys:

- `self.age` -> resolved attribute key `(ownerClass, age)`
- `self.employee` -> resolved association key + logical navigation direction + target multiplicity
- `Person.allInstances()` -> resolved class key

The later lowering stage therefore never performs ambiguous name lookup.

## Important acceptance condition

```isabelle
invariant_wf M I
```

requires:

```text
I is declared in M
context class exists in M
body type-checks to Boolean
all variables are in scope
attribute access resolves uniquely
association-role navigation resolves uniquely
operators receive legal operand types
iterator source is a collection
iterator body has the required type
expression satisfies the published supported profile
```

`supported_expr M C Gamma e` is the single profile predicate used by the
compiler claims. It admits mathematical Real values, including mixed
Integer/Real arithmetic and comparisons. It excludes String ordering, while
`sum` is admitted only for Set(Integer) and Bag(Integer); the collection
semantic value does not retain an element type for an empty Real collection.
Moreover, `isUnique`, `xor`, `reject`, and surface `null`/`invalid` are not
constructors of the admitted surface AST.

The full model is accepted by:

```isabelle
ocl_model_conforms M
```

which combines structural class-model conformance with validity of every OCL invariant.

## Compiler

```isabelle
compile_invariant ::
  rep_spec => ocl_model => ocl_invariant => graph_query option
```

A successful result is an actually generated query AST. `None` is rejection.

The compiler additionally requires the same representation contract `Sigma` used by the snapshot builder and requires `builder_supported_model M`, so it cannot compile an OCL query for source data that the graph builder does not represent.

## Main safety lemmas

```isabelle
compile_success_model_conforms
compile_success_rep_spec_wf
compile_success_invariant_wf
compile_rejects_ill_typed_ocl
compile_success_has_boolean_source_body
compile_success_has_supported_source_body
compile_total_on_admitted_invariant
admitted_model_generates_query_for_each_invariant
build_graph_conforms
lower_expr_semantic_preservation
compile_invariant_preserves_violations
transformation_correct_pointwise
build_attr_observation_adequate
build_nav_observation_adequate
build_scan_observation_adequate
build_label_iff_subclass
build_class_scan_exact
snapshot_scan_ids_exact
transformation_correct
```

Thus successful compilation implies that the source OCL was valid with respect
to M.  Independently, `build_graph_conforms` proves that every admissible
`(Sigma,M,S)` is transformed into a finite graph conforming to `MM_Graph`;
this includes well-formed labels/properties, supported property values, valid
relationship types, and endpoints that exist in the generated node set.

## Core -> Query rules

Examples:

```text
CAttr(e, attribute-key)
  -> QProperty(lower(e), Sigma_attr(attribute-key))

CNavOne(e,r)
  -> QNavOne(lower(e), relationship type + physical traversal from Sigma)

CNavMany(e,r)
  -> QNavMany(lower(e), relationship type + physical traversal from Sigma)

CForAll(C,x,b)
  -> QForAll3(lower(C),x,lower(b))

CExists(C,x,b)
  -> QExists3(lower(C),x,lower(b))

CScan(K)
  -> QScan(Sigma_class_label(K))
```

The logical UML navigation direction is combined with the physical graph direction selected by `Sigma` through `physical_traversal`.

## Final restricted Cypher AST

`Cypher.thy` adds the final formal target artifact:

```isabelle
compile_cypher ::
  rep_spec => ocl_model => ocl_invariant => cypher_query option
```

The produced query contains:

- context label for the initial context scan;
- resolved graph predicate;
- the physical relationship traversal decisions already fixed by `Sigma`;
- `cy_keep_non_true = True`, i.e. violations are all results different from True;
- stable-ID return property;
- `cy_return_distinct = True`.

Thus the complete formal generation chain is now:

```text
OCL surface
   -> infer_type / validation against M
   -> Front (resolved Core)
   -> lower Sigma (Q)
   -> realize_query (restricted Cypher AST)
```

Concrete string serialization remains a pure target-syntax boundary and should be handled by a later pretty-printer theory/tool.

## Executable Branch--Employee case study

`Example.thy` instantiates the paper's salary invariant and proves that:

- the class model, invariant, representation specification, and snapshot conform;
- the builder input is admissible and the generated graph conforms to `MM_Graph`;
- source objects and generated nodes are in bijection;
- the physically reversed `EMPLOYEE` relationship is traversed in the correct
  logical direction;
- compilation succeeds and produces a distinct violation query over label
  `Branch`, returning the stable property `id`.

Its executable checks evaluate to:

```text
{"oneBranch", "Alice", "Bob"}
("Bob", "oneBranch")
TraverseIn
```

Run the complete session from the repository root with:

```bash
./isabelle-cli build -D isabelle_stage1
```

Open it interactively with:

```bash
./isabelle-cli jedit -d isabelle_stage1 isabelle_stage1/Example.thy
```

## Semantic preservation

`Semantics.thy` now supplies independent executable evaluators for resolved OCL
Core and the graph-query AST, including undefined values, attributes,
navigation, class scans, Boolean/integer operations, and collection iterators.
It proves by structural induction that lowering preserves every expression and
therefore preserves both the complete violation set and pointwise violation
membership.

The intermediate theorem is deliberately stated in locale
`adequate_observations`. Its three assumptions say that snapshot-to-graph
construction preserves attribute lookup, association navigation, and class
extents. This avoids defining graph evaluation in terms of source evaluation.

`Adequacy.thy` discharges all three assumptions directly from the equations of
`build Sigma M S`. `build_class_scan_exact` additionally proves that scanning
a generated class label selects exactly the snapshot objects of that class
or one of its subclasses; its source-side set uses `subclass_of` directly,
independently of `build_labels`.

Its final theorem `transformation_correct` combines:

- conformance of the generated graph to `MM_Graph`;
- the bijection `obj_id` from source objects to generated nodes;
- successful OCL-to-query compilation; and
- exact equality of the source and target violation sets.

Consequently, the Isabelle development now contains the closed theorem that
corresponds to the paper's main correctness claim. Concrete serialization and
execution against an external Neo4j server remain implementation/integration
work, not missing obligations of this abstract transformation theorem.
