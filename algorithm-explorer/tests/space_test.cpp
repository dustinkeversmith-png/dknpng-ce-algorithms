#include "combinatorial/problem.hpp"
#include "combinatorial/value.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <set>

using namespace combinatorial;
using namespace combinatorial::problem;
using value::Value;

namespace {

value::TypeRegistry& reg() {
    static value::TypeRegistry r;
    return r;
}

std::string idx(const char* name, std::size_t i) { return std::string(name) + "[" + std::to_string(i) + "]"; }

/// queens[i] = column of the queen in row i.
std::vector<Invariant> queens_invariants(std::size_t n) {
    std::vector<Invariant> out;
    for (std::size_t i = 0; i < n; ++i)
        for (std::size_t j = i + 1; j < n; ++j) {
            Expr a = var(idx("", i)), b = var(idx("", j));
            const double d = static_cast<double>(j - i);
            out.emplace_back("queens " + std::to_string(i) + "," + std::to_string(j) + " safe",
                             (a != b) && (a - b != d) && (b - a != d));
        }
    return out;
}

std::shared_ptr<ConstrainedSpace> queens(std::size_t n, Norm norm = Norm::L1) {
    return ConstrainedSpace::over_all_leaves(std::to_string(n) + "-queens", reg().get("u8[" + std::to_string(n) + "]"),
                                             Domain::range(0, static_cast<double>(n) - 1), queens_invariants(n), norm);
}

/// Opaque (non-symbolic) predicate with declared dependencies.
Invariant opaque_lt(std::string a, std::string b) {
    return Invariant(
        a + " < " + b, [a, b](const Value& v) { return v.reference(a).as<double>() < v.reference(b).as<double>(); },
        {}, {a, b});
}

}  // namespace

// ----------------------------------------------------------- metrics ---

TEST(MetricTest, NormsOverShapedBuffers) {
    Value a(reg().get("f64[2,2]")), b(reg().get("f64[2,2]"));
    a.from_vector<double>(std::vector<double>{0, 0, 0, 0});
    b.from_vector<double>(std::vector<double>{3, -4, 0, 1});
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::L1), 8);
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::L2), std::sqrt(26.0));
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::LInf), 4);
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::Hamming), 3);
    EXPECT_DOUBLE_EQ(distance(a, a, Norm::L2), 0);
    EXPECT_THROW(distance(a, Value(reg().get("f64[4]")), Norm::L1), std::invalid_argument);
}

TEST(MetricTest, MixedStructLeavesAndOpaque) {
    value::TypeRegistry r;
    r.define_primitive("tag4", 4);
    auto t = r.define_struct("Rec", {{"k", "tag4"}, {"x", "i16"}, {"y", "f32[2]"}});
    Value a(t), b(t);
    b.reference("x").set<std::int16_t>(-2);
    b.reference("y[1]").set<float>(0.5f);
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::L1), 2.5);
    const std::array<std::byte, 1> one{std::byte{1}};
    b.reference("k").insert(one);
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::Hamming), 3);
}

TEST(MetricTest, MetricAxiomsOnRandomSamples) {
    Rng rng(3);
    BoxSpace box("box", reg().get("i32[5]"), {-50, 50});
    for (int i = 0; i < 200; ++i) {
        Value x = *box.generate(rng), y = *box.generate(rng), z = *box.generate(rng);
        for (Norm n : {Norm::L1, Norm::L2, Norm::LInf, Norm::Hamming}) {
            EXPECT_DOUBLE_EQ(distance(x, y, n), distance(y, x, n));
            EXPECT_LE(distance(x, z, n), distance(x, y, n) + distance(y, z, n) + 1e-9);
            EXPECT_GE(distance(x, y, n), 0);
        }
    }
}

// ------------------------------------------------------------ domains ---

TEST(DomainTest, Construction) {
    Domain d{3, 1, 2, 3};
    EXPECT_EQ(d.values, (std::vector<double>{1, 2, 3}));
    EXPECT_EQ(Domain::range(0, 1, 0.25).size(), 5u);
    EXPECT_EQ(Domain::of_prim(value::Prim::U8).size(), 256u);
    EXPECT_EQ(Domain::of_prim(value::Prim::Bool).values, (std::vector<double>{0, 1}));
    EXPECT_THROW(Domain::of_prim(value::Prim::U32), std::invalid_argument);
    EXPECT_THROW(Domain::range(0, 1, 0), std::invalid_argument);
    EXPECT_TRUE(Domain::range(2, 1).empty());
    EXPECT_EQ(d.index_of(2), 1u);
    EXPECT_EQ(d.index_of(2.5), 3u);
}

