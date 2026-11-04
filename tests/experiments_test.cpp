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
