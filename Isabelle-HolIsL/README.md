# OCL-to-Graph formalization in Isabelle/HOL

This repository contains the Isabelle/HOL formalization in
`isabelle_stage1/` and the associated paper and USE models in `usemodel/`.
Generated Isabelle heaps, a local Isabelle installation, editor state, build
logs, downloads, and legacy prototypes are intentionally excluded from Git.

## Requirements

- Isabelle2025-2 for Linux, macOS, or Windows;
- no third-party Isabelle components are required.

## Check the complete session

From the repository root, using the `isabelle` executable from your local
Isabelle installation:

```bash
isabelle build -D isabelle_stage1 UML_OCL_Graph_Verification
```

## Open the project in Isabelle/jEdit

Build the session first, then run:

```bash
isabelle jedit -d isabelle_stage1 \
  -l UML_OCL_Graph_Verification isabelle_stage1/Adequacy.thy
```

The main session declaration is `isabelle_stage1/ROOT`; the final abstract
correctness theorem is `transformation_correct` in `Adequacy.thy`.
