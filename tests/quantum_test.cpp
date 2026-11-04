#include "alg_exp/quantum/Quantum.hpp"

#include <gtest/gtest.h>

#include <cmath>
#include <numbers>

using namespace alg_exp;
using namespace alg_exp::quantum;
using value::Value;

namespace {
constexpr double kEps = 1e-12;
void expect_amp(const Value& s, std::size_t i, Complex want, double tol = 1e-12) {
    const Complex got = amplitude(s, i);
    EXPECT_NEAR(got.real(), want.real(), tol) << "index " << i;
    EXPECT_NEAR(got.imag(), want.imag(), tol) << "index " << i;
}
}  // namespace

TEST(QuantumTest, StateLayout) {
    Value s = make_state(3);
    EXPECT_EQ(s.type()->shape(), (std::vector<std::size_t>{8, 2}));
    EXPECT_EQ(s.size(), 8u * 16u);
    EXPECT_EQ(qubit_count(s), 3u);
    EXPECT_DOUBLE_EQ(s.at({0, 0}).get<double>(), 1.0);
    set_amplitude(s, 5, {0.25, -0.5});
    EXPECT_DOUBLE_EQ(s.reference("[5,1]").get<double>(), -0.5);  // imaginary part, via path
    EXPECT_THROW(qubit_count(Value(value::ValueType::primitive("f64", value::Prim::F64, {3, 2}))), std::invalid_argument);
    EXPECT_THROW(qubit_count(Value(value::ValueType::primitive("f64", value::Prim::F64, {4}))), std::invalid_argument);
    EXPECT_THROW(basis_state(2, 4), std::out_of_range);
}

TEST(QuantumTest, HadamardSuperposition) {
    Value s = make_state(1);
    hadamard(s, 0);
    const double r = 1 / std::numbers::sqrt2;
    expect_amp(s, 0, r);
    expect_amp(s, 1, r);
    hadamard(s, 0);
    expect_amp(s, 0, 1);
    expect_amp(s, 1, 0);
}

TEST(QuantumTest, BellStateAndEntanglement) {
    Value s = make_state(2);
    hadamard(s, 0);
    cnot(s, 0, 1);
    const double r = 1 / std::numbers::sqrt2;
    expect_amp(s, 0, r);
    expect_amp(s, 3, r);
    expect_amp(s, 1, 0);
    EXPECT_NEAR(purity(s, 0b01), 0.5, kEps);  // maximally mixed reduced state
    EXPECT_NEAR(linear_entropy(s, 0b10), 0.5, kEps);
    Value prod = make_state(2);
    hadamard(prod, 0);
    hadamard(prod, 1);
    EXPECT_NEAR(linear_entropy(prod, 0b01), 0, kEps);
    EXPECT_FALSE(entanglement_bound(0b01, 0.1).holds(s));
    EXPECT_TRUE(entanglement_bound(0b01, 0.1).holds(prod));
}

TEST(QuantumTest, GhzStatistics) {
    Value s = Circuit::ghz(5).run();
    auto p = probabilities(s);
    EXPECT_NEAR(p[0], 0.5, kEps);
    EXPECT_NEAR(p[31], 0.5, kEps);
    std::mt19937_64 rng(1);
    for (int i = 0; i < 50; ++i) {
        Value c = s.clone();
        const int first = measure(c, 2, rng);
        for (std::size_t q = 0; q < 5; ++q) EXPECT_EQ(measure(c, q, rng), first);  // perfectly correlated
        EXPECT_NEAR(norm_squared(c), 1, kEps);
    }
    int ones = 0;
    for (int i = 0; i < 2000; ++i) ones += sample(s, rng) == 31;
    EXPECT_NEAR(ones / 2000.0, 0.5, 0.05);
}

TEST(QuantumTest, NamedGatesMatchGeneralContraction) {
    std::mt19937_64 rng(3);
    StateSpace space(4);
    const std::vector<std::pair<Gate, std::vector<std::size_t>>> cases = {
        {{Gate::Kind::H, {2}}, {2}},        {{Gate::Kind::Y, {1}}, {1}},          {{Gate::Kind::T, {3}}, {3}},
        {{Gate::Kind::Rx, {0}, 0.7}, {0}},  {{Gate::Kind::CNOT, {3, 1}}, {3, 1}}, {{Gate::Kind::CZ, {0, 2}}, {0, 2}},
        {{Gate::Kind::Swap, {1, 3}}, {1, 3}}, {{Gate::Kind::CPhase, {2, 0}, 1.1}, {2, 0}},
        {{Gate::Kind::Toffoli, {0, 3, 2}}, {0, 3, 2}}};
    for (const auto& [g, qs] : cases) {
        Circuit c(qs.size());
        std::vector<std::size_t> local(qs.size());
        for (std::size_t i = 0; i < qs.size(); ++i) local[i] = i;
        Gate lg = g;
        lg.qubits = local;
        c.add(lg);
        const auto u = c.unitary();
        Value a = *space.generate(rng), b = a.clone();
        apply(a, g);
        apply_unitary(b, qs, u);
        EXPECT_NEAR(fidelity(a, b), 1, 1e-12) << g.to_string();
        EXPECT_NEAR(norm_squared(a), 1, 1e-12) << g.to_string();
    }
}

