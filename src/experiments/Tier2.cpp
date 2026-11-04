// Tier 2: loop problems.
//   gcd_loop          - sketch: while (r1 != 0) { ?? 3 }              (matrix problem 3)
//   popcount32_loop   - sketch: while (r0 != 0) { ?? 3 }              (matrix problem 4)
//   array_reverse     - body from an iteration contract, two-pointer loop (problem 1)
//   matrix_transpose  - inner body from an iteration contract, fixed nested skeleton (problem 2)
//
// "Sketch" problems search loop bodies directly by running whole programs.
// "Contract" problems synthesise the body with the straight-line search
// against a per-iteration specification, then validate the complete loop on
// whole inputs it never saw.

#include "alg_exp/automaton/Sketch.hpp"
#include "alg_exp/experiments/Experiment.hpp"
#include "alg_exp/value/TypeRegistry.hpp"

#include <bit>
#include <chrono>
#include <cmath>
#include <cstring>
#include <numeric>

namespace alg_exp::experiments {

namespace {

using automaton::Instruction;
using automaton::Machine;
using automaton::Op;
using automaton::Program;
using problem::Invariant;
using problem::var;
using Clock = std::chrono::steady_clock;

double since(Clock::time_point t) { return std::chrono::duration<double>(Clock::now() - t).count(); }

Invariant reg_is(std::size_t r, std::int64_t v) {
    return Invariant(var("regs[" + std::to_string(r) + "]") == static_cast<double>(v));
}

std::uint64_t load_word(const Machine& m, const value::Value& s, std::size_t addr) {
    std::uint64_t w = 0;
    for (int i = 7; i >= 0; --i) w = (w << 8) | m.mem(s, addr + static_cast<std::size_t>(i));
    return w;
}
void store_word(const Machine& m, const value::Value& s, std::size_t addr, std::uint64_t w) {
    for (int i = 0; i < 8; ++i) m.set_mem(s, addr + static_cast<std::size_t>(i), static_cast<std::uint8_t>(w >> (8 * i)));
}

/// Sketch synthesis with counterexample-guided refinement (CEGIS):
/// synthesise on the training set, test the candidate on a separate probe set
/// drawn from the held-out distribution, add the first failing probe input to
/// training, and repeat. The final held-out set uses an independent random
/// stream and is never used for training.
Outcome run_sketch(const Machine& m, const std::string& sketch_text, const automaton::SketchOptions& opts,
                   const InputGenerator& train_gen, const std::function<InputGenerator()>& make_test_gen,
                   const GoalFor& goal, std::size_t train, std::size_t test, std::uint64_t test_steps,
                   std::uint64_t seed) {
    Outcome out;
    InputGenerator test_gen = make_test_gen();  // probe stream
    Rng rng(seed);
    auto ex = make_examples(train_gen, goal, train, rng);
    const auto sketch = automaton::Sketch::parse(sketch_text);
    constexpr int kMaxRounds = 8;
    constexpr std::size_t kProbe = 500;  // probes are cheap next to search; rare bugs need many
    std::optional<Program> p;
    std::vector<std::vector<Instruction>> fills;
    std::uint64_t pruned = 0;
    int rounds = 0;
    Rng probe_rng(seed * 0x9e3779b97f4a7c15ULL + 1);
    for (; rounds < kMaxRounds; ++rounds) {
        automaton::SketchSynthesizer ss(m, sketch, ex, opts);
        p = ss.synthesize();
        out.search_states += ss.stats().candidates;
        out.search_seconds += ss.stats().seconds;
        pruned += ss.stats().pruned;
        if (!p) {
            out.notes = ss.stats().budget_exhausted ? "candidate budget exhausted; " : "no fill within hole bounds; ";
            break;
        }
        fills = ss.fills();
        // Probe for a counterexample.
        std::optional<value::Value> counterexample;
        for (std::size_t i = 0; i < kProbe && !counterexample; ++i) {
            value::Value in = test_gen(probe_rng);
            value::Value s = in.clone();
            const auto r = m.run(s, *p, test_steps);
            const bool ok = (r.reason == automaton::HaltReason::Halt || r.reason == automaton::HaltReason::EndOfProgram) &&
                            goal(in).holds(s);
            if (!ok) counterexample = in;
        }
        if (!counterexample) break;
        ex.push_back({*counterexample, goal(*counterexample)});
    }
    out.train_examples = ex.size();
    out.solved = p.has_value();
    if (!p) return out;
    std::string body;
    for (const auto& f : fills) body += (body.empty() ? "" : " | ") + automaton::to_string(f);
    out.solution = "body: " + body;
    Rng held_out(seed ^ 0xa5a5a5a5a5a5a5a5ULL);
    const auto t1 = Clock::now();
    record_validation(out, validate_program(m, *p, make_test_gen(), goal, test, held_out, test_steps), since(t1));
    out.notes += std::to_string(rounds + 1) + " CEGIS round(s), " + std::to_string(ex.size() - train) +
                 " counterexample(s) added; " + std::to_string(pruned) + " fills pruned as dead writes; ";
    return out;
}

// ------------------------------------------------------------------- GCD ---

class GcdLoop final : public Problem {
public:
    std::string name() const override { return "gcd_loop"; }
    std::string mode() const override { return "sketch"; }
    std::string description() const override {
        return "Euclid from examples: fill the body of while (b != 0) { ?? 3 } over mov/mod/sub; a,b in r0,r1, "
               "result in r0; held-out pairs up to 1e9 plus edge cases";
    }
    Outcome run(const RunConfig& cfg) const override {
        Machine m(64);
        auto make = [&m](std::int64_t a, std::int64_t b) {
            value::Value s = m.initial_state();
            m.set_reg(s, 0, a);
            m.set_reg(s, 1, b);
            return s;
        };
        InputGenerator train = [make](Rng& rng) {
            return make(1 + static_cast<std::int64_t>(rng() % 200), 1 + static_cast<std::int64_t>(rng() % 200));
        };
        auto test = [make]() -> InputGenerator {
            auto edge = std::make_shared<std::size_t>(0);
            return [make, edge](Rng& rng) {
                const std::int64_t big = 1'000'000'000;
                switch ((*edge)++) {
                    case 0: return make(1, big);                    // extreme scales
                    case 1: return make(big, big);                  // equal inputs
                    case 2: return make(999'999'937, 999'999'929);  // distinct primes (coprime)
                    case 3: return make(832'040, 514'229);          // consecutive Fibonacci: Euclid's worst case
                    case 4: return make(7, 7);
                    case 5: return make(big, 1);                    // extreme scales, mirrored
                    case 6: return make(big, 3);
                    default:
                        return make(1 + static_cast<std::int64_t>(rng() % big),
                                    1 + static_cast<std::int64_t>(rng() % big));
                }
            };
        };
        GoalFor goal = [&m](const value::Value& in) { return reg_is(0, std::gcd(m.reg(in, 0), m.reg(in, 1))); };
        automaton::SketchOptions o;
        o.alphabets[""] = automaton::ProgramSearchOptions{.ops = {Op::Mov, Op::Mod, Op::Sub}, .registers = 3};
        o.max_steps = 2'000;
        Outcome out = run_sketch(m, R"(
            top:  addi r1 r1 0
                  jz done
                  ?? 3
                  jmp top
            done:)",
                                 o, train, test, goal, 6, cfg.quick ? 40 : 200, 20'000, cfg.seed);
        out.notes += "edge cases: (1,1e9), (1e9,1), (1e9,3), (1e9,1e9), coprime primes, Fibonacci worst case; ";
        return out;
    }
};

// ------------------------------------------------------------- popcount ---

class Popcount32Loop final : public Problem {
public:
    std::string name() const override { return "popcount32_loop"; }
    std::string mode() const override { return "sketch"; }
    std::string description() const override {
        return "32-bit popcount from examples: fill while (x != 0) { ?? 3 } over addi/and/sub/mov; x in r0, count "
               "in r1; held-out Hamming weights 0, 1, 16, 31, 32 and random masks";
    }
    Outcome run(const RunConfig& cfg) const override {
        Machine m(64);
        auto make = [&m](std::uint32_t x) {
            value::Value s = m.initial_state();
            m.set_reg(s, 0, static_cast<std::int64_t>(x));
            return s;
        };
        InputGenerator train = [make](Rng& rng) { return make(static_cast<std::uint32_t>(rng() & 0xffff)); };
        auto test = [make]() -> InputGenerator {
            auto k = std::make_shared<std::size_t>(0);
            return [make, k](Rng& rng) {
                static const std::uint32_t edges[] = {0u,          1u,          0x80000000u, 0x0000ffffu,
                                                      0xaaaaaaaau, 0x7fffffffu, 0xffffffffu};
                if (*k < std::size(edges)) return make(edges[(*k)++]);
                return make(static_cast<std::uint32_t>(rng()));
            };
        };
        GoalFor goal = [&m](const value::Value& in) {
            return reg_is(1, std::popcount(static_cast<std::uint64_t>(m.reg(in, 0))));
        };
        automaton::SketchOptions o;
        o.alphabets[""] = automaton::ProgramSearchOptions{
            .ops = {Op::Addi, Op::And, Op::Sub, Op::Mov}, .immediates = {1, -1}, .registers = 3};
        o.max_steps = 1'000;
        Outcome out = run_sketch(m, R"(
            top:  addi r0 r0 0
                  jz done
                  ?? 3
                  jmp top
            done:)",
                                 o, train, test, goal, 6, cfg.quick ? 40 : 200, 2'000, cfg.seed);
        out.notes += "trained on 16-bit inputs, validated on 32-bit; ";
        return out;
    }
};

// ------------------------------------------------------- contract helper ---

/// Synthesises a loop body against per-iteration examples, then splices it
/// into `skeleton` (one hole) and validates the whole program.
struct ContractSpec {
    Machine train_machine;
    std::vector<automaton::Example> iteration_examples;
    automaton::ProgramSearchOptions body_options;
    std::string skeleton;
    Machine test_machine;
    InputGenerator test_gen;
    GoalFor test_goal;
    std::size_t test_cases;
    std::uint64_t test_steps;
};

Outcome run_contract(ContractSpec& spec, std::uint64_t seed) {
    Outcome out;
    out.train_examples = spec.iteration_examples.size();
    automaton::ProgramSynthesizer ps(spec.train_machine, spec.iteration_examples, spec.body_options);
    auto body = ps.synthesize();
    record_search(out, ps, body);
    if (!body) return out;
    const Program full = automaton::Sketch::parse(spec.skeleton).instantiate({*body});
    out.solution = "body: " + automaton::to_string(*body) + "  | loop program: " + std::to_string(full.size()) +
                   " instructions";
    Rng rng(seed ^ 0x5eed);
    const auto t1 = Clock::now();
    record_validation(out,
                      validate_program(spec.test_machine, full, spec.test_gen, spec.test_goal, spec.test_cases, rng,
                                       spec.test_steps),
                      since(t1));
    return out;
}

// --------------------------------------------------------- array reverse ---

class ArrayReverse final : public Problem {
public:
    std::string name() const override { return "array_reverse"; }
    std::string mode() const override { return "contract"; }
    std::string description() const override {
        return "In-place reversal of an i64 array: synthesise the two-pointer loop body from an iteration contract "
               "(swap *l and *r, l += 8, r -= 8), validate whole arrays of length 2..64";
    }
    Outcome run(const RunConfig& cfg) const override {
        Machine train_m(64);  // 8 words: keeps dedupe keys small
        Rng rng(cfg.seed);
        std::vector<automaton::Example> ex;
        for (int k = 0; k < 3; ++k) {
            value::Value s = train_m.initial_state();
            for (std::size_t w = 0; w < 8; ++w) store_word(train_m, s, 8 * w, rng());
            const std::size_t l = rng() % 7, r = l + 1 + rng() % (7 - l);
            train_m.set_reg(s, 0, static_cast<std::int64_t>(8 * l));
            train_m.set_reg(s, 1, static_cast<std::int64_t>(8 * r));
            value::Value want = s.clone();
            store_word(train_m, want, 8 * l, load_word(train_m, s, 8 * r));
            store_word(train_m, want, 8 * r, load_word(train_m, s, 8 * l));
            const Machine* tm = &train_m;
            ex.push_back({s, Invariant("swapped and stepped", [tm, want, l, r](const value::Value& v) {
                              if (tm->reg(v, 0) != static_cast<std::int64_t>(8 * (l + 1))) return false;
                              if (tm->reg(v, 1) != static_cast<std::int64_t>(8 * (r - 1))) return false;
                              for (std::size_t a = 0; a < 64; ++a)
                                  if (tm->mem(v, a) != tm->mem(want, a)) return false;
                              return true;
                          })});
        }
        // Restricted alphabet: word load/store through the pointer registers
        // r0/r1 with data registers r2/r3, +-8 pointer steps, data moves (14
        // instructions). Measured limits for a 6-instruction body: a 76-
        // instruction set (ld/st/mov/addi over any of r0..r3) and the generic
        // 152-instruction set both exhaust a 60M-state budget without a solution.
        std::vector<Instruction> alphabet;
        for (std::uint8_t d : {2, 3})
            for (std::uint8_t p : {0, 1}) {
                alphabet.push_back({Op::Ld, d, p, 0, 0});
                alphabet.push_back({Op::St, d, p, 0, 0});
            }
        for (std::uint8_t p : {0, 1})
            for (std::int32_t step : {8, -8}) alphabet.push_back({Op::Addi, p, p, 0, step});
        alphabet.push_back({Op::Mov, 2, 3, 0, 0});
        alphabet.push_back({Op::Mov, 3, 2, 0, 0});
        const automaton::ProgramSearchOptions body_opts{
            .registers = 4, .max_length = 6, .max_states = 40'000'000, .alphabet = alphabet};

        Machine test_m(1024);
        const Machine* vm = &test_m;
        InputGenerator gen = [vm](Rng& g) {
            value::Value s = vm->initial_state();
            for (std::size_t a = 0; a < vm->memory_bytes(); ++a) vm->set_mem(s, a, static_cast<std::uint8_t>(g()));
            const std::size_t n = 2 + g() % 63;  // [2, 64]
            vm->set_reg(s, 0, 0);
            vm->set_reg(s, 1, static_cast<std::int64_t>(8 * (n - 1)));
            vm->set_reg(s, 7, static_cast<std::int64_t>(n));  // length, for the checker only
            return s;
        };
        GoalFor goal = [vm](const value::Value& in) {
            const std::size_t n = static_cast<std::size_t>(vm->reg(in, 7));
            value::Value want = in.clone();
            for (std::size_t i = 0; i < n; ++i) store_word(*vm, want, 8 * i, load_word(*vm, in, 8 * (n - 1 - i)));
            return Invariant("reversed in place, rest of memory untouched", [vm, want](const value::Value& v) {
                for (std::size_t a = 0; a < vm->memory_bytes(); ++a)
                    if (vm->mem(v, a) != vm->mem(want, a)) return false;
                return true;
            });
        };
        ContractSpec spec{train_m,
                          ex,
                          body_opts,
                          R"(top:  cmp r0 r1
                                   jn body
                                   jmp done
                             body: ?? 6
                                   jmp top
                             done:)",
                          test_m,
                          gen,
                          goal,
                          std::size_t{cfg.quick ? 30u : 200u},
                          5'000};
        Outcome out = run_contract(spec, cfg.seed);
        out.notes += "restricted alphabet (" + std::to_string(alphabet.size()) +
                     " instructions; 76- and 152-instruction alphabets exhaust 60M states); held-out: random "
                     "lengths 2..64, odd and even, memory around the array checked untouched; ";
        return out;
    }
};

// ------------------------------------------------------ matrix transpose ---

class MatrixTranspose final : public Problem {
public:
    std::string name() const override { return "matrix_transpose"; }
    std::string mode() const override { return "contract"; }
    std::string description() const override {
        return "Transpose A[R,C] (f64) into B[C,R]: synthesise the inner-loop body from an iteration contract inside a "
               "fixed nested-loop skeleton; validate random non-square R,C in [2,8]";
    }
    Outcome run(const RunConfig& cfg) const override {
        // Registers: r0 = pa, r1 = pb, r2 = inner count, r3 = 8R stride, r4 scratch,
        // r5 = rows remaining, r6 = B column base, r7 = C.
        Machine train_m(128);
        Rng rng(cfg.seed);
        std::vector<automaton::Example> ex;
        for (int k = 0; k < 4; ++k) {
            value::Value s = train_m.initial_state();
            for (std::size_t w = 0; w < 16; ++w) store_word(train_m, s, 8 * w, rng());
            const std::size_t pa = 8 * (rng() % 16);
            std::size_t pb = 8 * (rng() % 16);
            if (pb == pa) pb = (pa + 8 * (1 + rng() % 15)) % 128;
            const std::int64_t cnt = 1 + static_cast<std::int64_t>(rng() % 5);
            const std::int64_t stride = 8 * (1 + static_cast<std::int64_t>(rng() % 4));
            train_m.set_reg(s, 0, static_cast<std::int64_t>(pa));
            train_m.set_reg(s, 1, static_cast<std::int64_t>(pb));
            train_m.set_reg(s, 2, cnt);
            train_m.set_reg(s, 3, stride);
            for (std::size_t r = 5; r < 8; ++r) train_m.set_reg(s, r, static_cast<std::int64_t>(rng() % 1000));
            value::Value want = s.clone();
            store_word(train_m, want, pb, load_word(train_m, s, pa));
            train_m.set_reg(want, 0, static_cast<std::int64_t>(pa + 8));
            train_m.set_reg(want, 1, static_cast<std::int64_t>(pb) + stride);
            train_m.set_reg(want, 2, cnt - 1);
            const Machine* tm = &train_m;
            ex.push_back({s, Invariant("copied one element and advanced", [tm, want](const value::Value& v) {
                              for (std::size_t r : {0, 1, 2, 3, 5, 6, 7})
                                  if (tm->reg(v, r) != tm->reg(want, r)) return false;
                              for (std::size_t a = 0; a < 128; ++a)
                                  if (tm->mem(v, a) != tm->mem(want, a)) return false;
                              return true;
                          })});
        }
        std::vector<Instruction> alphabet;
        for (std::uint8_t p : {0, 1}) {
            alphabet.push_back({Op::Ld, 4, p, 0, 0});
            alphabet.push_back({Op::St, 4, p, 0, 0});
            for (std::int32_t step : {8, -8}) alphabet.push_back({Op::Addi, p, p, 0, step});
            alphabet.push_back({Op::Add, p, p, 3, 0});
        }
        alphabet.push_back({Op::Addi, 2, 2, 0, -1});
        alphabet.push_back({Op::Addi, 2, 2, 0, 1});

        Machine test_m(2048);
        const Machine* vm = &test_m;
        auto reg = std::make_shared<value::TypeRegistry>();
        InputGenerator gen = [vm](Rng& g) {
            value::Value s = vm->initial_state();
            for (std::size_t a = 0; a < vm->memory_bytes(); ++a) vm->set_mem(s, a, static_cast<std::uint8_t>(g()));
            std::size_t R = 2 + g() % 7, C = 2 + g() % 7;
            if (R == C) C = 2 + (C - 1) % 7;  // prefer non-square
            std::uniform_real_distribution<double> u(-1e3, 1e3);
            for (std::size_t i = 0; i < R * C; ++i) store_word(*vm, s, 8 * i, std::bit_cast<std::uint64_t>(u(g)));
            vm->set_reg(s, 0, 0);
            vm->set_reg(s, 3, static_cast<std::int64_t>(8 * R));
            vm->set_reg(s, 4, static_cast<std::int64_t>(C));  // checker metadata (scratch reg, overwritten)
            vm->set_reg(s, 5, static_cast<std::int64_t>(R));
            vm->set_reg(s, 6, static_cast<std::int64_t>(8 * R * C));
            vm->set_reg(s, 7, static_cast<std::int64_t>(C));
            return s;
        };
        GoalFor goal = [vm, reg](const value::Value& in) {
            const auto R = static_cast<std::size_t>(vm->reg(in, 5)), C = static_cast<std::size_t>(vm->reg(in, 7));
            const std::size_t mem = vm->layout().mem;
            // Zero-copy views of A and B over the machine memory.
            const auto tA = reg->get("f64[" + std::to_string(R) + "," + std::to_string(C) + "]");
            const auto tB = reg->get("f64[" + std::to_string(C) + "," + std::to_string(R) + "]");
            const value::Value A0(tA, in.memory(), in.offset() + mem);
            const value::Value A_in = A0.clone();
            const std::size_t b_off = 8 * R * C;
            value::Value outside = in.clone();  // memory beyond B must be untouched
            return Invariant(
                "B[j][i] == A[i][j], sum |diff| < 1e-9, A and the rest of memory untouched",
                [vm, tA, tB, A_in, R, C, mem, b_off, outside](const value::Value& v) {
                    const value::Value A(tA, v.memory(), v.offset() + mem);
                    const value::Value B(tB, v.memory(), v.offset() + mem + b_off);
                    double diff = 0;
                    for (std::size_t i = 0; i < R; ++i)
                        for (std::size_t j = 0; j < C; ++j)
                            diff += std::fabs(B.at({j, i}).get<double>() - A_in.at({i, j}).get<double>());
                    if (!(diff < 1e-9) || !(A == A_in)) return false;
                    for (std::size_t a = b_off + 8 * R * C; a < vm->memory_bytes(); ++a)
                        if (vm->mem(v, a) != vm->mem(outside, a)) return false;
                    return true;
                });
        };
        ContractSpec spec{train_m,
                          ex,
                          automaton::ProgramSearchOptions{.registers = 5, .max_length = 5, .max_states = 40'000'000,
                                                          .alphabet = alphabet},
                          R"(outer: addi r5 r5 0
                                    jz done
                                    mov r1 r6
                                    mov r2 r7
                             inner: addi r2 r2 0
                                    jz next
                                    ?? 5
                                    jmp inner
                             next:  addi r6 r6 8
                                    addi r5 r5 -1
                                    jmp outer
                             done:)",
                          test_m,
                          gen,
                          goal,
                          std::size_t{cfg.quick ? 20u : 100u},
                          20'000};
        Outcome out = run_contract(spec, cfg.seed);
        out.notes += "outer skeleton given, inner body synthesised over 12 instructions; held-out: random R,C in "
                     "[2,8] (non-square), checked through f64[R,C]/f64[C,R] views of machine memory; ";
        return out;
    }
};

}  // namespace

std::vector<ProblemPtr> tier2_problems() {
    return {std::make_shared<GcdLoop>(), std::make_shared<Popcount32Loop>(), std::make_shared<ArrayReverse>(),
            std::make_shared<MatrixTranspose>()};
}

}  // namespace alg_exp::experiments