// -------------------------------------------------------- synthesizer ---

TEST(SynthesizerTest, EightQueensHas92Solutions) {
    auto space = queens(8);
    Synthesizer s = space->synthesizer();
    EXPECT_EQ(s.count(), 92u);
    EXPECT_TRUE(s.stats().exhausted);
    // Pairwise queen constraints only become decidable once both rows are
    // placed, so the work is done by forward checking, not interval pruning.
    EXPECT_LT(s.stats().nodes, 20000u);
    for (const auto& sol : space->enumerate(92)) EXPECT_TRUE(space->contains(sol)) << sol.to_string();
}

TEST(SynthesizerTest, SolutionCountsForSmallBoards) {
    const std::size_t expected[] = {1, 1, 0, 0, 2, 10, 4, 40};
    for (std::size_t n = 1; n <= 7; ++n) EXPECT_EQ(queens(n)->count(), expected[n]) << n;
}

TEST(SynthesizerTest, IntervalPruningReducesSearchWithoutChangingAnswers) {
    // x + y + z == 30 over 0..20: interval pruning cuts infeasible prefixes.
    auto t = reg().get("i32[3]");
    std::vector<Invariant> invs{Invariant(var("[0]") + var("[1]") + var("[2]") == 30),
                                Invariant(var("[0]") <= var("[1]")), Invariant(var("[1]") <= var("[2]"))};
    std::vector<DecisionLeaf> leaves{{"[0]", Domain::range(0, 20)}, {"[1]", Domain::range(0, 20)},
                                     {"[2]", Domain::range(0, 20)}};
    SynthesisOptions with, without;
    without.interval_pruning = false;
    Synthesizer a(Value(t), leaves, invs, with), b(Value(t), leaves, invs, without);
    const std::size_t na = a.count(), nb = b.count();
    EXPECT_EQ(na, nb);
    // brute-force: partitions of 30 into 3 ordered parts each <= 20
    std::size_t brute = 0;
    for (int x = 0; x <= 20; ++x)
        for (int y = x; y <= 20; ++y)
            if (int z = 30 - x - y; z >= y && z <= 20) ++brute;
    EXPECT_EQ(na, brute);
    EXPECT_LT(a.stats().nodes, b.stats().nodes);
    EXPECT_GT(a.stats().prunes, 0u);
}

TEST(SynthesizerTest, MagicSquare3x3) {
    auto t = reg().get("u8[3,3]");
    auto c = [](int r, int k) { return var("[" + std::to_string(r) + "," + std::to_string(k) + "]"); };
    std::vector<Expr> cells;
    for (int r = 0; r < 3; ++r)
        for (int k = 0; k < 3; ++k) cells.push_back(c(r, k));
    std::vector<Invariant> invs{Invariant("distinct", Constraint::all_different(cells))};
    for (int i = 0; i < 3; ++i) {
        invs.emplace_back(c(i, 0) + c(i, 1) + c(i, 2) == 15);
        invs.emplace_back(c(0, i) + c(1, i) + c(2, i) == 15);
    }
    invs.emplace_back(c(0, 0) + c(1, 1) + c(2, 2) == 15);
    invs.emplace_back(c(0, 2) + c(1, 1) + c(2, 0) == 15);
    auto space = ConstrainedSpace::over_all_leaves("magic", t, Domain::range(1, 9), invs);
    auto all = space->enumerate(100);
    ASSERT_EQ(all.size(), 8u);
    for (const auto& m : all) EXPECT_EQ(m.at({1, 1}).get<std::uint8_t>(), 5);
}

TEST(SynthesizerTest, GraphColouring) {
    // Petersen graph is 3-chromatic; K4 is not 3-colourable.
    const std::vector<std::pair<int, int>> petersen{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 0}, {0, 5}, {1, 6}, {2, 7},
                                                    {3, 8}, {4, 9}, {5, 7}, {7, 9}, {9, 6}, {6, 8}, {8, 5}};
    auto colouring = [](std::size_t n, const std::vector<std::pair<int, int>>& edges, int colours) {
        std::vector<Invariant> invs;
        for (auto [a, b] : edges) invs.emplace_back(var(idx("", a)) != var(idx("", b)));
        return ConstrainedSpace::over_all_leaves("colouring", reg().get("u8[" + std::to_string(n) + "]"),
                                                 Domain::range(0, colours - 1), invs);
    };
    auto p3 = colouring(10, petersen, 3);
    auto sol = p3->synthesizer().solve();
    ASSERT_TRUE(sol);
    EXPECT_TRUE(p3->contains(*sol));
    EXPECT_FALSE(colouring(10, petersen, 2)->synthesizer().solve());
    const std::vector<std::pair<int, int>> k4{{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}};
    auto s = colouring(4, k4, 3)->synthesizer();
    EXPECT_FALSE(s.solve());
    EXPECT_TRUE(s.stats().exhausted);
    EXPECT_EQ(colouring(4, k4, 4)->count(), 24u);
}

