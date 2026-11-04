#include "alg_exp/automaton.hpp"
#include "alg_exp/problem.hpp"

#include <gtest/gtest.h>

#include <limits>

using namespace alg_exp;
using namespace alg_exp::automaton;
using problem::Invariant;
using problem::var;
using value::Value;

namespace {
struct AutomatonTest : ::testing::Test {
    Machine m{64};
    Value run(const std::string& src, std::uint64_t max = 10000) {
        Value s = m.initial_state();
        m.run(s, assemble(src), max);
        return s;
    }
};
}  // namespace

TEST_F(AutomatonTest, StateIsAValueLayout) {
    const auto& t = *m.state_type();
    EXPECT_EQ(t.byte_size(), 4u + 4 + 1 + 1 + 64 + 64);
    Value s = m.initial_state();
    EXPECT_EQ(m.sp(s), 64u);
    m.set_reg(s, 3, -42);
    EXPECT_EQ(s.reference("regs[3]").get<std::int64_t>(), -42);  // same bytes, via path
    s.reference("mem[5]").set<std::uint8_t>(9);
    EXPECT_EQ(m.mem(s, 5), 9);
    EXPECT_EQ(m.instruction_type()->byte_size(), 8u);
}

TEST_F(AutomatonTest, AssemblerRoundTrip) {
    Program p = assemble("movi r0, 5\nadd r1 r0 r0 # double\n; addi r2 r1 -3; jz 7; halt");
    ASSERT_EQ(p.size(), 5u);
    EXPECT_EQ(to_string(p), "movi r0 5; add r1 r0 r0; addi r2 r1 -3; jz 7; halt");
    EXPECT_EQ(assemble(to_string(p)), p);
    EXPECT_THROW(assemble("frob r0"), std::invalid_argument);
    EXPECT_THROW(assemble("add r0 r1"), std::invalid_argument);
    EXPECT_THROW(assemble("mov r0 r9"), std::invalid_argument);
    EXPECT_THROW(assemble("movi r0 99999999999"), std::invalid_argument);
    EXPECT_THROW(assemble("halt r0"), std::invalid_argument);
}

TEST_F(AutomatonTest, InstructionEncodingThroughValues) {
    Program p = assemble("movi r7 -123456; xor r1 r2 r3; ret");
    Value enc = m.encode(p);
    EXPECT_EQ(enc.type()->shape(), (std::vector<std::size_t>{3}));
    EXPECT_EQ(enc.reference("[0].imm").get<std::int32_t>(), -123456);
    EXPECT_EQ(m.decode_program(enc), p);
}

TEST_F(AutomatonTest, ArithmeticAndFlags) {
    Value s = run("movi r0 7; movi r1 3; sub r2 r0 r1; mul r3 r2 r2; div r4 r3 r1; mod r5 r3 r1; neg r6 r0; halt");
    EXPECT_EQ(m.reg(s, 2), 4);
    EXPECT_EQ(m.reg(s, 3), 16);
    EXPECT_EQ(m.reg(s, 4), 5);
    EXPECT_EQ(m.reg(s, 5), 1);
    EXPECT_EQ(m.reg(s, 6), -7);
    EXPECT_EQ(m.halted(s), HaltReason::Halt);
    EXPECT_TRUE(m.flags(s) & flag::N);

    Value c = run("movi r0 -1; movi r1 1; add r2 r0 r1; halt");  // unsigned carry, zero
    EXPECT_TRUE(m.flags(c) & flag::Z);
    EXPECT_TRUE(m.flags(c) & flag::C);
    EXPECT_FALSE(m.flags(c) & flag::V);

    Value o = m.initial_state();  // signed overflow
    m.set_reg(o, 0, std::numeric_limits<std::int64_t>::max());
    m.set_reg(o, 1, 1);
    m.execute(o, {Op::Add, 2, 0, 1, 0});
    EXPECT_EQ(m.reg(o, 2), std::numeric_limits<std::int64_t>::min());
    EXPECT_TRUE(m.flags(o) & flag::V);
    EXPECT_TRUE(m.flags(o) & flag::N);
}

