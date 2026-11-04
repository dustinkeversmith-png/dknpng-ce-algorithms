# Guide

## Types and views

```cpp
using namespace combinatorial;
value::TypeRegistry reg;
reg.define_struct("Point", {{"x", "f64"}, {"y", "f64"}});
reg.define_struct("Body", {{"id", "u32"}, {"pos", "Point"}, {"trail", "Point[8]"}});

value::Value world(reg.get("Body[100]"));
world.reference("[42].trail[3].y").set<double>(1.5);   // zero-copy path view
value::Value body = world[42];                          // element view
body.reference_member("pos").copy_from(body.reference("trail[3]"));
auto sum = reg.apply("sum", {world[42].reference("trail")});  // throws: struct array
```

## Constraint problems

```cpp
using namespace combinatorial::problem;
// 8 queens: queens[i] = column of the queen in row i
std::vector<Invariant> safe;
for (int i = 0; i < 8; ++i)
    for (int j = i + 1; j < 8; ++j) {
        Expr a = var("[" + std::to_string(i) + "]"), b = var("[" + std::to_string(j) + "]");
        safe.emplace_back((a != b) && (a - b != j - i) && (b - a != j - i));
    }
auto space = ConstrainedSpace::over_all_leaves("queens", reg.get("u8[8]"), Domain::range(0, 7), safe);
space->count();                      // 92
auto nearest = space->project(v);    // exact branch & bound projection
auto lowered = lower_to_smtlib(space->value_type(), safe);   // SMT-LIB2 text
```

Compose spaces with `a & b`, `a | b`, `a - b`. Membership, generation and
enumeration behave as the set operations they name.

## Program synthesis

```cpp
using namespace combinatorial::automaton;
Machine m(64);
std::vector<Example> ex;
for (std::int64_t x : {1, 3, -7}) {
    auto s = m.initial_state();
    m.set_reg(s, 0, x);
    ex.push_back({s, Invariant(var("regs[1]") == double(5 * x))});
}
ProgramSynthesizer ps(m, ex, {.registers = 3, .max_length = 3});
auto prog = ps.synthesize();   // movi r1 2; shl r1 r0 r1; add r1 r0 r1
```

## Quantum circuits

```cpp
using namespace combinatorial::quantum;
Circuit c(3);
c.h(0).cnot(0, 1).cnot(1, 2);
value::Value psi = c.run();              // (|000> + |111>)/√2
linear_entropy(psi, 0b001);              // 0.5
StateSpace(3).contains(psi);             // normalised
std::mt19937_64 rng(1);
measure(psi, 0, rng);                    // collapses all three qubits
```