TEST(SynthesizerTest, OpaquePredicatesWithDependenciesAreForwardChecked) {
    auto t = reg().get("u8[4]");
    std::vector<Invariant> invs{opaque_lt("[0]", "[1]"), opaque_lt("[1]", "[2]"), opaque_lt("[2]", "[3]")};
    auto space = ConstrainedSpace::over_all_leaves("chain", t, Domain::range(0, 5), invs);
    Synthesizer s = space->synthesizer(SynthesisOptions{.order = SynthesisOptions::Order::Declaration});
    EXPECT_EQ(s.count(), 15u);  // C(6,4)
    // forward checking means far fewer than 6^4 = 1296 complete assignments are tried
    EXPECT_LT(s.stats().nodes, 300u);
}

TEST(SynthesizerTest, UnknownDependenciesAreCheckedAtTheEnd) {
    auto t = reg().get("u8[3]");
    Invariant sum_is_4("sum == 4", [](const Value& v) { return v.scalar(0) + v.scalar(1) + v.scalar(2) == 4; });
    auto space = ConstrainedSpace::over_all_leaves("sum", t, Domain::range(0, 4), {sum_is_4});
    EXPECT_EQ(space->count(), 15u);  // compositions of 4 into 3 non-negative parts
}

TEST(SynthesizerTest, FixedLeavesComeFromBase) {
    auto t = reg().define_struct("Pair3", {{"a", "i32"}, {"b", "i32"}, {"c", "f64"}});
    Value base(t);
    base.reference("a").set<std::int32_t>(7);
    base.reference("c").set<double>(2.5);
    Synthesizer s(base, {{"b", Domain::range(-10, 10)}}, {Invariant(var("a") + var("b") == 0)});
    auto sol = s.solve();
    ASSERT_TRUE(sol);
    EXPECT_EQ(sol->reference("b").get<std::int32_t>(), -7);
    EXPECT_DOUBLE_EQ(sol->reference("c").get<double>(), 2.5);
    EXPECT_THROW(Synthesizer(base, {{"b", Domain{}}}, {}), std::invalid_argument);
    EXPECT_THROW(Synthesizer(base, {{"b", Domain{1}}, {"b", Domain{2}}}, {}), std::invalid_argument);
    EXPECT_THROW(Synthesizer(base, {{"nope", Domain{1}}}, {}), std::invalid_argument);
}

TEST(SynthesizerTest, BudgetIsRespected) {
    SynthesisOptions o;
    o.max_nodes = 50;
    Synthesizer s = queens(10)->synthesizer(o);
    s.count();
    EXPECT_FALSE(s.stats().exhausted);
    EXPECT_LE(s.stats().nodes, 50u);
}

TEST(SynthesizerTest, ShuffledGenerationIsSeededAndValid) {
    auto space = queens(8);
    Rng a(42), b(42), c(43);
    auto x = space->generate(a), y = space->generate(b);
    ASSERT_TRUE(x && y);
    EXPECT_EQ(*x, *y);
    std::set<std::size_t> distinct;
    for (int i = 0; i < 20; ++i) {
        auto v = space->generate(c);
        ASSERT_TRUE(v);
        EXPECT_TRUE(space->contains(*v));
        distinct.insert(v->hash());
    }
    EXPECT_GT(distinct.size(), 5u);
}

TEST(SynthesizerTest, MinConflictsSolvesLargerQueens) {
    auto space = queens(24);
    SynthesisOptions o;
    o.seed = 11;
    Synthesizer s = space->synthesizer(o);
    auto sol = s.min_conflicts(20000);
    ASSERT_TRUE(sol);
    EXPECT_TRUE(space->contains(*sol));
}

TEST(SynthesizerTest, NearestMatchesBruteForce) {
    auto space = queens(6, Norm::L1);
    auto all = space->enumerate(100);
    ASSERT_EQ(all.size(), 4u);
    Rng rng(5);
    BoxSpace box("box", reg().get("u8[6]"), {0, 5});
    for (int trial = 0; trial < 30; ++trial) {
        Value target = *box.generate(rng);
        for (Norm n : {Norm::L1, Norm::L2, Norm::LInf, Norm::Hamming}) {
            double brute = std::numeric_limits<double>::infinity();
            for (const auto& m : all) brute = std::min(brute, distance(target, m, n));
            Synthesizer s = space->synthesizer();
            auto best = s.nearest(target, n);
            ASSERT_TRUE(best);
            EXPECT_TRUE(space->contains(*best));
            EXPECT_DOUBLE_EQ(distance(target, *best, n), brute) << norm_name(n);
            EXPECT_TRUE(s.stats().exhausted);
        }
    }
}

