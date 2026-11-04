// Systematic coverage: the operator/saturation/SMT matrices over every
// numeric primitive, plus formula, space-algebra and search branches that the
// scenario tests do not reach.
#include "combinatorial/automaton.hpp"
#include "combinatorial/problem.hpp"
#include "combinatorial/value.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <limits>

using namespace combinatorial;
using namespace combinatorial::problem;
using value::Prim;
using value::Value;

namespace {
const Prim kNumeric[] = {Prim::U8,  Prim::I8,  Prim::U16, Prim::I16, Prim::U32, Prim::I32,
                         Prim::U64, Prim::I64, Prim::F32, Prim::F64, Prim::Bool};

value::TypePtr arr(Prim p, std::size_t n) {
    return value::ValueType::primitive(std::string(value::prim_name(p)), p, {n});
}

template <class T>
double lo_of() { return static_cast<double>(std::numeric_limits<T>::lowest()); }
template <class T>
double hi_of() { return static_cast<double>(std::numeric_limits<T>::max()); }
}  // namespace

class PrimMatrix : public ::testing::TestWithParam<Prim> {};

TEST_P(PrimMatrix, NameRoundTripAndTraits) {
    const Prim p = GetParam();
    EXPECT_EQ(value::prim_from_name(value::prim_name(p)), p);
    EXPECT_EQ(value::prim_size(p), value::ValueType::primitive("x", p)->byte_size());
    value::visit_prim(p, [&]<class T>(std::type_identity<T>) {
        EXPECT_EQ(sizeof(T), value::prim_size(p));
        EXPECT_EQ(value::prim_is_float(p), std::is_floating_point_v<T>);
        EXPECT_EQ(value::prim_is_signed(p), std::is_signed_v<T>);
    });
}

TEST_P(PrimMatrix, SaturationAtBothEnds) {
    const Prim p = GetParam();
    Value v(value::ValueType::primitive("x", p));
    value::visit_prim(p, [&]<class T>(std::type_identity<T>) {
        if constexpr (std::is_same_v<T, bool>) {
            v.assign(-3);
            EXPECT_TRUE(v.get<bool>());
            v.assign(0);
            EXPECT_FALSE(v.get<bool>());
            v.assign(std::nan(""));
            EXPECT_FALSE(v.get<bool>());
        } else if constexpr (std::is_floating_point_v<T>) {
            v.assign(1.25);
            EXPECT_EQ(v.get<T>(), T(1.25));
            v.assign(std::numeric_limits<double>::infinity());
            EXPECT_TRUE(std::isinf(v.get<T>()));
        } else {
            v.assign(1e40);
            EXPECT_EQ(v.get<T>(), std::numeric_limits<T>::max());
            v.assign(-1e40);
            EXPECT_EQ(v.get<T>(), std::numeric_limits<T>::lowest());
            v.assign(std::nan(""));
            EXPECT_EQ(v.get<T>(), T{0});
            v.assign(-0.5);  // truncates toward zero
            EXPECT_EQ(v.get<T>(), T{0});
            v.assign(42.9);
            EXPECT_EQ(v.get<T>(), T{42});
            EXPECT_DOUBLE_EQ(v.as<double>(), 42.0);
            v.assign(lo_of<T>());
            EXPECT_EQ(v.get<T>(), std::numeric_limits<T>::lowest());
        }
    });
}

