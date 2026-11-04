// Tier 3: hybrid quantum-classical synthesis and verification-only problems.
//   teleportation  - corrections synthesised after a fixed entangle/measure
//                    prefix, verified over every measurement branch (problem 8)
//   rpn_interpreter - hand-written stack-machine interpreter checked against a
//                    reference evaluator and per-step invariants (problem 6)

#include "combinatorial/automaton/Sketch.hpp"
#include "combinatorial/automaton/TransitionSpace.hpp"
#include "combinatorial/experiments/Experiment.hpp"
#include "combinatorial/quantum/Quantum.hpp"

#include <chrono>
#include <cmath>
#include <numbers>

namespace combinatorial::experiments {

namespace {

using Clock = std::chrono::steady_clock;
double since(Clock::time_point t) { return std::chrono::duration<double>(Clock::now() - t).count(); }

// ---------------------------------------------------------- teleportation ---

class Teleportation final : public Problem {
public:
    std::string name() const override { return "teleportation"; }
    std::string mode() const override { return "synthesis"; }
    std::string description() const override {
        return "Teleport q0 to q2: fixed Bell-pair + Bell-measurement prefix, synthesise up to 3 (conditional) "
               "correction ops; every measurement branch must give fidelity >= 1-1e-9";
    }

    Outcome run(const RunConfig& cfg) const override {
        using quantum::Gate;
        using quantum::HybridProgram;
        Outcome out;
        HybridProgram prefix(3, 2);
        prefix.gate({Gate::Kind::H, {1}})
            .gate({Gate::Kind::CNOT, {1, 2}})
            .gate({Gate::Kind::CNOT, {0, 1}})
            .gate({Gate::Kind::H, {0}})
            .measure(0, 0)
            .measure(1, 1);
        // Correction alphabet: Pauli X/Z (and H) on q2, unconditional or controlled by c0/c1.
        std::vector<HybridProgram> alphabet;
        for (auto k : {Gate::Kind::X, Gate::Kind::Z}) {
            for (std::size_t c : {0u, 1u}) {
                HybridProgram op(3, 2);
                op.conditional({k, {2}}, c);
                alphabet.push_back(op);
            }
        }
        for (auto k : {Gate::Kind::X, Gate::Kind::Z, Gate::Kind::H}) {
            HybridProgram op(3, 2);
            op.gate({k, {2}});
            alphabet.push_back(op);
        }

        Rng rng(cfg.seed);
        auto random_state = [](Rng& g) {
            std::uniform_real_distribution<double> u(0, 1);
            const double theta = std::acos(1 - 2 * u(g)), phi = 2 * std::numbers::pi * u(g);  // uniform on the sphere
            return std::pair<quantum::Complex, quantum::Complex>{std::cos(theta / 2),
                                                                 std::polar(std::sin(theta / 2), phi)};
        };
        std::vector<std::pair<quantum::Complex, quantum::Complex>> train;
        for (int i = 0; i < 3; ++i) train.push_back(random_state(rng));
        out.train_examples = train.size();

        // Fidelity of q2 with psi in every branch (exact branch enumeration).
        auto teleports = [](const HybridProgram& prog, quantum::Complex a, quantum::Complex b, double* total_p) {
            double total = 0;
            for (int m0 = 0; m0 < 2; ++m0)
                for (int m1 = 0; m1 < 2; ++m1) {
                    value::Value h = quantum::make_hybrid(3, 2);
                    const value::Value q = h.reference_member("qreg");
                    quantum::set_amplitude(q, 0, a);
                    quantum::set_amplitude(q, 1, b);
                    const double p = prog.run_branch(h, {m0, m1});
                    if (p <= 1e-12) continue;
                    total += p;
                    if (quantum::fidelity_1q(quantum::reduced_density(q, 2), a, b) < 1 - 1e-9) return false;
                }
            if (total_p) *total_p = total;
            return std::fabs(total - 1) < 1e-9;
        };

        const auto t0 = Clock::now();
        std::optional<HybridProgram> found;
        std::uint64_t candidates = 0;
        const std::size_t A = alphabet.size();
        for (std::size_t len = 0; len <= 3 && !found; ++len) {
            std::size_t combos = 1;
            for (std::size_t i = 0; i < len; ++i) combos *= A;
            for (std::size_t code = 0; code < combos && !found; ++code) {
                HybridProgram prog = prefix;
                for (std::size_t i = 0, c = code; i < len; ++i, c /= A) prog.append(alphabet[c % A]);
                ++candidates;
                bool ok = true;
                for (const auto& [a, b] : train) ok = ok && teleports(prog, a, b, nullptr);
                if (ok) found = prog;
            }
        }
        out.search_seconds = since(t0);
        out.search_states = candidates;
        out.solved = found.has_value();
        if (!found) {
            out.notes = "no correction of <= 3 ops";
            return out;
        }
        std::string corr;
        for (std::size_t i = prefix.ops().size(); i < found->ops().size(); ++i)
            corr += (corr.empty() ? "" : "; ") + found->ops()[i].to_string();
        out.solution = "corrections: " + (corr.empty() ? std::string("(none)") : corr);

        const auto t1 = Clock::now();
        Rng held(cfg.seed ^ 0xbeef);
        const double r = 1 / std::numbers::sqrt2;
        std::vector<std::pair<quantum::Complex, quantum::Complex>> tests{
            {1, 0}, {0, 1}, {r, r}, {r, quantum::Complex(0, r)}};  // |0>, |1>, |+>, |+i>
        const std::size_t n = cfg.quick ? 20 : 100;
        for (std::size_t i = 0; i < n; ++i) tests.push_back(random_state(held));
        HeldOut h;
        for (const auto& [a, b] : tests) {
            ++h.cases;
            double p = 0;
            if (teleports(*found, a, b, &p)) ++h.passed;
            else if (h.first_failure.empty()) h.first_failure = "state " + std::to_string(h.cases - 1) + " lost fidelity";
        }
        record_validation(out, h, since(t1));
        out.notes += "prefix fixed (H1, CX1,2, CX0,1, H0, measure q0->c0, q1->c1); all 4 branches enumerated per "
                     "state; held-out |0>,|1>,|+>,|+i> + " + std::to_string(n) + " Haar-random states; ";
        return out;
    }
};

// -------------------------------------------------------- RPN interpreter ---

enum RpnOp : std::uint8_t { kPush = 0, kAdd = 1, kSub = 2, kMul = 3 };

struct Rpn {
    std::vector<std::uint8_t> ops;
    std::vector<std::int64_t> args;
};

/// Random expression tree serialised to postfix; returns its value with
/// 64-bit wrap-around semantics (matching the machine).
std::uint64_t random_postfix(Rng& g, int depth, Rpn& out) {
    if (depth == 0 || g() % 3 == 0) {
        const std::int64_t v = static_cast<std::int64_t>(g() % 101) - 50;
        out.ops.push_back(kPush);
        out.args.push_back(v);
        return static_cast<std::uint64_t>(v);
    }
    const std::uint64_t a = random_postfix(g, depth - 1, out);
    const std::uint64_t b = random_postfix(g, depth - 1, out);
    const auto op = static_cast<RpnOp>(1 + g() % 3);
    out.ops.push_back(op);
    out.args.push_back(0);
    return op == kAdd ? a + b : op == kSub ? a - b : a * b;
}

constexpr std::size_t kMaxTape = 32, kArgs = 32, kStack = kArgs + 8 * kMaxTape;

const char* kInterpreter = R"(
        movi r0 0           # ip
loop:   cmp r0 r1           # while (ip < M)
        jn body
        jmp done
body:   ldb r3 r0 0         # op = ops[ip]
        movi r6 0
        cmp r3 r6
        jz push
        addi r2 r2 -8       # b = pop
        ld r5 r2 288
        addi r2 r2 -8       # a = pop
        ld r4 r2 288
        movi r6 1
        cmp r3 r6
        jz add
        movi r6 2
        cmp r3 r6
        jz sub
        mul r4 r4 r5
        jmp store
add:    add r4 r4 r5
        jmp store
sub:    sub r4 r4 r5
store:  st r4 r2 288        # push(a op b)
        addi r2 r2 8
        jmp next
push:   movi r6 3           # push(args[ip])
        shl r7 r0 r6
        ld r4 r7 32
        st r4 r2 288
        addi r2 r2 8
next:   addi r0 r0 1
        jmp loop
done:   halt)";

}  // namespace

