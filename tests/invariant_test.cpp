#include "alg_exp/problem.hpp"
#include "alg_exp/value.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <map>
#include <random>

using namespace alg_exp;
using namespace alg_exp::problem;
using value::Value;

namespace {

struct InvariantTest : ::testing::Test {
    value::TypeRegistry reg;
    value::TypePtr point = reg.define_struct("Point", {{"x", "i32"}, {"y", "i32"}});
    value::TypePtr box = reg.define_struct("Box", {{"lo", "Point"}, {"hi", "Point"}, {"w", "f64"}, {"v", "u8[3]"}});

    Value make_point(int x, int y) {
        Value p(point);
        p.reference("x").set<std::int32_t>(x);
        p.reference("y").set<std::int32_t>(y);
        return p;
    }

    Invariant x_positive{"x > 0", [](const Value& v) { return v.reference("x").get<std::int32_t>() > 0; },
                         [](const Value& v) { return "x = " + std::to_string(v.reference("x").get<std::int32_t>()); },
                         {"x"}};
    Invariant y_positive{"y > 0", [](const Value& v) { return v.reference("y").get<std::int32_t>() > 0; }};
};

}  // namespace

// ---------------------------------------------------- predicate trees ---

TEST_F(InvariantTest, LeafPredicates) {
    EXPECT_TRUE(x_positive.holds(make_point(1, 0)));
    EXPECT_FALSE(x_positive.holds(make_point(0, 0)));
    EXPECT_EQ(x_positive.explain(make_point(1, 0)), std::nullopt);
    EXPECT_EQ(*x_positive.explain(make_point(-3, 0)), "x > 0: x = -3");
    EXPECT_EQ(*y_positive.explain(make_point(1, 0)), "y > 0 violated");
}

TEST_F(InvariantTest, AndOrNotTruthTables) {
    const Invariant both = x_positive && y_positive;
    const Invariant either = x_positive || y_positive;
    const Invariant not_x = !x_positive;
    for (int x : {-1, 1})
        for (int y : {-1, 1}) {
            Value p = make_point(x, y);
            EXPECT_EQ(both.holds(p), x > 0 && y > 0);
            EXPECT_EQ(either.holds(p), x > 0 || y > 0);
            EXPECT_EQ(not_x.holds(p), !(x > 0));
            EXPECT_EQ((!(x_positive && y_positive)).holds(p), (!x_positive || !y_positive).holds(p));  // De Morgan
        }
    EXPECT_EQ(both.description(), "(x > 0 && y > 0)");
    EXPECT_EQ(either.description(), "(x > 0 || y > 0)");
    EXPECT_EQ(not_x.description(), "!x > 0");
}

TEST_F(InvariantTest, CompositionFlattensAndCopiesSafely) {
    Invariant chain = x_positive && y_positive;
    chain = chain && x_positive;  // operands are captured by value, not by `this`
    EXPECT_EQ(chain.kind(), Invariant::Kind::And);
    EXPECT_EQ(chain.children().size(), 3u);
    Invariant copy = chain;
    EXPECT_TRUE(copy.holds(make_point(2, 2)));
    EXPECT_EQ(Invariant::all_of({}).holds(make_point(0, 0)), true);
    EXPECT_EQ(Invariant::any_of({}).holds(make_point(0, 0)), false);
}

TEST_F(InvariantTest, DiagnosticsReportFirstCauseAndAllViolations) {
    const Invariant both = x_positive && y_positive;
    Value bad = make_point(-1, -1);
    EXPECT_EQ(*both.explain(bad), "x > 0: x = -1");
    auto all = both.violations(bad);
    ASSERT_EQ(all.size(), 2u);
    EXPECT_EQ(all[1], "y > 0 violated");
    EXPECT_NE((x_positive || y_positive).explain(bad)->find("none of 2 alternatives"), std::string::npos);
    EXPECT_NE((!x_positive).explain(make_point(1, 1))->find("expected NOT x > 0"), std::string::npos);
}

TEST_F(InvariantTest, DependenciesPropagateThroughTree) {
    EXPECT_EQ(*x_positive.dependencies(), (std::set<std::string>{"x"}));
    EXPECT_EQ(y_positive.dependencies(), std::nullopt);
    EXPECT_EQ((x_positive && y_positive).dependencies(), std::nullopt);
    Invariant sym(var("x") + var("y") <= 10);
    EXPECT_EQ(*(x_positive && sym).dependencies(), (std::set<std::string>{"x", "y"}));
}

// ----------------------------------------------------------- symbolic ---

TEST_F(InvariantTest, SymbolicConstraintsEvaluate) {
    Constraint c = (var("x") + 2 * var("y") <= 10) && (var("x") != var("y"));
    EXPECT_TRUE(c.eval(make_point(2, 4)));
    EXPECT_FALSE(c.eval(make_point(4, 4)));
    EXPECT_FALSE(c.eval(make_point(3, 4)));
    EXPECT_EQ(c.variables(), (std::set<std::string>{"x", "y"}));
    Invariant inv("budget", c);
    EXPECT_EQ(*inv.explain(make_point(3, 4)), "budget: (x + (2 * y)) <= 10 violated: 11 <= 10 is false");
}