TEST_P(PrimMatrix, EveryBuiltinOperator) {
    const Prim p = GetParam();
    value::TypeRegistry reg;
    Value a(arr(p, 3)), b(arr(p, 3)), s(value::ValueType::primitive("s", p));
    const bool is_bool = p == Prim::Bool;
    const std::vector<double> av = is_bool ? std::vector<double>{1, 0, 1} : std::vector<double>{6, 9, 4};
    const std::vector<double> bv = is_bool ? std::vector<double>{1, 1, 0} : std::vector<double>{3, 9, 2};
    a.from_vector<double>(av);
    b.from_vector<double>(bv);
    s.assign(is_bool ? 1 : 2);
    auto vec = [](const Value& v) { return v.to_vector<double>(); };
    auto expect = [&](const char* op, std::vector<double> want) {
        Value r = reg.apply(op, {a, b});
        EXPECT_EQ(vec(r), want) << op << " " << value::prim_name(p);
    };
    if (!is_bool) {
        expect("add", {9, 18, 6});
        expect("sub", {3, 0, 2});
        expect("mul", {18, 81, 8});
        expect("div", {2, 1, 2});
        expect("min", {3, 9, 2});
        expect("max", {6, 9, 4});
        EXPECT_EQ(vec(reg.apply("mul", {a, s})), (std::vector<double>{12, 18, 8}));  // scalar broadcast (rhs)
        EXPECT_EQ(vec(reg.apply("add", {s, a})), (std::vector<double>{8, 11, 6}));   // scalar broadcast (lhs)
        EXPECT_DOUBLE_EQ(reg.apply("sum", {a}).get<double>(), 19);
        EXPECT_DOUBLE_EQ(reg.apply("dot", {a, b}).get<double>(), 18 + 81 + 8);
        EXPECT_EQ(vec(reg.apply("abs", {a})), av);
    } else {
        expect("min", {1, 0, 0});
        expect("max", {1, 1, 1});
    }
    const auto cmp = [&](const char* op) { return vec(reg.apply(op, {a, b})); };
    for (const char* op : {"eq", "ne", "lt", "le", "gt", "ge"}) {
        auto r = cmp(op);
        for (std::size_t i = 0; i < 3; ++i) {
            const double x = av[i], y = bv[i];
            const std::string o = op;
            const bool want = o == "eq" ? x == y : o == "ne" ? x != y : o == "lt" ? x < y : o == "le" ? x <= y
                                                                         : o == "gt" ? x > y : x >= y;
            EXPECT_EQ(r[i], want ? 1.0 : 0.0) << op << " " << value::prim_name(p);
        }
    }
    // mixed-primitive operands fall back to double arithmetic
    Value f(arr(Prim::F64, 3));
    f.from_vector<double>(std::vector<double>{0.5, 0.5, 0.5});
    EXPECT_EQ(reg.apply("add", {a, f}).type()->prim(), p);
    EXPECT_EQ(reg.apply("lt", {a, f}).type()->prim(), Prim::Bool);
}

TEST_P(PrimMatrix, IntegerDivisionEdgeCases) {
    const Prim p = GetParam();
    if (value::prim_is_float(p) || p == Prim::Bool) GTEST_SKIP();
    value::TypeRegistry reg;
    Value a(value::ValueType::primitive("a", p)), z(value::ValueType::primitive("z", p));
    a.assign(7);
    EXPECT_THROW(reg.apply("div", {a, z}), std::domain_error);
    if (value::prim_is_signed(p)) {
        value::visit_prim(p, [&]<class T>(std::type_identity<T>) {
            if constexpr (std::is_integral_v<T> && !std::is_same_v<T, bool>) {
                a.set<T>(std::numeric_limits<T>::min());
                z.set<T>(T(-1));
                EXPECT_THROW(reg.apply("div", {a, z}), std::domain_error);
                // wrapping add at the top of the range
                a.set<T>(std::numeric_limits<T>::max());
                z.set<T>(T(1));
                EXPECT_EQ(reg.apply("add", {a, z}).get<T>(), std::numeric_limits<T>::min());
            }
        });
    } else {
        value::visit_prim(p, [&]<class T>(std::type_identity<T>) {
            if constexpr (std::is_integral_v<T>) {
                a.set<T>(T(0));
                z.set<T>(T(1));
                EXPECT_EQ(reg.apply("sub", {a, z}).get<T>(), std::numeric_limits<T>::max());
            }
        });
    }
}