TEST_F(AutomatonTest, BitwiseAndShifts) {
    Value s = run("movi r0 12; movi r1 10; and r2 r0 r1; or r3 r0 r1; xor r4 r0 r1; movi r5 2; shl r6 r0 r5; shr r7 r0 r5");
    EXPECT_EQ(m.reg(s, 2), 8);
    EXPECT_EQ(m.reg(s, 3), 14);
    EXPECT_EQ(m.reg(s, 4), 6);
    EXPECT_EQ(m.reg(s, 6), 48);
    EXPECT_EQ(m.reg(s, 7), 3);
    EXPECT_EQ(m.halted(s), HaltReason::EndOfProgram);
}

TEST_F(AutomatonTest, LoopComputesFactorial) {
    // r0 = n, r1 = acc; while (n != 0) { acc *= n; n -= 1 }
    Value s = run(R"(movi r0 10
                     movi r1 1
                     movi r2 0
                     cmp r0 r2
                     jz 8
                     mul r1 r1 r0
                     addi r0 r0 -1
                     jmp 3
                     halt)");
    EXPECT_EQ(m.reg(s, 1), 3628800);
    EXPECT_EQ(m.halted(s), HaltReason::Halt);
}

TEST_F(AutomatonTest, MemoryStackAndCalls) {
    Value s = run(R"(movi r0 1000
                     movi r1 8
                     st r0 r1 4
                     ld r2 r1 4
                     stb r0 r1 0
                     ldb r3 r1 0
                     push r2
                     call 10
                     pop r5
                     halt
                     movi r6 77
                     ret)");
    EXPECT_EQ(m.reg(s, 2), 1000);
    EXPECT_EQ(m.reg(s, 3), 1000 & 0xff);
    EXPECT_EQ(m.mem(s, 12), 1000 & 0xff);  // little-endian word at 8+4
    EXPECT_EQ(m.mem(s, 13), 1000 >> 8);
    EXPECT_EQ(m.reg(s, 6), 77);
    EXPECT_EQ(m.reg(s, 5), 1000);
    EXPECT_EQ(m.sp(s), 64u);
    EXPECT_EQ(m.halted(s), HaltReason::Halt);
}

TEST_F(AutomatonTest, FaultsHalt) {
    for (const char* src : {"movi r1 0; div r0 r0 r1", "movi r1 60; ld r0 r1 0", "movi r1 -1; ldb r0 r1 0", "pop r0",
                            "ret"}) {
        Value s = run(src);
        EXPECT_EQ(m.halted(s), HaltReason::Fault) << src;
        EXPECT_TRUE(m.flags(s) & flag::F) << src;
    }
    Value s = m.initial_state();
    m.execute(s, {Op::Add, 9, 0, 0, 0});
    EXPECT_EQ(m.halted(s), HaltReason::Fault);
    Value t = m.initial_state();
    m.execute(t, {static_cast<Op>(200), 0, 0, 0, 0});
    EXPECT_EQ(m.halted(t), HaltReason::Fault);
    // stack overflow: 64 bytes of memory hold 8 words
    Value u = m.initial_state();
    RunResult r = m.run(u, assemble("push r0; jmp 0"), 1000);
    EXPECT_EQ(r.reason, HaltReason::Fault);
    EXPECT_EQ(r.steps, 17u);
    // a halted machine does not move
    Value h = run("halt");
    Value before = h.clone();
    m.execute(h, {Op::Movi, 0, 0, 0, 5});
    EXPECT_EQ(h, before);
}

TEST_F(AutomatonTest, RunRespectsStepBudget) {
    Value s = m.initial_state();
    RunResult r = m.run(s, assemble("jmp 0"), 100);
    EXPECT_EQ(r.steps, 100u);
    EXPECT_EQ(r.reason, HaltReason::Running);
}

// ------------------------------------------------- step invariants ---

TEST_F(AutomatonTest, TransitionSpaceAcceptsRealStepsAndExplainsFakes) {
    TransitionSpace ts(m);
    problem::Rng rng(13);
    for (int i = 0; i < 300; ++i) {
        auto t = ts.generate(rng);
        ASSERT_TRUE(t);
        EXPECT_TRUE(ts.contains(*t)) << ts.explain(*t).front();
    }
    Value before = m.initial_state();
    m.set_reg(before, 1, 5);
    Value t = ts.make(before, {Op::Addi, 0, 1, 0, 3});
    EXPECT_EQ(t.reference("after.regs[0]").get<std::int64_t>(), 8);
    t.reference("after.regs[0]").set<std::int64_t>(9);  // forge the result
    EXPECT_FALSE(ts.contains(t));
    auto why = ts.explain(t);
    ASSERT_EQ(why.size(), 1u);
    EXPECT_NE(why[0].find("regs differs"), std::string::npos) << why[0];
    EXPECT_NE(why[0].find("addi r0 r1 3"), std::string::npos) << why[0];
    // projection repairs the forged transition
    auto fixed = ts.project(t);
    ASSERT_TRUE(fixed);
    EXPECT_TRUE(ts.contains(*fixed));
    // bad encoding is a structural violation
    t.reference("instr.a").set<std::uint8_t>(12);
    EXPECT_FALSE(ts.contains(t));
}

