#pragma once
// N-qubit state vectors as Values, unitary gates as value transformations,
// and the space of normalised states.
//
// A state is a Value of type f64[2^N, 2] (row i = basis state |i>, columns =
// real and imaginary part). Qubit q is bit q of the basis index
// (little-endian), so |q1 q0> = |10> is index 2.
//
// Gates mutate the state in place through the Value's bytes; they never copy
// the amplitude tensor. apply_unitary() is the general k-qubit tensor
// contraction; the named gates are specialised fast paths of it.

#include "combinatorial/problem/Space.hpp"
#include "combinatorial/value/Value.hpp"

#include <array>
#include <complex>
#include <cstdint>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace combinatorial::quantum {

using Complex = std::complex<double>;
using Matrix2 = std::array<Complex, 4>;  // row-major [[m00, m01], [m10, m11]]

constexpr std::size_t kMaxQubits = 26;

value::TypePtr state_type(std::size_t qubits);
/// |0...0>
value::Value make_state(std::size_t qubits);
/// Computational basis state |index>.
value::Value basis_state(std::size_t qubits, std::size_t index);
/// Number of qubits of a state value (validates the layout).
std::size_t qubit_count(const value::Value& state);

Complex amplitude(const value::Value& state, std::size_t index);
void set_amplitude(const value::Value& state, std::size_t index, Complex a);
std::vector<Complex> amplitudes(const value::Value& state);

double norm_squared(const value::Value& state);
void normalize(const value::Value& state);
/// <a|b>
Complex inner(const value::Value& a, const value::Value& b);
/// |<a|b>|^2
double fidelity(const value::Value& a, const value::Value& b);
std::vector<double> probabilities(const value::Value& state);

// ---- gates -------------------------------------------------------------------
namespace gates {
Matrix2 H();
Matrix2 X();
Matrix2 Y();
Matrix2 Z();
Matrix2 S();
Matrix2 T();
Matrix2 phase(double theta);
Matrix2 rx(double theta);
Matrix2 ry(double theta);
Matrix2 rz(double theta);
}  // namespace gates

/// Applies a 2x2 unitary to `target`, conditioned on every qubit in `controls_mask` being 1.
void apply_1q(const value::Value& state, std::size_t target, const Matrix2& m, std::uint64_t controls_mask = 0);
void hadamard(const value::Value& state, std::size_t q);
void cnot(const value::Value& state, std::size_t control, std::size_t target);
void cz(const value::Value& state, std::size_t a, std::size_t b);
void swap(const value::Value& state, std::size_t a, std::size_t b);
void toffoli(const value::Value& state, std::size_t c0, std::size_t c1, std::size_t target);
/// General k-qubit unitary (2^k x 2^k, row-major) on `qubits` (qubits[0] = least significant).
void apply_unitary(const value::Value& state, const std::vector<std::size_t>& qubits, const std::vector<Complex>& u);

// ---- measurement ----------------------------------------------------------------
/// Measures one qubit, collapsing and renormalising the state.
int measure(const value::Value& state, std::size_t q, std::mt19937_64& rng);
/// Samples a basis index without collapsing.
std::size_t sample(const value::Value& state, std::mt19937_64& rng);
/// Probability that qubit q reads 1.
double probability_one(const value::Value& state, std::size_t q);

// ---- entanglement -----------------------------------------------------------------
/// Purity Tr(rho_A^2) of the reduced state on the qubits in `subsystem_mask`.
double purity(const value::Value& state, std::uint64_t subsystem_mask);
/// 1 - purity: 0 for product states across the cut, > 0 if entangled.
double linear_entropy(const value::Value& state, std::uint64_t subsystem_mask);

// ---- circuits -------------------------------------------------------------------
struct Gate {
    enum class Kind : std::uint8_t { H, X, Y, Z, S, T, Sdg, Tdg, Phase, Rx, Ry, Rz, CNOT, CZ, CPhase, Swap, Toffoli };
    Kind kind;
    std::vector<std::size_t> qubits;
    double theta = 0;

    std::string to_string() const;
    Gate inverse() const;
    bool operator==(const Gate&) const = default;
};