TEST_P(PrimMatrix, SmtRangeBoundsMatchMachineRange) {
    const Prim p = GetParam();
    auto t = value::ValueType::structure("S", {{"v", value::ValueType::primitive("v", p)}});
    SmtScript s = lower_to_smtlib(t, {Invariant(var("v") >= 0)});
    EXPECT_NE(s.text.find(value::prim_is_float(p) ? "(declare-const |v| Real)" : "(declare-const |v| Int)"),
              std::string::npos);
#ifdef COMBINATORIAL_Z3_PATH
    if (!value::prim_is_float(p)) {
        // the maximum is reachable, one past it is not
        value::visit_prim(p, [&]<class T>(std::type_identity<T>) {
            const double hi = std::is_same_v<T, bool> ? 1.0 : hi_of<T>();
            if (hi > 9e15) return;  // beyond exact double; covered textually
            auto sat = solve_smt(t, {Invariant(var("v") == hi)}, COMBINATORIAL_Z3_PATH);
            EXPECT_EQ(sat.status, SmtResult::Status::Sat) << value::prim_name(p);
            if (sat.model) {
                EXPECT_DOUBLE_EQ(sat.model->reference("v").as<double>(), hi);
            }
            EXPECT_EQ(solve_smt(t, {Invariant(var("v") > hi)}, COMBINATORIAL_Z3_PATH).status,
                      SmtResult::Status::Unsat)
                << value::prim_name(p);
        });
    }
#endif
}

TEST_P(PrimMatrix, DistanceAndPrinting) {
    const Prim p = GetParam();
    Value a(arr(p, 2)), b(arr(p, 2));
    b.set_scalar(1, 1);
    EXPECT_DOUBLE_EQ(distance(a, b, Norm::L1), 1);
    EXPECT_FALSE(a.to_string().empty());
    EXPECT_EQ(a.clone(), a);
}

INSTANTIATE_TEST_SUITE_P(AllNumeric, PrimMatrix, ::testing::ValuesIn(kNumeric),
                         [](const auto& info) { return std::string(value::prim_name(info.param)); });

// ------------------------------------------------------------ formulas ---

TEST(FormulaCoverage, PrintingAndExplainingEveryNode) {
    value::TypeRegistry reg;
    auto t = reg.define_struct("XY", {{"x", "i32"}, {"y", "i32"}});
    Value v(t);
    v.reference("x").set<std::int32_t>(2);
    v.reference("y").set<std::int32_t>(5);
    ValueEnv env(v);
    EXPECT_EQ((-var("x")).to_string(), "-x");
    EXPECT_EQ(min(var("x"), 1).to_string(), "min(x, 1)");
    EXPECT_EQ(max(var("x"), 1).to_string(), "max(x, 1)");
    EXPECT_EQ(Expr::var("").to_string(), "$");
    EXPECT_EQ(Constraint::always().to_string(), "true");
    EXPECT_EQ(Constraint::never().to_string(), "false");
    EXPECT_EQ((!(var("x") == 2)).to_string(), "!(x == 2)");
    EXPECT_EQ(((var("x") == 1) || (var("y") == 1)).to_string(), "(x == 1 || y == 1)");
    EXPECT_EQ(Constraint::never().explain(env), "false");
    EXPECT_EQ(Constraint::always().explain(env), "");
    EXPECT_NE(((var("x") == 1) || (var("y") == 1)).explain(env).find("none of the alternatives"), std::string::npos);
    EXPECT_NE((!(var("x") == 2)).explain(env).find("expected !(x == 2)"), std::string::npos);
    EXPECT_EQ(Constraint::always().feasible(env), Tri::True);
    EXPECT_EQ(Constraint::never().feasible(env), Tri::False);
    EXPECT_THROW(Constraint::compare(Constraint::Op::And, 1, 2), std::invalid_argument);
    EXPECT_THROW(Constraint::always().lhs(), std::logic_error);
    EXPECT_THROW(Constraint::always().rhs(), std::logic_error);
    EXPECT_EQ(Constraint::any_of({}).op(), Constraint::Op::False);
    // SMT lowering of every node kind
    const std::string smt = to_smtlib(t, Constraint::all_of({!(var("x") == 1), Constraint::always() || Constraint::never(),
                                                              -var("x") - var("y") >= min(var("x"), var("y")),
                                                              max(var("x"), 0) > 0}));
    EXPECT_EQ(smt,
              "(and (not (= |x| 1)) (or true false) (>= (- (- |x|) |y|) (ite (<= |x| |y|) |x| |y|)) "
              "(> (ite (>= |x| 0) |x| 0) 0))");
}

