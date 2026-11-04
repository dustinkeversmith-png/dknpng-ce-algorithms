// Google Benchmark targets:
//   1. strided multidimensional Value slicing
//   2. synthesizer exploration rate (states/sec), incl. 8-instruction tasks
//   3. quantum state tensor contraction up to 16 qubits
#include "alg_exp/automaton.hpp"
#include "alg_exp/problem.hpp"
#include "alg_exp/quantum/Quantum.hpp"
#include "alg_exp/value.hpp"

#include <benchmark/benchmark.h>

using namespace alg_exp;
using value::Value;

// ------------------------------------------------------------- slicing ---

static void BM_ElementView3D(benchmark::State& st) {
    value::TypeRegistry reg;
    Value t(reg.get("f64[64,64,64]"));
    std::size_t i = 0;
    for (auto _ : st) {
        Value e = t.at({i & 63, (i >> 6) & 63, (i >> 12) & 63});
        benchmark::DoNotOptimize(e.offset());
        ++i;
    }
    st.SetItemsProcessed(st.iterations());
}
BENCHMARK(BM_ElementView3D);

static void BM_IndexDimensionOnly(benchmark::State& st) {
    value::TypeRegistry reg;
    Value t(reg.get("u8[16,16,16,16]"));
    std::size_t i = 0;
    for (auto _ : st) {
        const std::size_t idx[4] = {i & 15, (i >> 4) & 15, (i >> 8) & 15, (i >> 12) & 15};
        benchmark::DoNotOptimize(t.index_dimension(idx).offset);
        ++i;
    }
    st.SetItemsProcessed(st.iterations());
}
BENCHMARK(BM_IndexDimensionOnly);

static void BM_SubArraySliceAndSum(benchmark::State& st) {
    value::TypeRegistry reg;
    const auto n = static_cast<std::size_t>(st.range(0));
    Value t(reg.get("f64[" + std::to_string(n) + "," + std::to_string(n) + "]"));
    for (auto _ : st) {
        double acc = 0;
        for (std::size_t r = 0; r < n; ++r) {
            Value row = t[r];
            for (std::size_t c = 0; c < n; ++c) acc += row[c].get<double>();
        }
        benchmark::DoNotOptimize(acc);
    }
    st.SetItemsProcessed(st.iterations() * static_cast<std::int64_t>(n * n));
}
BENCHMARK(BM_SubArraySliceAndSum)->Arg(16)->Arg(64)->Arg(256);

static void BM_PathReference(benchmark::State& st) {
    value::TypeRegistry reg;
    reg.define_struct("P", {{"x", "f64"}, {"y", "f64"}});
    reg.define_struct("Q", {{"id", "u32"}, {"pts", "P[8]"}});
    Value v(reg.get("Q[32]"));
    for (auto _ : st) benchmark::DoNotOptimize(v.reference("[17].pts[5].y").offset());
    st.SetItemsProcessed(st.iterations());
}
BENCHMARK(BM_PathReference);

// ------------------------------------------------------- synthesizers ---

static void BM_QueensEnumerate(benchmark::State& st) {
    using namespace problem;
    const auto n = static_cast<std::size_t>(st.range(0));
    value::TypeRegistry reg;
    std::vector<Invariant> invs;
    for (std::size_t i = 0; i < n; ++i)
        for (std::size_t j = i + 1; j < n; ++j) {
            Expr a = var("[" + std::to_string(i) + "]"), b = var("[" + std::to_string(j) + "]");
            const double d = static_cast<double>(j - i);
            invs.emplace_back((a != b) && (a - b != d) && (b - a != d));
        }
    auto space = ConstrainedSpace::over_all_leaves("q", reg.get("u8[" + std::to_string(n) + "]"),
                                                   Domain::range(0, static_cast<double>(n) - 1), invs);
    std::uint64_t nodes = 0;
    for (auto _ : st) {
        Synthesizer s = space->synthesizer();
        benchmark::DoNotOptimize(s.count());
        nodes += s.stats().nodes;
    }
    st.counters["states/s"] = benchmark::Counter(static_cast<double>(nodes), benchmark::Counter::kIsRate);
}
BENCHMARK(BM_QueensEnumerate)->Arg(6)->Arg(8)->Unit(benchmark::kMillisecond);

static void BM_MagicSquareEnumerate(benchmark::State& st) {
    // all 8 3x3 magic squares: sums + all_different, heavy on interval pruning
    using namespace problem;
    value::TypeRegistry reg;
    auto c = [](int r, int k) { return var("[" + std::to_string(r) + "," + std::to_string(k) + "]"); };
    std::vector<Expr> cells;
    for (int r = 0; r < 3; ++r)
        for (int k = 0; k < 3; ++k) cells.push_back(c(r, k));
    std::vector<Invariant> invs{Invariant(Constraint::all_different(cells))};
    for (int i = 0; i < 3; ++i) {
        invs.emplace_back(c(i, 0) + c(i, 1) + c(i, 2) == 15);
        invs.emplace_back(c(0, i) + c(1, i) + c(2, i) == 15);
    }
    invs.emplace_back(c(0, 0) + c(1, 1) + c(2, 2) == 15);
    invs.emplace_back(c(0, 2) + c(1, 1) + c(2, 0) == 15);
    auto space = ConstrainedSpace::over_all_leaves("magic", reg.get("u8[3,3]"), Domain::range(1, 9), invs);
    std::uint64_t nodes = 0;
    for (auto _ : st) {
        Synthesizer s = space->synthesizer();
        benchmark::DoNotOptimize(s.count());
        nodes += s.stats().nodes;
    }
    st.counters["states/s"] = benchmark::Counter(static_cast<double>(nodes), benchmark::Counter::kIsRate);
}
BENCHMARK(BM_MagicSquareEnumerate)->Unit(benchmark::kMillisecond);

