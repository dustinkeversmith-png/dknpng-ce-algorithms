# Architecture

## 1. Value runtime (`combinatorial::value`)

**ValueType** is immutable and shared (`TypePtr = shared_ptr<const ValueType>`).
It is either a primitive leaf (`u8 … i64, f32, f64, bool`, or an opaque
fixed-size primitive) or a struct of named fields, optionally repeated over a
row-major `shape`.

* Layouts are **packed**: a field's offset is the sum of the preceding field
  sizes. A value's byte image depends only on its type, never on host struct
  alignment, so it can be hashed, diffed, serialised or handed to a solver
  byte for byte.
* `strides()[d]` is the byte distance between consecutive indices of
  dimension `d`; `element_size()` is the size of one (struct or primitive)
  element.
* For a rank-r type, the r types obtained by dropping leading dimensions are
  built once at construction (`suffix(k)`, `element()`). Indexing therefore
  never allocates a type, which keeps element views at about 20 ns.
* All size arithmetic is overflow-checked (`std::length_error`).

**Value** = `TypePtr` + `shared_ptr<vector<byte>>` + offset. A root `Value`
allocates zeroed memory; `at()`, `operator[]`, `flat()`, `reference_member()`
and `reference(path)` return views into the same buffer. Constness is
shallow: a `const Value&` is like a `T* const`. `clone()` deep-copies.

Path grammar: `segment ('.' segment)*`, where `segment := ident? ('[' i (',' i)* ']')*`,
for example `regs[3]`, `grid[1,2].cell`, `[0].x`. `value::locate(type, path)`
resolves a path against a type without needing an instance.

Scalar access comes in two flavours:

* `get<T>/set<T>` is a **bitwise** reinterpretation that requires
  `sizeof(T) == slot size`. This is register casting: reading an `f64` slot
  as `u64` bits.
* `as<T>/assign(double)` is a **numeric** conversion that saturates integers
  and maps NaN to 0. Bools are normalised on read, so arbitrary bytes never
  form an invalid `bool`.

All loads and stores go through `memcpy`, so there are no strict-aliasing or
alignment hazards at any offset.

**TypeRegistry** names types, parses type expressions (`Point[3][4]`,
`f64[2,3]`; an aliased shaped type gets the new dimensions prepended), and
holds an operator table keyed by `(op, canonical type)`. Builtins (`add sub
mul div min max eq ne lt le gt ge neg abs sum dot`) are registered under the
numeric wildcard `*`, broadcast scalars, and compute in the native type with
defined wrap-around (no signed-overflow UB). Integer division by zero and
`MIN / -1` raise `std::domain_error`.

## 2. Constraints and invariants (`combinatorial::problem`)

**Expr / Constraint** form a symbolic AST over leaf paths: `+ - * neg abs min
max`, comparisons, `&& || !`, `all_different`, `between`. A constraint can be

* evaluated against a `Value` or any `Env`;
* bounded with interval arithmetic (`feasible(env) → Tri{True, False,
  Unknown}`). Soundness is property-tested: every point sample lies inside
  its bound;
* lowered to SMT-LIB2 (`Smt.hpp`). Integer and bool leaves become `Int` with
  their machine range asserted; float leaves become `Real`; mixed terms are
  coerced with `to_real`; literals are the shortest decimal that round-trips.
  `solve_smt` runs z3 (or any `-smt2` solver), parses the model and writes it
  back into a `Value`.

**Invariant** is an immutable predicate tree whose leaves are either opaque
C++ predicates (with an optional explainer and declared dependency paths) or
symbolic constraints. Composition flattens nested `&&`/`||` and captures
operands by value, so trees are safe to copy and share. Each tree offers:

* `holds`
* `explain`: first cause
* `violations`: every failing conjunct
* `dependencies`
* `constraint()`: present when fully symbolic, which enables SMT lowering and pruning
* `feasible`

## 3. Spaces and synthesis

