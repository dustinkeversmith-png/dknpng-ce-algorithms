#include "alg_exp/automaton.hpp"
#include "alg_exp/experiments/Experiment.hpp"
#include "alg_exp/quantum/Quantum.hpp"

#include <cmath>

#include <gtest/gtest.h>

#include <set>

using namespace alg_exp;

// ------------------------------------------------------- assembler labels ---

TEST(AssemblerLabels, ResolveToInstructionIndices) {
    using namespace automaton;
    Program p = assemble(R"(
        start: movi r0 3
        loop:  addi r0 r0 -1
               jnz loop        # back edge
               jmp end
        dead:  movi r1 99
        end:
    )");
    ASSERT_EQ(p.size(), 5u);
    EXPECT_EQ(p[2].imm, 1);  // loop
    EXPECT_EQ(p[3].imm, 5);  // end == one past the last instruction
    Machine m(64);
    auto s = m.initial_state();
    m.run(s, p);
    EXPECT_EQ(m.reg(s, 0), 0);
    EXPECT_EQ(m.reg(s, 1), 0);  // skipped
    EXPECT_EQ(m.halted(s), HaltReason::EndOfProgram);
    EXPECT_EQ(assemble("a: b: jmp b")[0].imm, 0);  // stacked labels
    EXPECT_THROW(assemble("x: nop; x: nop"), std::invalid_argument);
    EXPECT_THROW(assemble("jmp nowhere"), std::invalid_argument);
    EXPECT_THROW(assemble("9bad: nop"), std::invalid_argument);
    EXPECT_THROW(assemble("l: jmp l extra"), std::invalid_argument);
}

TEST(ExplicitAlphabet, ReplacesGeneratedAlphabet) {
    using namespace automaton;
    Machine m(64);
    auto s = m.initial_state();
    m.set_reg(s, 0, 4);
    ProgramSearchOptions o;
    o.alphabet = assemble("add r1 r0 r0; mul r1 r1 r1; mov r2 r1");
    EXPECT_EQ(make_alphabet(o).size(), 3u);
    ProgramSynthesizer ps(m, {{s, problem::Invariant(problem::var("regs[1]") == 64)}}, o);
    auto p = ps.synthesize();
    ASSERT_TRUE(p);
    EXPECT_EQ(to_string(*p), "add r1 r0 r0; mul r1 r1 r1");
    o.alphabet = assemble("jmp 0");
    EXPECT_THROW(make_alphabet(o), std::invalid_argument);
}

// ---------------------------------------------------------------- sketches ---

TEST(SketchTest, LabelsResolveAroundVariableLengthHoles) {
    using namespace automaton;
    Sketch s = Sketch::parse(R"(
        top:  addi r1 r1 0
              jz done
        body: ?? 3
              jmp top
        done: halt)");
    ASSERT_EQ(s.holes(), 1u);
    EXPECT_EQ(s.hole_max(0), 3u);
    for (std::size_t n = 0; n <= 3; ++n) {
        std::vector<Instruction> fill(n, Instruction{Op::Nop, 0, 0, 0, 0});
        Program p = s.instantiate({fill});
        ASSERT_EQ(p.size(), 4 + n);
        EXPECT_EQ(p[1].imm, static_cast<std::int32_t>(3 + n));  // jz done
        EXPECT_EQ(p[2 + n].imm, 0);                              // jmp top
    }
    EXPECT_THROW(s.instantiate({std::vector<Instruction>(4)}), std::invalid_argument);
    EXPECT_THROW(s.instantiate({}), std::invalid_argument);
    EXPECT_THROW(Sketch::parse("jmp nowhere"), std::invalid_argument);
    EXPECT_THROW(Sketch::parse("a: nop; a: nop"), std::invalid_argument);
    EXPECT_THROW(Sketch::parse("?? "), std::invalid_argument);
    EXPECT_NE(s.to_string().find("?? 3"), std::string::npos);
}

