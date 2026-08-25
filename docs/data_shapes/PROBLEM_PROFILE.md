# Programming Problem Profile Layer

This implementation keeps the original generic `ProblemContext` shape intact and adds a typed profile layer for programming problems.

## New package

```txt
main/scala/engine/problem/
```

## Main types

```scala
ProgrammingProblemProfile
ProblemExample
ProblemSpace
ProblemFormatFunctor[Input, Output]
JsonProgrammingProblemFunctor
JsonProblemSpaceFunctor
```

## Supported input styles

### Direct programming problem JSON/JSONic

```jsonc
{
  "id": "two-sum-basic",
  "kind": "algorithm",
  "domain": "arrays",
  "title": "Two Sum",
  "description": "Given an array and target, return two indices.",
  "input.spec": { "nums": "Array[Int]", "target": "Int" },
  "output.spec": "Array[Int]",
  "constraints": ["Exactly one answer", "Do not reuse an index"],
  "examples": [
    { "input": { "nums": [2, 7], "target": 9 }, "output": [0, 1] }
  ]
}
```

### Existing `ProblemContext` JSON

```json
{
  "facts": {
    "problem.id": { "StringValue": "p1" },
    "title": { "StringValue": "Already Normalized" },
    "description": { "StringValue": "A context-shaped problem." },
    "problem.kind": { "StringValue": "meta" }
  },
  "unknowns": {},
  "goals": ["test goal"],
  "artifacts": {}
}
```

### Problem space JSON

```jsonc
{
  "problems": [
    { "id": "p1", "kind": "algorithm", "title": "A", "description": "..." },
    { "id": "p2", "kind": "parser", "title": "B", "description": "..." }
  ]
}
```

## Conversion examples

```scala
import engine.problem.JsonProgrammingProblemFunctor

val contextEither = JsonProgrammingProblemFunctor.map(jsonText)
```

```scala
import engine.problem.JsonProblemSpaceFunctor

val spaceEither = JsonProblemSpaceFunctor.map(jsonText)
```

The resulting `ProblemContext` uses standardized keys:

```txt
profile
problem.id
problem.kind
problem.domain
title
description
input.spec
output.spec
constraints
examples
edgeCases
complexity.target
language.target
requiredBehavior
allowedTechniques
forbiddenTechniques
evaluation.tests
```