`Space` describes a set of Values: structural invariants (encoding validity),
semantic invariants (meaning), `distance` under a `Norm` (L1, L2, L∞,
Hamming; leaf-wise over numeric slots, opaque leaves count 0 or 1),
`project` onto the set, `generate`, and optional finite enumeration.
`intersect` / `unite` / `difference` (also `& | -`) compose spaces with the
expected membership, generation (rejection sampling with an enumeration
fallback) and projection (a union picks the closer side).

**Synthesizer** works over a base Value, a set of decision leaves with finite
`Domain`s, and a list of invariants:

* Conjunctions are split. Each conjunct is attached to the search depth at
  which its last dependency is assigned (**forward checking**). Opaque
  predicates without declared dependencies run at the leaves of the search.
* Symbolic conjuncts are also bounded at every intermediate depth with the
  unassigned leaves spanning their domain hull (**interval pruning**).
* Variable order: most-constrained-first by default, or smallest-domain, or
  declaration order.
* `nearest(target, norm)` is a **branch and bound** over distance-sorted
  domains. It is exact projection onto a finite space and is checked against
  brute force for all four norms.
* `min_conflicts` is stochastic local search for large instances (it solves
  30-queens in under 100 steps).

`ConstrainedSpace` wraps a synthesizer. Its structural invariants are "each
decision leaf is in its domain" and "fixed leaves equal the base". Its
**topology** is the graph whose edges join members that differ in one
decision leaf by one domain step. It provides `neighbors`, `reachable` (BFS)
and `connected_components`.

`BoxSpace` is a continuous axis-aligned box; its projection is exact clamping.

## 4. Automaton

The machine state *is* a `Value` of struct type `State{pc u32, sp u32, flags
u8, halted u8, regs i64[8], mem u8[M]}`, and instructions are `Instr{op, a, b,
c u8, imm i32}`, so programs are `Instr[n]` Values too. `Machine::execute`
works directly on the state's bytes using cached field offsets (about 11 ns
per step). Faults (bad register, out-of-range address, stack over- or
underflow, division by zero, invalid opcode) halt with `F` set and never
touch memory out of bounds.

`TransitionSpace` is the space of `{before, instr, after}` triples. The step
relation T(Sₜ, Sₜ₊₁) is expressed as one invariant per state component (pc,
sp, flags, halted, regs, mem), so a forged transition is reported as, for
example, "regs differs at byte 0 for 'addi r0 r1 3'". Its projection
recomputes `after`.

`ProgramSynthesizer` takes input states paired with goal invariants over the
final states, and an alphabet of opcodes × usable registers × immediate pool,
with commutative-operand symmetry and trivial no-ops removed. It searches by
increasing length, so the first program found is a shortest one:

* **Observational equivalence**: a BFS over the tuple of machine states across
  all examples, discarding any program whose tuple was already reached.
* **Plain iterative-deepening DFS**: the baseline.

## 5. Quantum

A state is a `Value` of type `f64[2^N, 2]`. Qubit q is bit q of the basis
index. Gates update the amplitude tensor **in place** through the Value's
bytes:

* `apply_1q`: blocked 2×2 update with an arbitrary control mask
* specialised `cnot`, `cz` and `swap` paths
* `apply_unitary`: the general k-qubit contraction

Every named gate is tested to match `apply_unitary` with its own dense
unitary. On top of the gates sit measurement with collapse, sampling, reduced
purity and linear entropy across any bipartition, `Circuit` (with `inverse`,
dense `unitary()`, `ghz`, and a `qft` verified against the DFT matrix), and
BFS circuit synthesis up to global phase.

`StateSpace(N)` checks finite amplitudes (structural) and Σ|cᵢ|² = 1
(semantic), draws Haar-random states, and projects by renormalisation (the
exact L2-nearest unit vector). `entanglement_bound` and `unitary_transition`
are invariants usable in any space.

## Error model

Library functions reject bad input with `std::invalid_argument`,
`std::out_of_range`, `std::length_error` or `std::domain_error`, and nothing
else. The fuzz harnesses enforce this: any other exception, sanitizer report
or property failure aborts.