TEST(SketchTest, DeadWritePruningIsSound) {
    using namespace automaton;
    // Exhaustively over a small alphabet: whenever a pair is declared dead,
    // dropping the first instruction leaves every reachable state unchanged.
    Machine m(64);
    ProgramSearchOptions o{.ops = {Op::Mov, Op::Add, Op::Addi, Op::Movi, Op::Ld, Op::St, Op::Cmp},
                           .immediates = {0, 8},
                           .registers = 3};
    const auto alphabet = make_alphabet(o);
    std::mt19937_64 rng(4);
    std::size_t dead = 0;
    for (const auto& a : alphabet)
        for (const auto& b : alphabet) {
            if (!is_dead_write_pair(a, b)) continue;
            ++dead;
            for (int trial = 0; trial < 3; ++trial) {
                auto s = m.initial_state();
                for (std::size_t r = 0; r < 3; ++r) m.set_reg(s, r, static_cast<std::int64_t>(rng() % 7) * 8);
                auto t = s.clone();
                m.execute(s, a);
                m.execute(s, b);
                m.execute(t, b);
                // pc differs by one instruction; compare everything else
                m.set_pc(s, 0);
                m.set_pc(t, 0);
                EXPECT_EQ(s, t) << a.to_string() << " ; " << b.to_string();
            }
        }
    EXPECT_GT(dead, 0u);
    EXPECT_FALSE(is_dead_write_pair({Op::Addi, 0, 0, 0, 1}, {Op::Mov, 0, 1, 0, 0}));  // flags of addi stay live
    EXPECT_FALSE(is_dead_write_pair({Op::Mov, 0, 1, 0, 0}, {Op::Add, 0, 0, 1, 0}));   // next reads it
    EXPECT_TRUE(is_dead_write_pair({Op::Mov, 0, 1, 0, 0}, {Op::Movi, 0, 0, 0, 5}));
}

TEST(SketchTest, SynthesizesCountdownLoop) {
    using namespace automaton;
    Machine m(64);
    std::vector<Example> ex;
    for (std::int64_t n : {3, 5, 9}) {
        auto s = m.initial_state();
        m.set_reg(s, 0, n);
        ex.push_back({s, problem::Invariant(problem::var("regs[1]") == static_cast<double>(2 * n))});
    }
    SketchOptions o;
    o.alphabets[""] = ProgramSearchOptions{.ops = {Op::Addi}, .immediates = {1, 2, -1}, .registers = 2};
    SketchSynthesizer ss(m, Sketch::parse("top: addi r0 r0 0; jz done; ?? 2; jmp top; done:"), ex, o);
    auto p = ss.synthesize();
    ASSERT_TRUE(p);
    EXPECT_EQ(ss.fills()[0].size(), 2u);
    EXPECT_TRUE(ss.verify(*p));
    auto s = m.initial_state();
    m.set_reg(s, 0, 40);
    m.run(s, *p);
    EXPECT_EQ(m.reg(s, 1), 80);
    EXPECT_GT(ss.stats().candidates, 0u);
    // unknown alphabet name and impossible bound
    EXPECT_THROW(SketchSynthesizer(m, Sketch::parse("?? 1 nope"), ex, o), std::invalid_argument);
    SketchSynthesizer none(m, Sketch::parse("?? 1"), ex, o);
    EXPECT_FALSE(none.synthesize());
}

// ---------------------------------------------------- hybrid quantum ops ---

namespace {
quantum::HybridProgram teleport_prefix() {
    using quantum::Gate;
    quantum::HybridProgram p(3, 2);
    p.gate({Gate::Kind::H, {1}})
        .gate({Gate::Kind::CNOT, {1, 2}})
        .gate({Gate::Kind::CNOT, {0, 1}})
        .gate({Gate::Kind::H, {0}})
        .measure(0, 0)
        .measure(1, 1);
    return p;
}

/// Minimum q2 fidelity over all branches for psi = (a, b).
double worst_teleport_fidelity(const quantum::HybridProgram& p, quantum::Complex a, quantum::Complex b) {
    double worst = 1;
    for (int m0 = 0; m0 < 2; ++m0)
        for (int m1 = 0; m1 < 2; ++m1) {
            auto h = quantum::make_hybrid(3, 2);
            const auto q = h.reference_member("qreg");
            quantum::set_amplitude(q, 0, a);
            quantum::set_amplitude(q, 1, b);
            if (p.run_branch(h, {m0, m1}) <= 1e-12) continue;
            worst = std::min(worst, quantum::fidelity_1q(quantum::reduced_density(q, 2), a, b));
        }
    return worst;
}
}  // namespace