automaton::Program rpn_reference_interpreter() { return automaton::assemble(kInterpreter); }

HeldOut verify_rpn_interpreter(const automaton::Program& prog, std::size_t cases, std::uint64_t seed,
                               std::uint64_t* steps_out, std::uint64_t* transitions_out) {
    static_assert(kStack == 288, "interpreter offsets");
    automaton::Machine m(1024);
    automaton::TransitionSpace ts(m);
    Rng rng(seed);
    std::uint64_t steps_total = 0, transitions_checked = 0;
    HeldOut h;
    {
        for (std::size_t k = 0; k < cases; ++k) {
            Rpn t;
            std::uint64_t expected = random_postfix(rng, 4, t);
            if (t.ops.size() > kMaxTape) {  // keep within the tape; regenerate shallower
                t = Rpn{};
                expected = random_postfix(rng, 3, t);
            }
            const std::size_t M = t.ops.size();
            auto s = m.initial_state();
            for (std::size_t i = 0; i < M; ++i) {
                m.set_mem(s, i, t.ops[i]);
                for (int b = 0; b < 8; ++b)
                    m.set_mem(s, kArgs + 8 * i + static_cast<std::size_t>(b),
                              static_cast<std::uint8_t>(static_cast<std::uint64_t>(t.args[i]) >> (8 * b)));
            }
            m.set_reg(s, 1, static_cast<std::int64_t>(M));
            ++h.cases;
            std::string why;
            for (std::uint64_t step = 0; step < 100'000 && m.halted(s) == automaton::HaltReason::Running; ++step) {
                const auto pc = m.pc(s);
                const bool sample = step % 25 == 0 && pc < prog.size();
                value::Value before = sample ? s.clone() : s;
                m.step(s, prog);
                ++steps_total;
                if (sample) {
                    value::Value tr = ts.make(before, prog[pc]);
                    tr.reference_member("after").copy_from(s);
                    ++transitions_checked;
                    if (!ts.contains(tr)) {
                        why = "transition at step " + std::to_string(step) + " violates the step invariants";
                        break;
                    }
                }
                const std::int64_t sp = m.reg(s, 2);
                if (sp < 0 || sp > static_cast<std::int64_t>(8 * M) || sp % 8 != 0) {
                    why = "sp invariant violated at step " + std::to_string(step) + " (sp=" + std::to_string(sp) + ")";
                    break;
                }
            }
            if (why.empty() && m.halted(s) != automaton::HaltReason::Halt) why = "did not halt cleanly";
            if (why.empty() && m.reg(s, 2) != 8) why = "final sp " + std::to_string(m.reg(s, 2) / 8) + " != 1";
            if (why.empty()) {
                std::uint64_t got = 0;
                for (int b = 7; b >= 0; --b) got = (got << 8) | m.mem(s, kStack + static_cast<std::size_t>(b));
                if (got != expected) why = "stack[0] mismatch";
            }
            if (why.empty()) ++h.passed;
            else if (h.first_failure.empty()) h.first_failure = "program " + std::to_string(k) + ": " + why;
        }
    }
    if (steps_out) *steps_out = steps_total;
    if (transitions_out) *transitions_out = transitions_checked;
    return h;
}

