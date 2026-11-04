// Tier 1: problems solved by straight-line search today.
//   bell_state      - quantum circuit synthesis (matrix problem 7)
//   rule110         - cellular automaton rule, synthesised per cell and lifted
//                     to a bit-parallel tape program (matrix problem 5)
//   popcount3       - straight-line popcount on 3-bit inputs (problem 4, part 1)

#include "alg_exp/experiments/Experiment.hpp"
#include "alg_exp/quantum/Quantum.hpp"
#include "alg_exp/value/TypeRegistry.hpp"

#include <bit>
#include <chrono>
#include <cmath>
#include <numbers>

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

// ------------------------------------------------------------ Bell state ---

class BellState final : public Problem {
public:
    std::string name() const override { return "bell_state"; }
    std::string mode() const override { return "synthesis"; }
    std::string description() const override {
        return "Shortest circuit (<= 4 gates) mapping |00> to (|00>+|11>)/sqrt2";
    }
    Outcome run(const RunConfig&) const override {
        using quantum::Gate;
        Outcome out;
        value::Value bell = quantum::make_state(2);
        const double r = 1 / std::numbers::sqrt2;
        quantum::set_amplitude(bell, 0, r);
        quantum::set_amplitude(bell, 3, r);
        const std::vector<Gate> alphabet{{Gate::Kind::H, {0}},       {Gate::Kind::H, {1}},       {Gate::Kind::X, {0}},
                                         {Gate::Kind::X, {1}},       {Gate::Kind::Z, {0}},       {Gate::Kind::S, {1}},
                                         {Gate::Kind::T, {0}},       {Gate::Kind::CNOT, {0, 1}}, {Gate::Kind::CNOT, {1, 0}},
                                         {Gate::Kind::CZ, {0, 1}}};
        const auto t0 = Clock::now();
        std::uint64_t explored = 0;
        auto c = quantum::synthesize_circuit(bell, alphabet, 4, 1e-9, &explored);
        out.search_seconds = since(t0);
        out.search_states = explored;
        out.train_examples = 1;
        out.solved = c.has_value();
        if (!c) {
            out.notes = "no circuit within 4 gates";
            return out;
        }
        out.solution = c->to_string();
        const auto t1 = Clock::now();
        value::Value psi = c->run();
        const bool checks[] = {c->size() <= 4,                                                   // structural
                               quantum::normalization_invariant(1e-9).holds(psi),                // normalisation
                               quantum::fidelity(psi, bell) >= 1.0 - 1e-7,                       // fidelity
                               std::fabs(quantum::linear_entropy(psi, 0b01) - 0.5) < 1e-9};      // maximal entanglement
        out.test_cases = 4;
        for (bool ok : checks) out.test_passed += ok;
        out.verify_seconds = since(t1);
        out.verified = out.test_passed == out.test_cases;
        out.notes = "checks: gates<=4, normalised, fidelity>=1-1e-7, linear entropy 0.5";
        return out;
    }
};

// --------------------------------------------------------------- Rule 110 ---

int rule110(int l, int c, int r) { return (110 >> ((l << 2) | (c << 1) | r)) & 1; }

/// Reference: one step on a zero-bounded tape.
std::vector<std::uint8_t> rule110_step(const std::vector<std::uint8_t>& t) {
    std::vector<std::uint8_t> n(t.size());
    for (std::size_t i = 0; i < t.size(); ++i) {
        const int l = i > 0 ? t[i - 1] : 0, r = i + 1 < t.size() ? t[i + 1] : 0;
        n[i] = static_cast<std::uint8_t>(rule110(l, t[i], r));
    }
    return n;
}

