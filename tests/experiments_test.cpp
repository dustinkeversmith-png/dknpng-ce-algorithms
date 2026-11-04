#include "combinatorial/automaton.hpp"
#include "combinatorial/experiments/Experiment.hpp"

#include <gtest/gtest.h>

#include <set>

using namespace combinatorial;

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