TEST(FormulaCoverage, InvariantFeasibilityThroughNotAndOr) {
    struct Point : Env {
        double value(std::string_view) const override { return 3; }
    } env;
    Invariant gt(var("x") > 1), lt(var("x") < 1);
    EXPECT_EQ((!gt).feasible(env), Tri::False);
    EXPECT_EQ((!lt).feasible(env), Tri::True);
    EXPECT_EQ((gt || lt).feasible(env), Tri::True);
    EXPECT_EQ((lt || lt).feasible(env), Tri::False);
    Invariant opaque("?", [](const Value&) { return true; });
    EXPECT_EQ((lt || opaque).feasible(env), Tri::Unknown);
    EXPECT_EQ((!opaque).feasible(env), Tri::Unknown);
    EXPECT_EQ((gt && opaque).feasible(env), Tri::Unknown);
    EXPECT_FALSE((!opaque).constraint().has_value());
    EXPECT_EQ((gt || opaque).dependencies(), std::nullopt);
    EXPECT_EQ((gt || lt).size(), 3u);
    EXPECT_THROW(Invariant("null", Invariant::Predicate{}), std::invalid_argument);
}

// ------------------------------------------------------------- spaces ---

TEST(SpaceCoverage, AlgebraOverInfiniteSpaces) {
    value::TypeRegistry reg;
    auto t = reg.get("f64[2]");
    SpacePtr big = std::make_shared<BoxSpace>("big", t, Interval{-2, 2}, std::map<std::string, Interval>{}, Norm::L1);
    SpacePtr small = std::make_shared<BoxSpace>("small", t, Interval{-1, 1});
    auto u = big | small;
    auto d = big - small;
    EXPECT_FALSE(u->is_finite());
    EXPECT_FALSE(d->is_finite());
    EXPECT_EQ(u->norm(), Norm::L1);
    EXPECT_EQ(d->norm(), Norm::L1);
    EXPECT_EQ(d->description(), "(big \\ small)");
    EXPECT_EQ(u->description(), "(big ∪ small)");
    EXPECT_EQ(d->value_type().get(), t.get());
    Rng rng(6);
    for (int i = 0; i < 50; ++i) {
        auto x = d->generate(rng);
        ASSERT_TRUE(x);
        EXPECT_TRUE(big->contains(*x) && !small->contains(*x));
        EXPECT_TRUE(u->contains(*u->generate(rng)));
    }
    Value a(t), b(t);
    b.set_scalar(0, 1);
    EXPECT_DOUBLE_EQ(d->distance(a, b), 1);
    EXPECT_DOUBLE_EQ((big & small)->distance(a, b), 1);
    EXPECT_TRUE(d->enumerate(10).empty());
    // empty difference with no finite fallback
    SpacePtr e = small - big;
    EXPECT_FALSE(e->generate(rng));
    EXPECT_FALSE(e->project(a).has_value() && e->contains(*e->project(a)));
    // union where one side cannot generate falls back to the other
    SpacePtr empty = std::make_shared<PredicateSpace>("none", t, std::vector<Invariant>{Invariant::never()},
                                                      PredicateSpace::Generator{});
    for (int i = 0; i < 10; ++i) EXPECT_TRUE((empty | small)->generate(rng).has_value());
    EXPECT_EQ(small->explain(Value(reg.get("f64[3]"))).front().rfind("layout mismatch", 0), 0u);
    EXPECT_FALSE(small->contains(Value(reg.get("f64[3]"))));
}

TEST(SpaceCoverage, DefaultProjectionOnFiniteNonConstrainedSpace) {
    value::TypeRegistry reg;
    auto t = reg.get("u8[2]");
    SpacePtr a = ConstrainedSpace::over_all_leaves("a", t, Domain{0, 3}, {});
    SpacePtr b = ConstrainedSpace::over_all_leaves("b", t, Domain{1, 3}, {});
    auto inter = a & b;  // {(3,3)}
    Value v(t);
    auto p = inter->project(v);  // default Space::project -> enumeration path
    ASSERT_TRUE(p);
    EXPECT_EQ(p->to_vector<int>(), (std::vector<int>{3, 3}));
    auto diff = a - b;  // finite
    EXPECT_EQ(diff->enumerate(100).size(), 3u);
    Rng rng(1);
    EXPECT_TRUE(diff->contains(*diff->generate(rng)));
}

// ---------------------------------------------------------- synthesizer ---