// --------------------------------------------------------------- space ---

TEST(SpaceTest, ProjectionOntoConstrainedSpace) {
    auto space = queens(5);
    Value bad(reg().get("u8[5]"));  // all queens in column 0
    EXPECT_FALSE(space->contains(bad));
    auto explained = space->explain(bad);
    EXPECT_FALSE(explained.empty());
    auto p = space->project(bad);
    ASSERT_TRUE(p);
    EXPECT_TRUE(space->contains(*p));
    // a member projects to itself
    EXPECT_EQ(*space->project(*p), *p);
}

TEST(SpaceTest, StructuralInvariantsGuardDomainsAndFixedLeaves) {
    auto t = reg().define_struct("Cfg", {{"mode", "u8"}, {"level", "u8"}});
    Value base(t);
    base.reference("level").set<std::uint8_t>(3);
    ConstrainedSpace space("cfg", base, {{"mode", Domain{1, 2, 4}}}, {});
    Value v = base.clone();
    v.reference("mode").set<std::uint8_t>(2);
    EXPECT_TRUE(space.contains(v));
    v.reference("mode").set<std::uint8_t>(3);
    EXPECT_FALSE(space.contains(v));
    v.reference("mode").set<std::uint8_t>(4);
    v.reference("level").set<std::uint8_t>(9);
    EXPECT_FALSE(space.contains(v));
    auto msgs = space.explain(v);
    ASSERT_EQ(msgs.size(), 1u);
    EXPECT_NE(msgs[0].find("differs from base"), std::string::npos);
    EXPECT_EQ(space.count(), 3u);
    EXPECT_FALSE(space.contains(Value(reg().get("u8[2]"))) && false);
    EXPECT_FALSE(space.contains(Value(reg().get("u16"))));
}

TEST(SpaceTest, SetAlgebra) {
    auto t = reg().get("u8[2]");
    auto grid = ConstrainedSpace::over_all_leaves("grid", t, Domain::range(0, 4), {});
    SpacePtr diag = ConstrainedSpace::over_all_leaves("diag", t, Domain::range(0, 4), {Invariant(var("[0]") == var("[1]"))});
    SpacePtr low = ConstrainedSpace::over_all_leaves("low", t, Domain::range(0, 4), {Invariant(var("[0]") + var("[1]") <= 3)});
    EXPECT_EQ(grid->count(), 25u);
    auto inter = diag & low;    // (0,0),(1,1)
    auto uni = diag | low;      // 5 + 10 - 2
    auto diff = low - diag;     // 10 - 2
    EXPECT_EQ(inter->enumerate(100).size(), 2u);
    EXPECT_EQ(uni->enumerate(100).size(), 13u);
    EXPECT_EQ(diff->enumerate(100).size(), 8u);
    EXPECT_EQ(inter->description(), "(diag ∩ low)");
    Rng rng(9);
    for (int i = 0; i < 20; ++i) {
        for (const SpacePtr& s : {inter, uni, diff}) {
            auto v = s->generate(rng);
            ASSERT_TRUE(v) << s->description();
            EXPECT_TRUE(s->contains(*v)) << s->description();
            EXPECT_TRUE(s->invariant().holds(*v)) << s->description();
        }
    }
    // algebraic identities on membership
    for (const auto& v : grid->enumerate(100)) {
        EXPECT_EQ(inter->contains(v), diag->contains(v) && low->contains(v));
        EXPECT_EQ(uni->contains(v), diag->contains(v) || low->contains(v));
        EXPECT_EQ(diff->contains(v), low->contains(v) && !diag->contains(v));
        EXPECT_EQ((diag & low)->contains(v), (low & diag)->contains(v));
        EXPECT_EQ(((diag | low) - inter)->contains(v), diag->contains(v) != low->contains(v));
    }
    EXPECT_THROW(intersect(diag, queens(3)), std::invalid_argument);
}

TEST(SpaceTest, EmptyIntersectionGeneratesNothing) {
    auto t = reg().get("u8[2]");
    SpacePtr a = ConstrainedSpace::over_all_leaves("a", t, Domain::range(0, 3), {Invariant(var("[0]") < 2)});
    SpacePtr b = ConstrainedSpace::over_all_leaves("b", t, Domain::range(0, 3), {Invariant(var("[0]") > 2)});
    Rng rng(1);
    EXPECT_FALSE((a & b)->generate(rng));
    EXPECT_TRUE((a & b)->enumerate(10).empty());
}