TEST_F(InvariantTest, ExpressionOperators) {
    Value p = make_point(-3, 5);
    ValueEnv env(p);
    EXPECT_DOUBLE_EQ(abs(var("x")).eval(env), 3);
    EXPECT_DOUBLE_EQ(min(var("x"), var("y")).eval(env), -3);
    EXPECT_DOUBLE_EQ(max(var("x"), var("y")).eval(env), 5);
    EXPECT_DOUBLE_EQ((-var("x") * var("y") - 1).eval(env), 14);
    EXPECT_TRUE(Constraint::between(var("y"), 5, 5).eval(env));
    EXPECT_TRUE(Constraint::all_different({var("x"), var("y"), Expr(0)}).eval(env));
    EXPECT_FALSE(Constraint::all_different({var("x"), var("y"), Expr(5)}).eval(env));
    EXPECT_THROW(Constraint(var("lo") == 1).eval(Value(box)), std::invalid_argument);  // not a scalar leaf
}

TEST_F(InvariantTest, IntervalFeasibilityIsSound) {
    struct Ranges : Env {
        std::map<std::string, Interval, std::less<>> r;
        double value(std::string_view p) const override { return r.find(p)->second.lo; }
        Interval range(std::string_view p) const override { return r.find(p)->second; }
    } env;
    env.r["x"] = {0, 3};
    env.r["y"] = {5, 9};
    EXPECT_EQ((var("x") < var("y")).feasible(env), Tri::True);
    EXPECT_EQ((var("x") > var("y")).feasible(env), Tri::False);
    EXPECT_EQ((var("x") + var("y") == 10).feasible(env), Tri::Unknown);
    EXPECT_EQ((var("x") + var("y") == 20).feasible(env), Tri::False);
    EXPECT_EQ((var("x") * var("y") <= 27).feasible(env), Tri::True);
    EXPECT_EQ((!(var("x") * var("y") <= 27)).feasible(env), Tri::False);
    EXPECT_EQ(((var("x") > 5) || (var("y") > 8)).feasible(env), Tri::Unknown);
    EXPECT_EQ(((var("x") > 5) && (var("y") > 8)).feasible(env), Tri::False);
    EXPECT_EQ((abs(var("x") - 4) >= 1).feasible(env), Tri::True);
    env.r["x"] = {2, 2};
    env.r["y"] = {2, 2};
    EXPECT_EQ((var("x") == var("y")).feasible(env), Tri::True);
    EXPECT_EQ((var("x") != var("y")).feasible(env), Tri::False);
    Invariant opaque("opaque", [](const Value&) { return true; });
    EXPECT_EQ(opaque.feasible(env), Tri::Unknown);
    EXPECT_EQ((opaque && Invariant(var("x") > 5)).feasible(env), Tri::False);
}

TEST_F(InvariantTest, IntervalBoundsContainEveryPointSample) {
    // Property: for random point assignments inside the boxes, the concrete
    // value of a term lies inside its interval bound.
    std::mt19937 rng(7);
    std::uniform_real_distribution<double> u(-10, 10);
    const Expr terms[] = {var("a") * var("b") - var("a"), abs(var("a") - var("b")) * -2,
                          min(var("a"), var("b") * var("b")), max(-var("a"), var("b") + 3)};
    for (int trial = 0; trial < 500; ++trial) {
        double a0 = u(rng), a1 = u(rng), b0 = u(rng), b1 = u(rng);
        Interval A{std::min(a0, a1), std::max(a0, a1)}, B{std::min(b0, b1), std::max(b0, b1)};
        std::uniform_real_distribution<double> ua(A.lo, A.hi), ub(B.lo, B.hi);
        struct E : Env {
            Interval A, B;
            double a, b;
            double value(std::string_view p) const override { return p == "a" ? a : b; }
            Interval range(std::string_view p) const override { return p == "a" ? A : B; }
        } env;
        env.A = A;
        env.B = B;
        env.a = ua(rng);
        env.b = ub(rng);
        for (const Expr& t : terms) {
            Interval bound = t.bounds(env);
            const double v = t.eval(env);
            EXPECT_LE(bound.lo, v + 1e-9);
            EXPECT_GE(bound.hi, v - 1e-9);
        }
    }
}

TEST_F(InvariantTest, MixedTreeLowersOnlyWhenFullySymbolic) {
    Invariant sym = Invariant(var("x") >= 0) && !Invariant(var("y") == 3);
    ASSERT_TRUE(sym.constraint().has_value());
    EXPECT_TRUE(sym.constraint()->eval(make_point(1, 2)));
    EXPECT_FALSE(sym.constraint()->eval(make_point(1, 3)));
    EXPECT_FALSE((sym && y_positive).constraint().has_value());
}

// ---------------------------------------------------------------- SMT ---

