# Experiments

`alg_exp::experiments` is a catalogue of synthesis and verification
problems. Each problem searches using **training** examples only, and is then
validated on **held-out** inputs it never saw, often from a wider
distribution. The report records both, plus search effort, so results are
comparable across problems.

```bash
./build/release/tools/algexp experiments --list
```

```bash
./build/release/tools/algexp experiments
```

```bash
./build/release/tools/algexp experiments --only gcd_loop --seed 7
```

`--quick` shrinks held-out sets for CI. The GoogleTest suite runs every
catalogue problem in quick mode (`All/CatalogProblem.*`).

## Results (release build, seed 1)

| problem | mode | found | held-out | search | solution |
|---|---|---|---|---|---|
| bell_state | synthesis | yes | 4/4 | 18 states | `h q0; cx q0,q1` |
| rule110 | synthesis | yes | 16 tapes × 10⁴ steps | 1.6M states, 0.7 s | cell: `(l&c&r) ^ (c\|r)`, lifted to an 8-instruction bit-parallel tape program |
| popcount3 | synthesis | yes | 8/8 (3-bit) | 5.2M states, 1.9 s | 5 instructions; **0/32 on 32-bit inputs** (straight-line limit) |
| gcd_loop | sketch | yes | 200/200 | 14.6k candidates | `t=a; a=b; b=t mod a` (Euclid), after 1 CEGIS counterexample |
| popcount32_loop | sketch | yes | 200/200 | 31k candidates | `count++; t=x-1; x&=t` (Kernighan) |
| array_reverse | contract | yes | 200/200 | 26k states | 6-instruction swap body in a two-pointer loop |
| matrix_transpose | contract | yes | 100/100 | 7.6k states | 5-instruction inner body in a nested-loop skeleton |
| teleportation | synthesis | yes | 104/104 | 18 candidates | `if c0: Z q2; if c1: X q2` |
| rpn_interpreter | verification | n/a | 300/300 | – | hand-written 34-instruction interpreter |

## Modes

**synthesis**: straight-line enumerative search (`ProgramSynthesizer`, BFS
with observational-equivalence dedupe). It finds a shortest program. In
practice it reaches about 5–6 instructions over small alphabets.

**sketch**: assembly with labels and holes (`automaton::Sketch`), filled by
`SketchSynthesizer`:

```
top:  addi r1 r1 0     # sets Z from r1
      jz done
      ?? 3             # up to 3 instructions
      jmp top
done:
```

Fills are enumerated shortest-first. Each candidate runs on every training
example under a step budget, and the example that fails moves to the front.
Fills containing a dead write (a register overwritten before it can be read)
are skipped; this is sound and is tested exhaustively. Sketch problems use
**CEGIS**. The candidate is checked on a 500-input probe drawn from the
held-out distribution, and the first failure is added to training. The final
held-out set uses an independent random stream.

Without CEGIS, `gcd_loop` finds `b = b mod a; a = a - b`. It passes small
training pairs but needs about a/b iterations when b ≪ a. The probe catches
it with the `(10⁹, 1)` edge case, and the next round finds Euclid.

**contract**: the loop body is synthesized with the straight-line search
against a per-iteration specification ("swap `*l` and `*r`, `l += 8`,
`r -= 8`"). It is then spliced into a loop skeleton and validated as a whole
program on full inputs.

**verification**: the solution is given, and the harness checks it against
invariants. `rpn_interpreter` checks `0 ≤ sp ≤ 8M` at every step, checks a
sample of transitions against `TransitionSpace` (the step relation
T(Sₜ, Sₜ₊₁)), and compares the final `sp == 1` and `stack[0]` with a reference
evaluator. The test suite also feeds it four mutated interpreters (swapped
`sub` operands, `mul` → `add`, wrong push stride, off-by-one argument read).
Each mutant is rejected.

## Measured limits

* **array_reverse**: the 6-instruction body is found over a 14-instruction
  alphabet (pointer registers r0/r1, data registers r2/r3). A 76-instruction
  alphabet (ld/st/mov/addi over any of r0..r3) and the fully generic
  152-instruction one both exhaust a 60M-state budget, taking about 60 s
  each.
* **matrix_transpose**: the outer loop skeleton is given; only the inner body
  is synthesized.
* **teleportation**: the entangle-and-measure prefix is given; the
  corrections are synthesized.
* **rpn_interpreter**: a multi-way dispatch interpreter is far beyond
  enumerative search, so it is verification-only.
* **Machine words** are 64-bit, so arrays are `i64` rather than the `i32` in
  the original spec.

## Adding a problem

Subclass `experiments::Problem` and add it to a tier's list (`src/experiments/Tier*.cpp`):

```cpp
class MyProblem final : public Problem {
public:
    std::string name() const override { return "my_problem"; }
    std::string mode() const override { return "sketch"; }
    std::string description() const override { return "..."; }
    Outcome run(const RunConfig& cfg) const override {
        // 1. input generators for train and held-out (InputGenerator)
        // 2. goal from each input (GoalFor -> Invariant over the final state)
        // 3. search: ProgramSynthesizer / SketchSynthesizer / your own
        // 4. validate_program(...) on held-out inputs, record_validation(...)
    }
};
```

The goal is any `Invariant`. A symbolic one, such as
`var("regs[1]") == expected`, is evaluated through cached offsets. An opaque
C++ predicate can compare memory, check views (`Value` overlays on machine
memory), or run a reference implementation.