TEST(SynthesizerCoverage, OrderingsAgree) {
    value::TypeRegistry reg;
    auto t = reg.get("u8[4]");
    std::vector<Invariant> invs{Invariant(var("[0]") + var("[1]") == var("[2]")),
                                Invariant(var("[3]") != var("[0]"))};
    std::vector<DecisionLeaf> leaves{{"[0]", Domain::range(0, 5)}, {"[1]", Domain::range(0, 2)},
                                     {"[2]", Domain::range(0, 7)}, {"[3]", Domain{1, 2}}};
    std::size_t counts[3];
    int k = 0;
    for (auto order : {SynthesisOptions::Order::Declaration, SynthesisOptions::Order::SmallestDomainFirst,
                       SynthesisOptions::Order::MostConstrainedFirst}) {
        Synthesizer s(Value(t), leaves, invs, SynthesisOptions{.order = order});
        counts[k++] = s.count();
        EXPECT_EQ(s.decision_count(), 4u);
    }
    EXPECT_EQ(counts[0], counts[1]);
    EXPECT_EQ(counts[1], counts[2]);
}

TEST(SynthesizerCoverage, RootChecksAndNestedConjunctions) {
    value::TypeRegistry reg;
    auto t = reg.define_struct("RC", {{"fixed", "i32"}, {"free", "i32"}});
    Value base(t);
    base.reference("fixed").set<std::int32_t>(4);
    // conjunct over fixed leaf only -> root check; nested && is flattened
    Invariant nested = (Invariant(var("fixed") == 4) && Invariant(var("free") > 1)) && Invariant(var("free") < 4);
    Synthesizer ok(base, {{"free", Domain::range(0, 9)}}, {nested});
    EXPECT_EQ(ok.count(), 2u);
    Synthesizer bad(base, {{"free", Domain::range(0, 9)}}, {Invariant(var("fixed") == 5)});
    EXPECT_EQ(bad.count(), 0u);
    EXPECT_TRUE(bad.stats().exhausted);
    EXPECT_FALSE(bad.nearest(base).has_value());
    EXPECT_FALSE(bad.min_conflicts(10).has_value());
    EXPECT_THROW(bad.nearest(Value(reg.get("i32"))), std::invalid_argument);
    EXPECT_THROW(Synthesizer(Value(reg.get("Pair")), {}, {}), std::invalid_argument);
}

TEST(SynthesizerCoverage, NearestCountsFixedLeavesAndRespectsBudget) {
    value::TypeRegistry reg;
    reg.define_primitive("tag", 2);
    auto t = reg.define_struct("NF", {{"k", "tag"}, {"a", "i16"}, {"b", "i16"}});
    Value base(t);
    base.reference("a").set<std::int16_t>(10);
    Synthesizer s(base, {{"b", Domain::range(0, 20)}}, {Invariant(var("b") >= 7)});
    Value target(t);
    target.reference("b").set<std::int16_t>(3);
    const std::array<std::byte, 2> raw{std::byte{1}, std::byte{2}};
    target.reference("k").insert(raw);
    auto best = s.nearest(target, Norm::L1);
    ASSERT_TRUE(best);
    EXPECT_EQ(best->reference("b").get<std::int16_t>(), 7);
    EXPECT_DOUBLE_EQ(distance(*best, target, Norm::L1), 10 + 4 + 1);  // a fixed, b moved, opaque differs
    Synthesizer tiny(base, {{"b", Domain::range(0, 20)}}, {Invariant(var("b") >= 19)},
                     SynthesisOptions{.max_nodes = 1});
    tiny.nearest(target, Norm::L2);
    EXPECT_FALSE(tiny.stats().exhausted);
    EXPECT_THROW(Synthesizer(base, {{"k", Domain{1}}}, {}), std::invalid_argument);  // opaque leaf as decision
}

TEST(SynthesizerCoverage, MinConflictsWithLargeDomainsAndNoise) {
    value::TypeRegistry reg;
    auto t = reg.get("i32[3]");
    std::vector<Invariant> invs{Invariant(var("[0]") + var("[1]") == 150), Invariant(var("[1]") - var("[2]") == 20),
                                Invariant(var("[2]") > 40)};
    auto space = ConstrainedSpace::over_all_leaves("lin", t, Domain::range(0, 199), invs);
    Synthesizer s = space->synthesizer(SynthesisOptions{.seed = 3});
    auto sol = s.min_conflicts(50000, 0.2);
    ASSERT_TRUE(sol);
    EXPECT_TRUE(space->contains(*sol));
}