TEST_F(AutomatonTest, TraceValidation) {
    TransitionSpace ts(m);
    Program p = assemble("movi r0 3; addi r0 r0 4; halt");
    std::vector<Value> trace{m.initial_state()};
    for (int i = 0; i < 4; ++i) {
        Value next = trace.back().clone();
        m.step(next, p);
        trace.push_back(next);
    }
    EXPECT_TRUE(ts.valid_trace(trace, p));
    trace[2].reference("regs[0]").set<std::int64_t>(6);
    EXPECT_FALSE(ts.valid_trace(trace, p));
}

// ------------------------------------------------------ synthesis ---

namespace {
Example example(const Machine& m, std::vector<std::int64_t> regs, Invariant goal) {
    Value s = m.initial_state();
    for (std::size_t i = 0; i < regs.size(); ++i) m.set_reg(s, i, regs[i]);
    return Example{s, std::move(goal)};
}

/// Goal: r_out == expected (a per-example constant).
Invariant reg_equals(std::size_t r, std::int64_t v) {
    return Invariant(var("regs[" + std::to_string(r) + "]") == static_cast<double>(v));
}
}  // namespace

TEST_F(AutomatonTest, SynthesizesTimesFive) {
    // r0 = x  ->  r1 = 5x
    std::vector<Example> ex;
    for (std::int64_t x : {1, 3, -7, 100}) ex.push_back(example(m, {x}, reg_equals(1, 5 * x)));
    ProgramSynthesizer ps(m, ex, ProgramSearchOptions{.registers = 3, .max_length = 3});
    auto p = ps.synthesize();
    ASSERT_TRUE(p) << "explored " << ps.stats().states;
    EXPECT_LE(p->size(), 3u);
    EXPECT_TRUE(ps.verify(*p)) << to_string(*p);
    // generalises to unseen inputs
    Value s = m.initial_state();
    m.set_reg(s, 0, 12345);
    m.run(s, *p);
    EXPECT_EQ(m.reg(s, 1), 5 * 12345) << to_string(*p);
}

TEST_F(AutomatonTest, SynthesizesSwapAndShortestPrograms) {
    // swap r0, r1 with no scratch register: classic three-xor (or add/sub) solution.
    std::vector<Example> ex;
    for (auto [a, b] : std::vector<std::pair<int, int>>{{1, 2}, {-5, 9}, {40, 3}})
        ex.push_back(example(m, {a, b}, Invariant(reg_equals(0, b) && reg_equals(1, a))));
    ProgramSynthesizer ps(m, ex, ProgramSearchOptions{.registers = 2, .max_length = 3});
    auto p = ps.synthesize();
    ASSERT_TRUE(p);
    EXPECT_EQ(p->size(), 3u) << to_string(*p);
    EXPECT_TRUE(ps.verify(*p));
}

TEST_F(AutomatonTest, ObservationalEquivalenceAgreesWithPlainSearch) {
    std::vector<Example> ex;
    for (std::int64_t x : {2, 5, -3}) ex.push_back(example(m, {x}, reg_equals(0, x * x + 1)));
    ProgramSearchOptions o{.registers = 2, .max_length = 2};
    ProgramSynthesizer oe(m, ex, o);
    o.observational_equivalence = false;
    ProgramSynthesizer plain(m, ex, o);
    auto a = oe.synthesize(), b = plain.synthesize();
    ASSERT_TRUE(a && b);
    EXPECT_EQ(a->size(), b->size());
    EXPECT_TRUE(oe.verify(*a));
    EXPECT_TRUE(plain.verify(*b));
    EXPECT_LE(oe.stats().states, plain.stats().states);
    EXPECT_GT(oe.stats().states_per_second(), 0);
}