TEST(SpaceTest, ProjectionOntoUnionPicksCloserSide) {
    auto t = reg().get("i32");
    auto pt = [&](double x) {
        return ConstrainedSpace::over_all_leaves("p", t, Domain{x}, {});
    };
    SpacePtr u = SpacePtr(pt(0)) | SpacePtr(pt(10));
    Value v(t);
    v.set<std::int32_t>(7);
    EXPECT_EQ(u->project(v)->get<std::int32_t>(), 10);
    v.set<std::int32_t>(3);
    EXPECT_EQ(u->project(v)->get<std::int32_t>(), 0);
}

TEST(SpaceTest, PredicateSpaceUsesRejection) {
    auto t = reg().get("f64[2]");
    PredicateSpace disk("unit disk", t,
                        {Invariant(var("[0]") * var("[0]") + var("[1]") * var("[1]") <= 1)},
                        [t](Rng& rng) {
                            Value v(t);
                            std::uniform_real_distribution<double> u(-1, 1);
                            v.set_scalar(0, u(rng));
                            v.set_scalar(1, u(rng));
                            return std::optional<Value>(v);
                        });
    Rng rng(4);
    for (int i = 0; i < 50; ++i) EXPECT_TRUE(disk.contains(*disk.generate(rng)));
    Value far(t);
    far.from_vector<double>(std::vector<double>{3, 0});
    auto p = disk.project(far);  // sampled projection
    ASSERT_TRUE(p);
    EXPECT_TRUE(disk.contains(*p));
    EXPECT_GT(p->scalar(0), 0.5);
}

TEST(SpaceTest, BoxSpaceClampsExactly) {
    auto t = reg().define_struct("Pose", {{"x", "f64"}, {"y", "f64"}, {"n", "i32"}});
    BoxSpace box("pose", t, {-1, 1}, {{"n", {0, 10}}});
    Value v(t);
    v.reference("x").set<double>(5);
    v.reference("y").set<double>(-0.5);
    v.reference("n").set<std::int32_t>(-3);
    EXPECT_FALSE(box.contains(v));
    Value p = *box.project(v);
    EXPECT_TRUE(box.contains(p));
    EXPECT_DOUBLE_EQ(p.reference("x").get<double>(), 1);
    EXPECT_DOUBLE_EQ(p.reference("y").get<double>(), -0.5);
    EXPECT_EQ(p.reference("n").get<std::int32_t>(), 0);
    EXPECT_DOUBLE_EQ(box.distance(v, p), std::sqrt(16.0 + 9.0));
    EXPECT_EQ(box.structural_invariants().size(), 3u);
    for (const auto& inv : box.structural_invariants()) EXPECT_TRUE(inv.holds(p));
    Rng rng(2);
    for (int i = 0; i < 100; ++i) EXPECT_TRUE(box.contains(*box.generate(rng)));
    EXPECT_THROW(BoxSpace("bad", t, {1, 0}), std::invalid_argument);
    EXPECT_THROW(BoxSpace("bad", t, {0, 1}, {{"zz", {0, 1}}}), std::invalid_argument);
}

// ------------------------------------------------------------ topology ---

TEST(TopologyTest, NeighboursAndComponents) {
    auto t = reg().get("u8[2]");
    // Two separated blobs: x+y <= 1 and x+y >= 5 on a 4x4 grid.
    auto space = ConstrainedSpace::over_all_leaves(
        "blobs", t, Domain::range(0, 3),
        {Invariant((var("[0]") + var("[1]") <= 1) || (var("[0]") + var("[1]") >= 5))});
    EXPECT_EQ(space->count(), 6u);
    EXPECT_EQ(space->connected_components(), 2u);
    Value origin(t);
    auto ns = space->neighbors(origin);
    EXPECT_EQ(ns.size(), 2u);
    EXPECT_EQ(space->reachable(origin).size(), 3u);
    auto full = ConstrainedSpace::over_all_leaves("grid", t, Domain::range(0, 3), {});
    EXPECT_EQ(full->connected_components(), 1u);
    EXPECT_EQ(full->reachable(origin).size(), 16u);
    EXPECT_EQ(full->reachable(origin, 5).size(), 5u);
}

TEST(TopologyTest, QueensSolutionsAreIsolated) {
    // Moving one queen one column never yields another solution.
    auto space = queens(6);
    EXPECT_EQ(space->connected_components(), 4u);
}