class Circuit {
public:
    explicit Circuit(std::size_t qubits) : qubits_(qubits) {}
    std::size_t qubits() const noexcept { return qubits_; }
    const std::vector<Gate>& gates() const noexcept { return gates_; }
    std::size_t size() const noexcept { return gates_.size(); }

    Circuit& add(Gate g);
    Circuit& h(std::size_t q) { return add({Gate::Kind::H, {q}}); }
    Circuit& x(std::size_t q) { return add({Gate::Kind::X, {q}}); }
    Circuit& z(std::size_t q) { return add({Gate::Kind::Z, {q}}); }
    Circuit& s(std::size_t q) { return add({Gate::Kind::S, {q}}); }
    Circuit& t(std::size_t q) { return add({Gate::Kind::T, {q}}); }
    Circuit& phase(std::size_t q, double th) { return add({Gate::Kind::Phase, {q}, th}); }
    Circuit& rx(std::size_t q, double th) { return add({Gate::Kind::Rx, {q}, th}); }
    Circuit& ry(std::size_t q, double th) { return add({Gate::Kind::Ry, {q}, th}); }
    Circuit& rz(std::size_t q, double th) { return add({Gate::Kind::Rz, {q}, th}); }
    Circuit& cnot(std::size_t c, std::size_t t) { return add({Gate::Kind::CNOT, {c, t}}); }
    Circuit& cz(std::size_t a, std::size_t b) { return add({Gate::Kind::CZ, {a, b}}); }
    Circuit& cphase(std::size_t c, std::size_t t, double th) { return add({Gate::Kind::CPhase, {c, t}, th}); }
    Circuit& swap(std::size_t a, std::size_t b) { return add({Gate::Kind::Swap, {a, b}}); }
    Circuit& toffoli(std::size_t a, std::size_t b, std::size_t t) { return add({Gate::Kind::Toffoli, {a, b, t}}); }

    void apply(const value::Value& state) const;
    value::Value run() const;                     ///< applied to |0...0>
    Circuit inverse() const;
    /// Dense 2^n x 2^n unitary (row-major), for small circuits.
    std::vector<Complex> unitary() const;
    std::string to_string() const;

    static Circuit ghz(std::size_t n);
    static Circuit qft(std::size_t n);

private:
    std::size_t qubits_;
    std::vector<Gate> gates_;
};

void apply(const value::Value& state, const Gate& g);
/// U U^dagger == I within tolerance.
bool is_unitary(const std::vector<Complex>& u, double tol = 1e-9);

/// Shortest circuit over `alphabet` mapping |0..0> to `target` up to global
/// phase (fidelity >= 1 - tol), by iterative deepening with state dedupe.
std::optional<Circuit> synthesize_circuit(const value::Value& target, const std::vector<Gate>& alphabet,
                                          std::size_t max_depth, double tol = 1e-9,
                                          std::uint64_t* explored = nullptr);

// ---- spaces -------------------------------------------------------------------------
/// Normalised N-qubit states. Generation draws Haar-random states;
/// projection is renormalisation (the exact L2-nearest unit vector).
class StateSpace : public problem::Space {
public:
    explicit StateSpace(std::size_t qubits, double tolerance = 1e-9);
    std::string description() const override;
    value::TypePtr value_type() const override { return type_; }
    std::vector<problem::Invariant> structural_invariants() const override;
    std::vector<problem::Invariant> semantic_invariants() const override;
    std::optional<value::Value> generate(problem::Rng& rng) const override;
    std::optional<value::Value> project(const value::Value& v) const override;
    std::size_t qubits() const noexcept { return qubits_; }

private:
    std::size_t qubits_;
    double tolerance_;
    value::TypePtr type_;
};

/// Invariant: state is normalised.
problem::Invariant normalization_invariant(double tolerance = 1e-9);
/// Invariant: linear entanglement entropy across `mask` is at most `bound`.
problem::Invariant entanglement_bound(std::uint64_t subsystem_mask, double bound);
/// Invariant over {before, after} pairs of states: `circuit` maps before to after.
problem::Invariant unitary_transition(const Circuit& circuit, double tolerance = 1e-9);
value::TypePtr transition_type(std::size_t qubits);

}  // namespace combinatorial::quantum