// ------------------------------------------- compiled-evaluator oracle ---

namespace {
Expr random_expr(std::mt19937_64& rng, int depth) {
    std::uniform_int_distribution<int> pick(0, depth <= 0 ? 1 : 8);
    std::uniform_int_distribution<int> leaf(0, 3), konst(-3, 6);
    switch (pick(rng)) {
        case 0: return Expr(static_cast<double>(konst(rng)));
        case 1: return var("[" + std::to_string(leaf(rng)) + "]");
        case 2: return random_expr(rng, depth - 1) + random_expr(rng, depth - 1);
        case 3: return random_expr(rng, depth - 1) - random_expr(rng, depth - 1);
        case 4: return random_expr(rng, depth - 1) * random_expr(rng, depth - 1);
        case 5: return -random_expr(rng, depth - 1);
        case 6: return abs(random_expr(rng, depth - 1));
        case 7: return min(random_expr(rng, depth - 1), random_expr(rng, depth - 1));
        default: return max(random_expr(rng, depth - 1), random_expr(rng, depth - 1));
    }
}

Constraint random_formula(std::mt19937_64& rng, int depth) {
    std::uniform_int_distribution<int> pick(0, depth <= 0 ? 5 : 10);
    const int k = pick(rng);
    if (k <= 5) {
        static const Constraint::Op ops[] = {Constraint::Op::Eq, Constraint::Op::Ne, Constraint::Op::Lt,
                                             Constraint::Op::Le, Constraint::Op::Gt, Constraint::Op::Ge};
        return Constraint::compare(ops[k], random_expr(rng, 2), random_expr(rng, 2));
    }
    if (k == 6) return random_formula(rng, depth - 1) && random_formula(rng, depth - 1);
    if (k == 7) return random_formula(rng, depth - 1) || random_formula(rng, depth - 1);
    if (k == 8) return !random_formula(rng, depth - 1);
    if (k == 9) return Constraint::always();
    return Constraint::never() || random_formula(rng, depth - 1);
}
}  // namespace

TEST(CompiledEvaluator, CountsMatchBruteForceOnRandomFormulas) {
    // The synthesizer evaluates compiled formulas and prunes with compiled
    // interval bounds; brute force uses the reference AST evaluator. Any
    // evaluation mismatch or unsound pruning changes the count.
    value::TypeRegistry reg;
    auto t = reg.get("i8[4]");
    const Domain dom = Domain::range(-2, 3);
    std::mt19937_64 rng(2024);
    for (int trial = 0; trial < 150; ++trial) {
        std::vector<Invariant> invs;
        const int n = 1 + static_cast<int>(rng() % 3);
        for (int i = 0; i < n; ++i) invs.emplace_back(random_formula(rng, 3));
        std::vector<DecisionLeaf> leaves;
        for (int i = 0; i < 4; ++i) leaves.push_back({"[" + std::to_string(i) + "]", dom});

        std::size_t brute = 0;
        Value v(t);
        for (double a : dom.values)
            for (double b : dom.values)
                for (double c : dom.values)
                    for (double d : dom.values) {
                        v.set_scalar(0, a);
                        v.set_scalar(1, b);
                        v.set_scalar(2, c);
                        v.set_scalar(3, d);
                        bool ok = true;
                        for (const auto& inv : invs) ok = ok && inv.holds(v);
                        brute += ok;
                    }
        for (bool pruning : {true, false}) {
            Synthesizer s(Value(t), leaves, invs, SynthesisOptions{.interval_pruning = pruning});
            EXPECT_EQ(s.count(), brute) << "trial " << trial << " pruning=" << pruning << " "
                                        << invs.front().description();
        }
        // min-conflicts / nearest results are members when found
        Synthesizer s(Value(t), leaves, invs, SynthesisOptions{.seed = static_cast<std::uint64_t>(trial)});
        if (auto m = s.min_conflicts(500)) {
            for (const auto& inv : invs) EXPECT_TRUE(inv.holds(*m));
        }
        if (auto p = Synthesizer(Value(t), leaves, invs).nearest(Value(t), Norm::L1)) {
            for (const auto& inv : invs) EXPECT_TRUE(inv.holds(*p));
            EXPECT_GT(brute, 0u);
        } else {
            EXPECT_EQ(brute, 0u);
        }
    }
}