namespace {

class RpnInterpreter final : public Problem {
public:
    std::string name() const override { return "rpn_interpreter"; }
    std::string mode() const override { return "verification"; }
    std::string description() const override {
        return "Verify a hand-written stack-machine interpreter on random postfix programs: 0 <= sp <= 8M at every "
               "step, sampled transitions satisfy the step invariants, final sp == 1 and stack[0] == reference";
    }
    Outcome run(const RunConfig& cfg) const override {
        Outcome out;
        const automaton::Program prog = rpn_reference_interpreter();
        out.solved = true;
        out.solution = "hand-written interpreter, " + std::to_string(prog.size()) + " instructions";
        const std::size_t cases = cfg.quick ? 60 : 300;
        std::uint64_t steps = 0, transitions = 0;
        const auto t0 = Clock::now();
        const HeldOut h = verify_rpn_interpreter(prog, cases, cfg.seed, &steps, &transitions);
        record_validation(out, h, since(t0));
        out.notes += std::to_string(cases) + " random postfix programs (depth <= 4, <= 32 ops), " +
                     std::to_string(steps) + " steps, " + std::to_string(transitions) +
                     " transitions checked against TransitionSpace; not synthesised (beyond enumerative search); ";
        return out;
    }
};

}  // namespace

std::vector<ProblemPtr> tier3_problems() {
    return {std::make_shared<Teleportation>(), std::make_shared<RpnInterpreter>()};
}

}  // namespace combinatorial::experiments