TEST(HybridQuantum, ForcedMeasurementAndReducedDensity) {
    using quantum::Gate;
    auto psi = quantum::make_state(2);
    quantum::hadamard(psi, 0);
    auto rho = quantum::reduced_density(psi, 0);
    EXPECT_NEAR(rho[0].real(), 0.5, 1e-12);
    EXPECT_NEAR(rho[1].real(), 0.5, 1e-12);  // coherent superposition
    EXPECT_NEAR(quantum::fidelity_1q(rho, 1 / std::sqrt(2.0), 1 / std::sqrt(2.0)), 1, 1e-12);
    auto c = psi.clone();
    EXPECT_NEAR(quantum::measure_forced(c, 0, 1), 0.5, 1e-12);
    EXPECT_NEAR(std::abs(quantum::amplitude(c, 1)), 1, 1e-12);
    auto z = quantum::make_state(1);
    EXPECT_NEAR(quantum::measure_forced(z, 0, 1), 0, 1e-12);  // impossible branch
    EXPECT_THROW(quantum::measure_forced(z, 0, 2), std::invalid_argument);
    // Bell pair: each qubit alone is maximally mixed
    quantum::Circuit bell(2);
    bell.h(0).cnot(0, 1);
    auto b = bell.run();
    auto rb = quantum::reduced_density(b, 1);
    EXPECT_NEAR(rb[0].real(), 0.5, 1e-12);
    EXPECT_NEAR(std::abs(rb[1]), 0, 1e-12);
}

TEST(HybridQuantum, BranchProbabilitiesAndClassicalBits) {
    using quantum::Gate;
    auto prog = teleport_prefix();
    EXPECT_EQ(prog.measurement_count(), 2u);
    double total = 0;
    for (int m0 = 0; m0 < 2; ++m0)
        for (int m1 = 0; m1 < 2; ++m1) {
            auto h = quantum::make_hybrid(3, 2);
            quantum::set_amplitude(h.reference_member("qreg"), 0, 0.6);
            quantum::set_amplitude(h.reference_member("qreg"), 1, 0.8);
            const double p = prog.run_branch(h, {m0, m1});
            EXPECT_NEAR(p, 0.25, 1e-12);
            total += p;
            EXPECT_EQ(h.reference("classical_bits[0]").get<std::uint8_t>(), m0);
            EXPECT_EQ(h.reference("classical_bits[1]").get<std::uint8_t>(), m1);
            EXPECT_NEAR(quantum::norm_squared(h.reference_member("qreg")), 1, 1e-12);
        }
    EXPECT_NEAR(total, 1, 1e-12);
    // random-outcome run records bits consistent with the collapsed state
    std::mt19937_64 rng(3);
    for (int i = 0; i < 20; ++i) {
        auto h = quantum::make_hybrid(3, 2);
        quantum::set_amplitude(h.reference_member("qreg"), 0, 1);
        prog.run(h, rng);
        const auto q = h.reference_member("qreg");
        const int c0 = h.reference("classical_bits[0]").get<std::uint8_t>();
        EXPECT_NEAR(quantum::probability_one(q, 0), c0, 1e-12);
    }
    EXPECT_THROW(prog.run_branch(quantum::make_hybrid(3, 2), {0}), std::invalid_argument);
    EXPECT_THROW(prog.run_branch(quantum::make_hybrid(2, 2), {0, 0}), std::invalid_argument);
    EXPECT_THROW(quantum::HybridProgram(3, 2).measure(0, 2), std::out_of_range);
}