// --------------------------------------------------------------- misc ---

TEST(MiscCoverage, NormNamesAndDomainErrors) {
    EXPECT_EQ(norm_name(Norm::L1), "L1");
    EXPECT_EQ(norm_name(Norm::L2), "L2");
    EXPECT_EQ(norm_name(Norm::LInf), "Linf");
    EXPECT_EQ(norm_name(Norm::Hamming), "Hamming");
    EXPECT_THROW(Domain{}.lo(), std::logic_error);
    EXPECT_THROW(Domain{}.hi(), std::logic_error);
    EXPECT_THROW(Domain({std::nan("")}), std::invalid_argument);
    EXPECT_THROW(Domain::range(0, 1e12), std::length_error);
    EXPECT_EQ(Domain::of_prim(Prim::I8).lo(), -128);
    value::TypeRegistry reg;
    Value nanv(reg.get("f64[1]")), zero(reg.get("f64[1]"));
    nanv.set_scalar(0, std::nan(""));
    EXPECT_TRUE(std::isinf(distance(nanv, zero, Norm::L2)));
}

TEST(MiscCoverage, SmtErrorPaths) {
    value::TypeRegistry reg;
    auto t = reg.define_struct("E", {{"p", "u8[2]"}, {"w", "f64"}});
    EXPECT_THROW(to_smtlib(t, var("p") == 1), std::invalid_argument);       // not a scalar leaf
    EXPECT_THROW(smt_symbol("a|b"), std::invalid_argument);
    EXPECT_EQ(smt_symbol(""), "|$|");
    EXPECT_THROW(parse_smt_model("((define-fun |w| () Real abc))"), std::invalid_argument);
    EXPECT_THROW(parse_smt_model("(\"unterminated"), std::invalid_argument);
    EXPECT_THROW(parse_smt_model("(|unterminated"), std::invalid_argument);
    EXPECT_THROW(parse_smt_model(")"), std::invalid_argument);
    auto m = parse_smt_model("; comment\n((define-fun |w| () Real (+ 1.0 (to_real 2) (- 5.0 1.5))) \"s\")");
    EXPECT_DOUBLE_EQ(m["w"], 6.5);
    SmtOptions o;
    o.bounds["w"] = {-1.5, 2.5};
    o.bounds["p[0]"] = {1.2, 3.8};
    o.produce_model = false;
    SmtScript s = lower_to_smtlib(t, {}, o);
    EXPECT_NE(s.text.find("(assert (<= (- 1.5) |w|))"), std::string::npos);
    EXPECT_NE(s.text.find("(assert (<= 2 |p[0]|))"), std::string::npos);
    EXPECT_NE(s.text.find("(assert (<= |p[0]| 3))"), std::string::npos);
    EXPECT_EQ(s.text.find("get-model"), std::string::npos);
}

TEST(MiscCoverage, TraceThroughEndOfProgramAndHalt) {
    automaton::Machine m(32);
    automaton::TransitionSpace ts(m, {automaton::Op::Addi});
    automaton::Program p = automaton::assemble("addi r0 r0 1");
    std::vector<Value> trace{m.initial_state()};
    for (int i = 0; i < 3; ++i) {
        Value n = trace.back().clone();
        m.step(n, p);
        trace.push_back(n);
    }
    EXPECT_TRUE(ts.valid_trace(trace, p));                  // includes the end-of-program step and a halted step
    trace[3].reference("regs[0]").set<std::int64_t>(99);   // halted states must not change
    EXPECT_FALSE(ts.valid_trace(trace, p));
    Rng rng(2);
    auto t = ts.generate(rng);
    ASSERT_TRUE(t);
    EXPECT_EQ(m.decode(t->reference_member("instr")).op, automaton::Op::Addi);
    EXPECT_FALSE(ts.project(Value(m.state_type())).has_value());
    EXPECT_EQ(ts.description(), "machine step transitions");
}