TEST_F(AutomatonTest, DedupeIsSoundOnPlantedPrograms) {
    // Plant a random hidden program, derive goals from its outputs, and check
    // that OE search (which discards states by compact key) finds a program no
    // longer than the planted one and agrees with plain enumeration on length.
    std::mt19937_64 rng(77);
    ProgramSearchOptions o{.immediates = {1, 3}, .registers = 3, .max_length = 3};
    ProgramSynthesizer probe(m, {example(m, {0}, reg_equals(0, 0))}, o);
    const auto& alphabet = probe.alphabet();
    for (int trial = 0; trial < 25; ++trial) {
        Program hidden;
        const std::size_t len = 1 + rng() % 3;
        for (std::size_t k = 0; k < len; ++k) hidden.push_back(alphabet[rng() % alphabet.size()]);
        std::vector<Example> ex;
        for (std::int64_t x : {2, -3, 5}) {
            Value s = m.initial_state();
            m.set_reg(s, 0, x);
            m.set_reg(s, 1, x * 7 - 1);
            Value out = s.clone();
            m.run(out, hidden, hidden.size() + 1);
            ex.push_back(example(m, {x, x * 7 - 1},
                                 Invariant(reg_equals(2, m.reg(out, 2)) && reg_equals(1, m.reg(out, 1)))));
        }
        ProgramSearchOptions oe = o;
        oe.max_length = hidden.size();
        ProgramSynthesizer a(m, ex, oe);
        auto pa = a.synthesize();
        ASSERT_TRUE(pa) << "trial " << trial << ": " << to_string(hidden);
        EXPECT_LE(pa->size(), hidden.size()) << to_string(hidden);
        EXPECT_TRUE(a.verify(*pa));
        if (hidden.size() <= 2) {  // plain enumeration is the exhaustive reference
            ProgramSearchOptions plain = oe;
            plain.observational_equivalence = false;
            ProgramSynthesizer b(m, ex, plain);
            auto pb = b.synthesize();
            ASSERT_TRUE(pb) << "trial " << trial << ": " << to_string(hidden);
            EXPECT_EQ(pa->size(), pb->size()) << to_string(hidden) << " | oe: " << to_string(*pa)
                                              << " | plain: " << to_string(*pb);
        }
    }
}

TEST_F(AutomatonTest, ImpossibleTasksExhaustCleanly) {
    // r1 = r0 * r0 * r0 * r0 * r0 + 7 is not reachable in one instruction.
    std::vector<Example> ex;
    for (std::int64_t x : {2, 3}) ex.push_back(example(m, {x}, reg_equals(1, x * x * x * x * x + 7)));
    ProgramSynthesizer ps(m, ex, ProgramSearchOptions{.registers = 2, .max_length = 1});
    EXPECT_FALSE(ps.synthesize());
    EXPECT_FALSE(ps.stats().budget_exhausted);
    ProgramSynthesizer tight(m, ex, ProgramSearchOptions{.registers = 4, .max_length = 6, .max_states = 1000});
    EXPECT_FALSE(tight.synthesize());
    EXPECT_TRUE(tight.stats().budget_exhausted);
}

TEST_F(AutomatonTest, SynthesisRejectsBadConfiguration) {
    std::vector<Example> ex{example(m, {1}, reg_equals(0, 1))};
    EXPECT_THROW(ProgramSynthesizer(m, {}, {}), std::invalid_argument);
    EXPECT_THROW(ProgramSynthesizer(m, ex, ProgramSearchOptions{.ops = {Op::Jmp}}), std::invalid_argument);
    EXPECT_THROW(ProgramSynthesizer(m, ex, ProgramSearchOptions{.registers = 9}), std::invalid_argument);
    ProgramSynthesizer trivial(m, ex, {});
    auto p = trivial.synthesize();
    ASSERT_TRUE(p);
    EXPECT_TRUE(p->empty());
}

TEST_F(AutomatonTest, MemoryAwareSynthesis) {
    // store r0 to mem[8..16) and leave mem[8] == r0's low byte
    std::vector<Example> ex;
    for (std::int64_t x : {5, 17}) {
        auto e = example(m, {x, 8}, Invariant(var("mem[8]") == static_cast<double>(x)));
        ex.push_back(std::move(e));
    }
    ProgramSynthesizer ps(m, ex, ProgramSearchOptions{.ops = {Op::Stb, Op::St, Op::Mov}, .immediates = {0}, .registers = 2, .max_length = 1});
    auto p = ps.synthesize();
    ASSERT_TRUE(p);
    EXPECT_TRUE(ps.verify(*p)) << to_string(*p);
}