TEST(HybridQuantum, OnlyCorrectCorrectionsTeleport) {
    using quantum::Gate;
    const quantum::Complex a(0.6, 0), b(0, 0.8);
    auto good = teleport_prefix();
    good.conditional({Gate::Kind::Z, {2}}, 0).conditional({Gate::Kind::X, {2}}, 1);
    EXPECT_NEAR(worst_teleport_fidelity(good, a, b), 1, 1e-9);
    auto swapped = teleport_prefix();  // X and Z controls swapped
    swapped.conditional({Gate::Kind::X, {2}}, 0).conditional({Gate::Kind::Z, {2}}, 1);
    // Wrong corrections can still overlap with psi (0.92 here), but must fall
    // clearly below the verifier's 1 - 1e-9 threshold.
    EXPECT_LT(worst_teleport_fidelity(swapped, a, b), 1 - 1e-3);
    EXPECT_LT(worst_teleport_fidelity(teleport_prefix(), a, b), 1 - 1e-3);  // no correction
}

// ---------------------------------------------- verifier must catch mutants ---

TEST(RpnVerifier, AcceptsReferenceAndRejectsMutants) {
    using namespace automaton;
    const Program ref = experiments::rpn_reference_interpreter();
    auto h = experiments::verify_rpn_interpreter(ref, 80, 9);
    EXPECT_EQ(h.passed, h.cases) << h.first_failure;
    auto mutate = [&](auto pred, auto edit) {
        Program p = ref;
        for (auto& ins : p)
            if (pred(ins)) {
                edit(ins);
                return p;
            }
        ADD_FAILURE() << "mutation site not found";
        return p;
    };
    const std::vector<std::pair<std::string, Program>> mutants = {
        {"sub operands swapped", mutate([](const Instruction& i) { return i.op == Op::Sub; },
                                        [](Instruction& i) { std::swap(i.b, i.c); })},
        {"mul -> add", mutate([](const Instruction& i) { return i.op == Op::Mul; },
                              [](Instruction& i) { i.op = Op::Add; })},
        {"push stride 16", mutate([](const Instruction& i) { return i.op == Op::Addi && i.a == 2 && i.imm == 8; },
                                  [](Instruction& i) { i.imm = 16; })},
        {"args read one slot late", mutate([](const Instruction& i) { return i.op == Op::Ld && i.imm == 32; },
                                           [](Instruction& i) { i.imm = 40; })},
    };
    for (const auto& [what, p] : mutants) {
        auto r = experiments::verify_rpn_interpreter(p, 80, 9);
        EXPECT_LT(r.passed, r.cases) << what << " was not detected";
        EXPECT_FALSE(r.first_failure.empty()) << what;
    }
}

// ------------------------------------------------------- problem catalog ---

TEST(ExperimentCatalog, NamesAreUniqueAndDescribed) {
    auto all = experiments::catalog();
    ASSERT_FALSE(all.empty());
    std::set<std::string> names;
    for (const auto& p : all) {
        EXPECT_TRUE(names.insert(p->name()).second) << p->name();
        EXPECT_FALSE(p->description().empty());
        EXPECT_FALSE(p->mode().empty());
        ASSERT_NE(experiments::find_problem(p->name()), nullptr);
        EXPECT_EQ(experiments::find_problem(p->name())->name(), p->name());
    }
    EXPECT_EQ(experiments::find_problem("no_such_problem"), nullptr);
}

class CatalogProblem : public ::testing::TestWithParam<std::string> {};

TEST_P(CatalogProblem, SolvesAndGeneralises) {
    auto p = experiments::find_problem(GetParam());
    ASSERT_NE(p, nullptr);
    experiments::RunConfig cfg;
    cfg.quick = true;
    cfg.seed = 3;
    const auto o = p->run(cfg);
    EXPECT_TRUE(o.solved) << o.notes;
    EXPECT_TRUE(o.verified) << o.test_passed << "/" << o.test_cases << " " << o.notes;
    EXPECT_GT(o.test_cases, 0u);
    EXPECT_FALSE(o.solution.empty());
    const std::string report = experiments::format_report({{p, o}});
    EXPECT_NE(report.find(p->name()), std::string::npos);
}

namespace {
std::vector<std::string> catalog_names() {
    std::vector<std::string> out;
    for (const auto& p : experiments::catalog()) out.push_back(p->name());
    return out;
}
}  // namespace

INSTANTIATE_TEST_SUITE_P(All, CatalogProblem, ::testing::ValuesIn(catalog_names()),
                         [](const auto& info) { return info.param; });