TEST(QuantumTest, CircuitsAreUnitaryAndInvertible) {
    Circuit c(3);
    c.h(0).cnot(0, 1).t(1).rz(2, 0.3).toffoli(0, 1, 2).s(2).ry(0, -1.2).swap(0, 2).cphase(1, 2, 0.4);
    EXPECT_TRUE(is_unitary(c.unitary()));
    std::mt19937_64 rng(5);
    Value psi = *StateSpace(3).generate(rng), copy = psi.clone();
    c.apply(psi);
    c.inverse().apply(psi);
    EXPECT_NEAR(fidelity(psi, copy), 1, 1e-12);
    EXPECT_FALSE(is_unitary({1, 1, 0, 1}));
    EXPECT_THROW(Circuit(2).cnot(0, 0), std::invalid_argument);
    EXPECT_THROW(Circuit(2).h(2), std::out_of_range);
}

TEST(QuantumTest, QftMatchesDiscreteFourierTransform) {
    for (std::size_t n = 1; n <= 4; ++n) {
        const auto u = Circuit::qft(n).unitary();
        const std::size_t N = std::size_t{1} << n;
        for (std::size_t r = 0; r < N; ++r)
            for (std::size_t c = 0; c < N; ++c) {
                const Complex want = std::polar(1 / std::sqrt(double(N)), 2 * std::numbers::pi * double(r * c) / double(N));
                EXPECT_NEAR(std::abs(u[r * N + c] - want), 0, 1e-12) << n << ":" << r << "," << c;
            }
    }
}

TEST(QuantumTest, StateSpaceInvariantsAndProjection) {
    StateSpace space(3);
    std::mt19937_64 rng(8);
    for (int i = 0; i < 20; ++i) EXPECT_TRUE(space.contains(*space.generate(rng)));
    Value bad = make_state(3);
    set_amplitude(bad, 1, 1.0);  // norm^2 = 2
    EXPECT_FALSE(space.contains(bad));
    EXPECT_NE(space.explain(bad).at(0).find("norm^2 = 2"), std::string::npos);
    Value p = *space.project(bad);
    EXPECT_TRUE(space.contains(p));
    expect_amp(p, 0, 1 / std::numbers::sqrt2);
    // renormalisation is the L2-nearest unit vector: no sampled member is closer
    const double d = space.distance(bad, p);
    for (int i = 0; i < 200; ++i) EXPECT_GE(space.distance(bad, *space.generate(rng)), d - 1e-12);
    EXPECT_TRUE(space.contains(*space.project(Value(state_type(3)))));  // zero vector -> |000>
    set_amplitude(bad, 2, {std::nan(""), 0});
    EXPECT_FALSE(space.contains(bad));
}

TEST(QuantumTest, UnitaryTransitionInvariant) {
    Circuit c = Circuit::ghz(3);
    auto tt = transition_type(3);
    Value t(tt);
    t.reference_member("before").copy_from(make_state(3));
    t.reference_member("after").copy_from(c.run());
    auto inv = unitary_transition(c);
    EXPECT_TRUE(inv.holds(t));
    t.reference_member("after").copy_from(make_state(3));
    EXPECT_FALSE(inv.holds(t));
    EXPECT_NE(inv.explain(t)->find("fidelity"), std::string::npos);
}

TEST(QuantumTest, CircuitSynthesisFindsBellPreparation) {
    Value bell = make_state(2);
    hadamard(bell, 0);
    cnot(bell, 0, 1);
    std::vector<Gate> alphabet{{Gate::Kind::H, {0}}, {Gate::Kind::H, {1}}, {Gate::Kind::X, {0}},
                               {Gate::Kind::X, {1}}, {Gate::Kind::CNOT, {0, 1}}, {Gate::Kind::CNOT, {1, 0}},
                               {Gate::Kind::T, {0}}};
    std::uint64_t explored = 0;
    auto c = synthesize_circuit(bell, alphabet, 4, 1e-9, &explored);
    ASSERT_TRUE(c);
    EXPECT_EQ(c->size(), 2u) << c->to_string();
    EXPECT_NEAR(fidelity(c->run(), bell), 1, 1e-9);
    EXPECT_GT(explored, 0u);
    // GHZ on 3 qubits needs 3 gates
    std::vector<Gate> a3{{Gate::Kind::H, {0}}, {Gate::Kind::CNOT, {0, 1}}, {Gate::Kind::CNOT, {1, 2}},
                         {Gate::Kind::CNOT, {0, 2}}, {Gate::Kind::X, {2}}};
    auto g = synthesize_circuit(Circuit::ghz(3).run(), a3, 4);
    ASSERT_TRUE(g);
    EXPECT_EQ(g->size(), 3u);
    // unreachable with the given alphabet
    EXPECT_FALSE(synthesize_circuit(bell, {{Gate::Kind::X, {0}}, {Gate::Kind::X, {1}}}, 3));
}