class Rule110 final : public Problem {
public:
    std::string name() const override { return "rule110"; }
    std::string mode() const override { return "synthesis"; }
    std::string description() const override {
        return "Synthesise the Rule 110 cell update from its 8 neighbourhoods (bitwise ops only), lift it to a "
               "64-cell bit-parallel tape program, and simulate random tapes";
    }
    Outcome run(const RunConfig& cfg) const override {
        Outcome out;
        Machine m(64);
        // Local rule: l, c, r in r0, r1, r2 -> r3.
        std::vector<automaton::Example> ex;
        for (int code = 0; code < 8; ++code) {
            value::Value s = m.initial_state();
            const int l = code >> 2 & 1, c = code >> 1 & 1, r = code & 1;
            m.set_reg(s, 0, l);
            m.set_reg(s, 1, c);
            m.set_reg(s, 2, r);
            ex.push_back({s, reg_is(3, rule110(l, c, r))});
        }
        out.train_examples = ex.size();
        automaton::ProgramSearchOptions o;
        o.ops = {Op::And, Op::Or, Op::Xor, Op::Not, Op::Mov};  // bitwise only: makes the bit-parallel lift sound
        o.registers = 5;
        o.max_length = 5;
        automaton::ProgramSynthesizer ps(m, ex, o);
        auto local = ps.synthesize();
        record_search(out, ps, local);
        if (!local) return out;

        // Lift: tape in r1, mask in r6. L = x<<1 (bit i = cell i-1), R = x>>1.
        Program tape = automaton::assemble("movi r7 1; shl r0 r1 r7; shr r2 r1 r7");
        tape.insert(tape.end(), local->begin(), local->end());
        tape.push_back({Op::And, 1, 3, 6, 0});
        out.solution = "cell: " + automaton::to_string(*local) + "  | tape program: " +
                       std::to_string(tape.size()) + " instructions";

        // Held-out: random tapes, many steps, compared cell by cell. The tape
        // also lives in a u8[W] Value so the reference works on the layout.
        const auto t1 = Clock::now();
        Rng rng(cfg.seed);
        const std::size_t tapes = cfg.quick ? 4 : 16, steps = cfg.quick ? 500 : 10000;
        value::TypeRegistry reg;
        HeldOut h;
        for (std::size_t k = 0; k < tapes; ++k) {
            const std::size_t W = 16 + rng() % 49;  // [16, 64]
            value::Value cells(reg.get("u8[" + std::to_string(W) + "]"), "tape");
            for (std::size_t i = 0; i < W; ++i) cells.set_scalar(i, (rng() % 10) < 3 ? 1 : 0);
            std::vector<std::uint8_t> ref = cells.to_vector<std::uint8_t>();
            std::uint64_t bits = 0;
            for (std::size_t i = 0; i < W; ++i) bits |= std::uint64_t{ref[i]} << i;
            value::Value s = m.initial_state();
            m.set_reg(s, 1, static_cast<std::int64_t>(bits));
            m.set_reg(s, 6, W == 64 ? -1 : static_cast<std::int64_t>((std::uint64_t{1} << W) - 1));
            ++h.cases;
            bool ok = true;
            for (std::size_t t = 0; t < steps && ok; ++t) {
                m.set_pc(s, 0);
                s.reference("halted").set<std::uint8_t>(0);
                m.run(s, tape, tape.size() + 1);
                ref = rule110_step(ref);
                const auto got = static_cast<std::uint64_t>(m.reg(s, 1));
                for (std::size_t i = 0; i < W; ++i)
                    if (((got >> i) & 1) != ref[i]) {
                        ok = false;
                        if (h.first_failure.empty())
                            h.first_failure = "tape " + std::to_string(k) + " (W=" + std::to_string(W) +
                                              ") diverged at step " + std::to_string(t) + " cell " + std::to_string(i);
                        break;
                    }
            }
            h.passed += ok;
        }
        record_validation(out, h, since(t1));
        out.notes += std::to_string(tapes) + " random tapes x " + std::to_string(steps) + " steps; ";
        return out;
    }
};

// ------------------------------------------------------------- popcount3 ---

class Popcount3 final : public Problem {
public:
    std::string name() const override { return "popcount3"; }
    std::string mode() const override { return "synthesis"; }
    std::string description() const override {
        return "Straight-line popcount of a 3-bit input (all 8 inputs); shows the straight-line limit";
    }
    Outcome run(const RunConfig&) const override {
        Outcome out;
        Machine m(64);
        auto gen_all = [&](std::int64_t x) {
            value::Value s = m.initial_state();
            m.set_reg(s, 0, x);
            return s;
        };
        std::vector<automaton::Example> ex;
        for (std::int64_t x = 0; x < 8; ++x) ex.push_back({gen_all(x), reg_is(1, std::popcount(std::uint64_t(x)))});
        out.train_examples = ex.size();
        automaton::ProgramSearchOptions o;
        o.ops = {Op::Movi, Op::Shr, Op::Sub, Op::And, Op::Add};
        o.immediates = {1};
        o.registers = 4;
        o.max_length = 5;
        o.max_states = 60'000'000;
        automaton::ProgramSynthesizer ps(m, ex, o);
        auto p = ps.synthesize();
        record_search(out, ps, p);
        if (!p) return out;
        const auto t1 = Clock::now();
        HeldOut h;
        for (std::int64_t x = 0; x < 8; ++x) {
            value::Value s = gen_all(x);
            m.run(s, *p, p->size() + 1);
            ++h.cases;
            h.passed += m.reg(s, 1) == std::popcount(std::uint64_t(x));
        }
        record_validation(out, h, since(t1));
        // Straight-line code is specific to the width it was trained on.
        std::size_t wide_ok = 0;
        Rng rng(5);
        for (int k = 0; k < 32; ++k) {
            const std::uint32_t x = static_cast<std::uint32_t>(rng());
            value::Value s = gen_all(x);
            m.run(s, *p, p->size() + 1);
            wide_ok += m.reg(s, 1) == std::popcount(x);
        }
        out.notes += "exhaustive on 3-bit inputs; correct on " + std::to_string(wide_ok) +
                     "/32 random 32-bit inputs (needs the loop version, see popcount32_loop); ";
        return out;
    }
};

}  // namespace

std::vector<ProblemPtr> tier1_problems() {
    return {std::make_shared<BellState>(), std::make_shared<Rule110>(), std::make_shared<Popcount3>()};
}

}  // namespace alg_exp::experiments