TEST_F(InvariantTest, SmtLoweringText) {
    Constraint c = (var("lo.x") + 2 <= var("hi.x")) && (var("w") * 2 > 1.5) && (abs(var("v[1]")) != 7);
    const std::string term = to_smtlib(box, c);
    EXPECT_EQ(term,
              "(and (<= (+ |lo.x| 2) |hi.x|) (> (* |w| 2.0) 1.5) "
              "(not (= (ite (>= |v[1]| 0) |v[1]| (- |v[1]|)) 7)))");
    SmtScript s = lower_to_smtlib(box, {Invariant(c), y_positive});
    EXPECT_EQ(s.lowered_invariants, 1u);
    EXPECT_EQ(s.skipped_invariants, 1u);
    EXPECT_NE(s.text.find("(declare-const |w| Real)"), std::string::npos);
    EXPECT_NE(s.text.find("(declare-const |v[1]| Int)"), std::string::npos);
    EXPECT_NE(s.text.find("(assert (and (<= 0 |v[1]|) (<= |v[1]| 255)))"), std::string::npos);
    EXPECT_NE(s.text.find("(assert (and (<= (- 2147483648) |lo.x|)"), std::string::npos);
    EXPECT_EQ(s.variables, (std::vector<std::string>{"hi.x", "lo.x", "v[1]", "w"}));
}

TEST_F(InvariantTest, SmtLiteralsAreExactDecimals) {
    // shortest decimal that round-trips to the same double
    EXPECT_EQ(to_smtlib(box, var("w") == 0.1), "(= |w| 0.1)");
    EXPECT_EQ(to_smtlib(box, var("w") == -1e-3), "(= |w| (- 0.001))");
    EXPECT_EQ(to_smtlib(box, var("w") == 1e21), "(= |w| 1000000000000000000000.0)");
    EXPECT_EQ(to_smtlib(box, var("lo.x") == -5), "(= |lo.x| (- 5))");
    EXPECT_EQ(to_smtlib(box, var("lo.x") < var("w")), "(< (to_real |lo.x|) |w|)");
    EXPECT_THROW(to_smtlib(box, var("w") == std::nan("")), std::invalid_argument);
    EXPECT_THROW(to_smtlib(box, var("nope") == 1), std::invalid_argument);
}

TEST_F(InvariantTest, ModelParsing) {
    const std::string out = R"(sat
(
  (define-fun |lo.x| () Int
    (- 4))
  (define-fun |w| () Real
    (/ 3.0 4.0))
  (define-fun |v[2]| () Int 200)
  (define-fun |hi.y| () Int (- 0 1))
)
)";
    auto m = parse_smt_model(std::string_view(out).substr(3));
    ASSERT_EQ(m.size(), 4u);
    EXPECT_DOUBLE_EQ(m["lo.x"], -4);
    EXPECT_DOUBLE_EQ(m["w"], 0.75);
    EXPECT_DOUBLE_EQ(m["hi.y"], -1);
    Value v = apply_model(box, m);
    EXPECT_EQ(v.reference("v[2]").get<std::uint8_t>(), 200);
    EXPECT_DOUBLE_EQ(v.reference("w").get<double>(), 0.75);
    EXPECT_THROW(parse_smt_model("((define-fun |x| () Int (foo 1)))"), std::invalid_argument);
    EXPECT_THROW(parse_smt_model("((define-fun |x| () Int 1)"), std::invalid_argument);
}

#ifdef ALG_EXP_Z3_PATH
TEST_F(InvariantTest, Z3RoundTripProducesSatisfyingValue) {
    const std::vector<Invariant> invs = {
        Invariant(var("lo.x") + 3 <= var("hi.x")),
        Invariant(var("lo.y") * var("lo.y") == 49),
        Invariant(var("hi.x") < 0),
        Invariant(var("v[0]") + var("v[1]") + var("v[2]") == 700),
        Invariant(Constraint::all_different({var("v[0]"), var("v[1]"), var("v[2]")})),
        Invariant(var("w") * 3 == 1),
    };
    SmtResult r = solve_smt(box, invs, ALG_EXP_Z3_PATH);
    ASSERT_EQ(r.status, SmtResult::Status::Sat) << r.raw_output;
    ASSERT_TRUE(r.model.has_value());
    for (std::size_t i = 0; i < invs.size() - 1; ++i) EXPECT_TRUE(invs[i].holds(*r.model)) << invs[i].description();
    EXPECT_NEAR(r.model->reference("w").get<double>(), 1.0 / 3.0, 1e-12);  // exact rational rounded to double
}

TEST_F(InvariantTest, Z3DetectsUnsat) {
    // u8 range bound makes this infeasible: v[0] > 255
    SmtResult r = solve_smt(box, {Invariant(var("v[0]") > 255)}, ALG_EXP_Z3_PATH);
    EXPECT_EQ(r.status, SmtResult::Status::Unsat) << r.raw_output;
    SmtOptions loose;
    loose.include_type_bounds = false;
    EXPECT_EQ(solve_smt(box, {Invariant(var("v[0]") > 255)}, ALG_EXP_Z3_PATH, loose).status,
              SmtResult::Status::Sat);
}
#endif

TEST_F(InvariantTest, MissingSolverReportsUnavailable) {
    SmtResult r = solve_smt(box, {Invariant(var("w") > 0)}, "/nonexistent/solver");
    EXPECT_NE(r.status, SmtResult::Status::Sat);
}