static void BM_MinConflictsQueens(benchmark::State& st) {
    using namespace problem;
    const auto n = static_cast<std::size_t>(st.range(0));
    value::TypeRegistry reg;
    std::vector<Invariant> invs;
    for (std::size_t i = 0; i < n; ++i)
        for (std::size_t j = i + 1; j < n; ++j) {
            Expr a = var("[" + std::to_string(i) + "]"), b = var("[" + std::to_string(j) + "]");
            const double d = static_cast<double>(j - i);
            invs.emplace_back((a != b) && (a - b != d) && (b - a != d));
        }
    auto space = ConstrainedSpace::over_all_leaves("q", reg.get("u8[" + std::to_string(n) + "]"),
                                                   Domain::range(0, static_cast<double>(n) - 1), invs);
    std::uint64_t seed = 1;
    for (auto _ : st) {
        Synthesizer s = space->synthesizer(SynthesisOptions{.seed = seed++});
        benchmark::DoNotOptimize(s.min_conflicts(100000));
    }
}
BENCHMARK(BM_MinConflictsQueens)->Arg(16)->Arg(32)->Unit(benchmark::kMillisecond);

namespace {
std::vector<automaton::Example> poly_examples(const automaton::Machine& m) {
    std::vector<automaton::Example> ex;
    for (std::int64_t x : {2, 3, -5, 7}) {
        Value s = m.initial_state();
        m.set_reg(s, 0, x);
        // unreachable target so the search explores its whole budget
        ex.push_back({s, problem::Invariant(problem::var("regs[1]") == static_cast<double>(x * 1000003 + 77))});
    }
    return ex;
}
}  // namespace

static void BM_ProgramSearch8(benchmark::State& st) {
    // 8-instruction synthesis task under a fixed state budget; reports exploration rate.
    automaton::Machine m(64);
    const bool oe = st.range(0) != 0;
    std::uint64_t states = 0;
    for (auto _ : st) {
        automaton::ProgramSynthesizer ps(m, poly_examples(m),
                                         {.registers = 3, .max_length = 8, .max_states = 200000,
                                          .observational_equivalence = oe});
        benchmark::DoNotOptimize(ps.synthesize());
        states += ps.stats().states;
    }
    st.counters["states/s"] = benchmark::Counter(static_cast<double>(states), benchmark::Counter::kIsRate);
    st.SetLabel(oe ? "observational-equivalence" : "plain-iddfs");
}
BENCHMARK(BM_ProgramSearch8)->Arg(1)->Arg(0)->Unit(benchmark::kMillisecond);

static void BM_MachineStep(benchmark::State& st) {
    automaton::Machine m(256);
    Value s = m.initial_state();
    const automaton::Instruction ins{automaton::Op::Add, 1, 1, 0, 0};
    m.set_reg(s, 0, 3);
    for (auto _ : st) m.execute(s, ins);
    benchmark::DoNotOptimize(m.reg(s, 1));
    st.SetItemsProcessed(st.iterations());
}
BENCHMARK(BM_MachineStep);

// ---------------------------------------------------------------- quantum ---

static void BM_HadamardLayer(benchmark::State& st) {
    const auto n = static_cast<std::size_t>(st.range(0));
    Value s = quantum::make_state(n);
    for (auto _ : st)
        for (std::size_t q = 0; q < n; ++q) quantum::hadamard(s, q);
    st.SetItemsProcessed(st.iterations() * static_cast<std::int64_t>(n) * (std::int64_t{1} << n));
    st.SetBytesProcessed(st.iterations() * static_cast<std::int64_t>(n) * 16 * (std::int64_t{1} << n));
}
BENCHMARK(BM_HadamardLayer)->DenseRange(4, 16, 4)->Unit(benchmark::kMicrosecond);

static void BM_CnotChain(benchmark::State& st) {
    const auto n = static_cast<std::size_t>(st.range(0));
    Value s = quantum::make_state(n);
    for (auto _ : st)
        for (std::size_t q = 0; q + 1 < n; ++q) quantum::cnot(s, q, q + 1);
    st.SetItemsProcessed(st.iterations() * static_cast<std::int64_t>(n - 1) * (std::int64_t{1} << n));
}
BENCHMARK(BM_CnotChain)->DenseRange(4, 16, 4)->Unit(benchmark::kMicrosecond);

static void BM_TwoQubitContraction(benchmark::State& st) {
    // general 4x4 unitary contracted against qubits (0, n-1)
    const auto n = static_cast<std::size_t>(st.range(0));
    Value s = quantum::make_state(n);
    quantum::Circuit c(2);
    c.h(0).cnot(0, 1).rz(1, 0.3);
    const auto u = c.unitary();
    for (auto _ : st) quantum::apply_unitary(s, {0, n - 1}, u);
    st.SetItemsProcessed(st.iterations() * (std::int64_t{1} << n));
}
BENCHMARK(BM_TwoQubitContraction)->DenseRange(4, 16, 4)->Unit(benchmark::kMicrosecond);

static void BM_QftCircuit(benchmark::State& st) {
    const auto n = static_cast<std::size_t>(st.range(0));
    const auto qft = quantum::Circuit::qft(n);
    Value s = quantum::make_state(n);
    for (auto _ : st) qft.apply(s);
    st.counters["gates"] = static_cast<double>(qft.size());
}
BENCHMARK(BM_QftCircuit)->DenseRange(4, 16, 4)->Unit(benchmark::kMillisecond);
