#include "combinatorial/quantum/Quantum.hpp"

#include <algorithm>
#include <bit>
#include <numeric>
#include <cmath>
#include <cstring>
#include <numbers>
#include <sstream>
#include <stdexcept>
#include <unordered_set>

namespace combinatorial::quantum {

namespace {

inline Complex load(const std::byte* base, std::size_t i) {
    double d[2];
    std::memcpy(d, base + 16 * i, 16);
    return {d[0], d[1]};
}
inline void store(std::byte* base, std::size_t i, Complex c) {
    const double d[2] = {c.real(), c.imag()};
    std::memcpy(base + 16 * i, d, 16);
}

void check_qubit(std::size_t n, std::size_t q, const char* where) {
    if (q >= n) throw std::out_of_range(std::string(where) + ": qubit " + std::to_string(q) + " >= " + std::to_string(n));
}

void check_distinct(const std::vector<std::size_t>& qs, std::size_t n, const char* where) {
    for (std::size_t i = 0; i < qs.size(); ++i) {
        check_qubit(n, qs[i], where);
        for (std::size_t j = i + 1; j < qs.size(); ++j)
            if (qs[i] == qs[j]) throw std::invalid_argument(std::string(where) + ": repeated qubit");
    }
}

}  // namespace

value::TypePtr state_type(std::size_t qubits) {
    if (qubits > kMaxQubits) throw std::invalid_argument("state_type: too many qubits");
    return value::ValueType::primitive("f64", value::Prim::F64, {std::size_t{1} << qubits, 2});
}

value::Value make_state(std::size_t qubits) { return basis_state(qubits, 0); }

value::Value basis_state(std::size_t qubits, std::size_t index) {
    value::Value v(state_type(qubits), "psi");
    if (index >= (std::size_t{1} << qubits)) throw std::out_of_range("basis_state: index out of range");
    store(v.data(), index, 1.0);
    return v;
}

std::size_t qubit_count(const value::Value& s) {
    const value::ValueType& t = *s.type();
    if (!t.is_primitive() || t.prim() != value::Prim::F64 || t.rank() != 2 || t.shape()[1] != 2)
        throw std::invalid_argument("quantum: value " + t.signature() + " is not an f64[2^N,2] state");
    const std::size_t dim = t.shape()[0];
    if (dim == 0 || (dim & (dim - 1)) != 0) throw std::invalid_argument("quantum: dimension is not a power of two");
    return static_cast<std::size_t>(std::countr_zero(dim));
}

Complex amplitude(const value::Value& s, std::size_t i) {
    if (i >= (std::size_t{1} << qubit_count(s))) throw std::out_of_range("amplitude: index");
    return load(s.data(), i);
}
void set_amplitude(const value::Value& s, std::size_t i, Complex a) {
    if (i >= (std::size_t{1} << qubit_count(s))) throw std::out_of_range("set_amplitude: index");
    store(s.data(), i, a);
}
std::vector<Complex> amplitudes(const value::Value& s) {
    const std::size_t dim = std::size_t{1} << qubit_count(s);
    std::vector<Complex> out(dim);
    for (std::size_t i = 0; i < dim; ++i) out[i] = load(s.data(), i);
    return out;
}

double norm_squared(const value::Value& s) {
    const std::size_t dim = std::size_t{1} << qubit_count(s);
    double acc = 0;
    for (std::size_t i = 0; i < dim; ++i) acc += std::norm(load(s.data(), i));
    return acc;
}

void normalize(const value::Value& s) {
    const double n2 = norm_squared(s);
    if (!(n2 > 0) || !std::isfinite(n2)) throw std::domain_error("normalize: zero or non-finite state");
    const double k = 1.0 / std::sqrt(n2);
    const std::size_t dim = std::size_t{1} << qubit_count(s);
    for (std::size_t i = 0; i < dim; ++i) store(s.data(), i, load(s.data(), i) * k);
}

Complex inner(const value::Value& a, const value::Value& b) {
    const std::size_t n = qubit_count(a);
    if (qubit_count(b) != n) throw std::invalid_argument("inner: qubit count mismatch");
    Complex acc = 0;
    for (std::size_t i = 0; i < (std::size_t{1} << n); ++i) acc += std::conj(load(a.data(), i)) * load(b.data(), i);
    return acc;
}

double fidelity(const value::Value& a, const value::Value& b) { return std::norm(inner(a, b)); }

std::vector<double> probabilities(const value::Value& s) {
    const std::size_t dim = std::size_t{1} << qubit_count(s);
    std::vector<double> p(dim);
    for (std::size_t i = 0; i < dim; ++i) p[i] = std::norm(load(s.data(), i));
    return p;
}

namespace gates {
using namespace std::complex_literals;
Matrix2 H() {
    const double r = 1.0 / std::numbers::sqrt2;
    return {r, r, r, -r};
}
Matrix2 X() { return {0, 1, 1, 0}; }
Matrix2 Y() { return {0, -1i, 1i, 0}; }
Matrix2 Z() { return {1, 0, 0, -1}; }
Matrix2 S() { return {1, 0, 0, 1i}; }
Matrix2 T() { return {1, 0, 0, std::polar(1.0, std::numbers::pi / 4)}; }
Matrix2 phase(double th) { return {1, 0, 0, std::polar(1.0, th)}; }
Matrix2 rx(double th) {
    const double c = std::cos(th / 2), s = std::sin(th / 2);
    return {c, -1i * s, -1i * s, c};
}
Matrix2 ry(double th) {
    const double c = std::cos(th / 2), s = std::sin(th / 2);
    return {c, -s, s, c};
}
Matrix2 rz(double th) { return {std::polar(1.0, -th / 2), 0, 0, std::polar(1.0, th / 2)}; }
}  // namespace gates

void apply_1q(const value::Value& s, std::size_t target, const Matrix2& m, std::uint64_t controls) {
    const std::size_t n = qubit_count(s);
    check_qubit(n, target, "apply_1q");
    if (controls >> n) throw std::out_of_range("apply_1q: control mask exceeds qubit count");
    if (controls & (std::uint64_t{1} << target)) throw std::invalid_argument("apply_1q: target is also a control");
    std::byte* p = s.data();
    const std::size_t dim = std::size_t{1} << n, stride = std::size_t{1} << target;
    for (std::size_t base = 0; base < dim; base += 2 * stride) {
        for (std::size_t k = 0; k < stride; ++k) {
            const std::size_t i = base + k;
            if ((i & controls) != controls) continue;
            const std::size_t j = i | stride;
            const Complex a0 = load(p, i), a1 = load(p, j);
            store(p, i, m[0] * a0 + m[1] * a1);
            store(p, j, m[2] * a0 + m[3] * a1);
        }
    }
}

void hadamard(const value::Value& s, std::size_t q) { apply_1q(s, q, gates::H()); }

void cnot(const value::Value& s, std::size_t control, std::size_t target) {
    const std::size_t n = qubit_count(s);
    check_distinct({control, target}, n, "cnot");
    std::byte* p = s.data();
    const std::size_t dim = std::size_t{1} << n, cb = std::size_t{1} << control, tb = std::size_t{1} << target;
    for (std::size_t i = 0; i < dim; ++i) {
        if ((i & cb) && !(i & tb)) {
            const Complex a = load(p, i), b = load(p, i | tb);
            store(p, i, b);
            store(p, i | tb, a);
        }
    }
}

void cz(const value::Value& s, std::size_t a, std::size_t b) {
    const std::size_t n = qubit_count(s);
    check_distinct({a, b}, n, "cz");
    std::byte* p = s.data();
    const std::size_t mask = (std::size_t{1} << a) | (std::size_t{1} << b);
    for (std::size_t i = 0; i < (std::size_t{1} << n); ++i)
        if ((i & mask) == mask) store(p, i, -load(p, i));
}

void swap(const value::Value& s, std::size_t a, std::size_t b) {
    const std::size_t n = qubit_count(s);
    check_distinct({a, b}, n, "swap");
    std::byte* p = s.data();
    const std::size_t ab = std::size_t{1} << a, bb = std::size_t{1} << b;
    for (std::size_t i = 0; i < (std::size_t{1} << n); ++i) {
        if ((i & ab) && !(i & bb)) {
            const std::size_t j = (i & ~ab) | bb;
            const Complex x = load(p, i), y = load(p, j);
            store(p, i, y);
            store(p, j, x);
        }
    }
}

void toffoli(const value::Value& s, std::size_t c0, std::size_t c1, std::size_t t) {
    check_distinct({c0, c1, t}, qubit_count(s), "toffoli");
    apply_1q(s, t, gates::X(), (std::uint64_t{1} << c0) | (std::uint64_t{1} << c1));
}

void apply_unitary(const value::Value& s, const std::vector<std::size_t>& qubits, const std::vector<Complex>& u) {
    const std::size_t n = qubit_count(s);
    check_distinct(qubits, n, "apply_unitary");
    const std::size_t k = qubits.size(), d = std::size_t{1} << k;
    if (u.size() != d * d) throw std::invalid_argument("apply_unitary: matrix must be 2^k x 2^k");
    std::size_t tmask = 0;
    for (auto q : qubits) tmask |= std::size_t{1} << q;
    std::vector<std::size_t> offs(d);
    for (std::size_t sub = 0; sub < d; ++sub) {
        std::size_t o = 0;
        for (std::size_t b = 0; b < k; ++b)
            if (sub >> b & 1) o |= std::size_t{1} << qubits[b];
        offs[sub] = o;
    }
    std::byte* p = s.data();
    std::vector<Complex> in(d), out(d);
    for (std::size_t base = 0; base < (std::size_t{1} << n); ++base) {
        if (base & tmask) continue;
        for (std::size_t r = 0; r < d; ++r) in[r] = load(p, base | offs[r]);
        for (std::size_t r = 0; r < d; ++r) {
            Complex acc = 0;
            for (std::size_t c = 0; c < d; ++c) acc += u[r * d + c] * in[c];
            out[r] = acc;
        }
        for (std::size_t r = 0; r < d; ++r) store(p, base | offs[r], out[r]);
    }
}

double probability_one(const value::Value& s, std::size_t q) {
    const std::size_t n = qubit_count(s);
    check_qubit(n, q, "probability_one");
    double p1 = 0;
    for (std::size_t i = 0; i < (std::size_t{1} << n); ++i)
        if (i >> q & 1) p1 += std::norm(load(s.data(), i));
    return p1;
}

int measure(const value::Value& s, std::size_t q, std::mt19937_64& rng) {
    const double total = norm_squared(s);
    const double p1 = probability_one(s, q) / total;
    const int bit = std::uniform_real_distribution<double>(0, 1)(rng) < p1 ? 1 : 0;
    const double keep = bit ? p1 : 1 - p1;
    const double k = 1.0 / std::sqrt(keep * total);
    for (std::size_t i = 0; i < (std::size_t{1} << qubit_count(s)); ++i)
        store(s.data(), i, (static_cast<int>(i >> q & 1) == bit) ? load(s.data(), i) * k : Complex(0));
    return bit;
}

std::size_t sample(const value::Value& s, std::mt19937_64& rng) {
    const auto p = probabilities(s);
    double r = std::uniform_real_distribution<double>(0, 1)(rng) * std::accumulate(p.begin(), p.end(), 0.0);
    for (std::size_t i = 0; i < p.size(); ++i) {
        r -= p[i];
        if (r < 0) return i;
    }
    return p.size() - 1;
}

double purity(const value::Value& s, std::uint64_t mask) {
    const std::size_t n = qubit_count(s);
    if (mask >> n) throw std::out_of_range("purity: subsystem mask exceeds qubit count");
    std::vector<std::size_t> A, B;
    for (std::size_t q = 0; q < n; ++q) (mask >> q & 1 ? A : B).push_back(q);
    const std::size_t dA = std::size_t{1} << A.size(), dB = std::size_t{1} << B.size();
    std::vector<Complex> M(dA * dB);
    for (std::size_t i = 0; i < (std::size_t{1} << n); ++i) {
        std::size_t a = 0, b = 0;
        for (std::size_t k = 0; k < A.size(); ++k) a |= (i >> A[k] & 1) << k;
        for (std::size_t k = 0; k < B.size(); ++k) b |= (i >> B[k] & 1) << k;
        M[a * dB + b] = load(s.data(), i);
    }
    double pur = 0;
    for (std::size_t x = 0; x < dA; ++x)
        for (std::size_t y = 0; y < dA; ++y) {
            Complex rho = 0;
            for (std::size_t b = 0; b < dB; ++b) rho += M[x * dB + b] * std::conj(M[y * dB + b]);
            pur += std::norm(rho);
        }
    return pur;
}

double linear_entropy(const value::Value& s, std::uint64_t mask) { return 1.0 - purity(s, mask); }

// ------------------------------------------------------------- circuits ---

namespace {
const char* kind_name(Gate::Kind k) {
    switch (k) {
        case Gate::Kind::H: return "h";
        case Gate::Kind::X: return "x";
        case Gate::Kind::Y: return "y";
        case Gate::Kind::Z: return "z";
        case Gate::Kind::S: return "s";
        case Gate::Kind::T: return "t";
        case Gate::Kind::Sdg: return "sdg";
        case Gate::Kind::Tdg: return "tdg";
        case Gate::Kind::Phase: return "p";
        case Gate::Kind::Rx: return "rx";
        case Gate::Kind::Ry: return "ry";
        case Gate::Kind::Rz: return "rz";
        case Gate::Kind::CNOT: return "cx";
        case Gate::Kind::CZ: return "cz";
        case Gate::Kind::CPhase: return "cp";
        case Gate::Kind::Swap: return "swap";
        case Gate::Kind::Toffoli: return "ccx";
    }
    return "?";
}
std::size_t arity(Gate::Kind k) {
    switch (k) {
        case Gate::Kind::CNOT: case Gate::Kind::CZ: case Gate::Kind::CPhase: case Gate::Kind::Swap: return 2;
        case Gate::Kind::Toffoli: return 3;
        default: return 1;
    }
}
bool parametric(Gate::Kind k) {
    return k == Gate::Kind::Phase || k == Gate::Kind::Rx || k == Gate::Kind::Ry || k == Gate::Kind::Rz ||
           k == Gate::Kind::CPhase;
}
}  // namespace

std::string Gate::to_string() const {
    std::ostringstream os;
    os << kind_name(kind);
    if (parametric(kind)) os << "(" << theta << ")";
    for (std::size_t i = 0; i < qubits.size(); ++i) os << (i ? "," : " ") << "q" << qubits[i];
    return os.str();
}

Gate Gate::inverse() const {
    Gate g = *this;
    switch (kind) {
        case Kind::S: g.kind = Kind::Sdg; break;
        case Kind::Sdg: g.kind = Kind::S; break;
        case Kind::T: g.kind = Kind::Tdg; break;
        case Kind::Tdg: g.kind = Kind::T; break;
        case Kind::Phase: case Kind::Rx: case Kind::Ry: case Kind::Rz: case Kind::CPhase: g.theta = -theta; break;
        default: break;  // self-inverse
    }
    return g;
}

void apply(const value::Value& s, const Gate& g) {
    if (g.qubits.size() != arity(g.kind)) throw std::invalid_argument("apply: wrong qubit count for " + g.to_string());
    const auto& q = g.qubits;
    using K = Gate::Kind;
    switch (g.kind) {
        case K::H: return apply_1q(s, q[0], gates::H());
        case K::X: return apply_1q(s, q[0], gates::X());
        case K::Y: return apply_1q(s, q[0], gates::Y());
        case K::Z: return apply_1q(s, q[0], gates::Z());
        case K::S: return apply_1q(s, q[0], gates::S());
        case K::T: return apply_1q(s, q[0], gates::T());
        case K::Sdg: return apply_1q(s, q[0], gates::phase(-std::numbers::pi / 2));
        case K::Tdg: return apply_1q(s, q[0], gates::phase(-std::numbers::pi / 4));
        case K::Phase: return apply_1q(s, q[0], gates::phase(g.theta));
        case K::Rx: return apply_1q(s, q[0], gates::rx(g.theta));
        case K::Ry: return apply_1q(s, q[0], gates::ry(g.theta));
        case K::Rz: return apply_1q(s, q[0], gates::rz(g.theta));
        case K::CNOT: return cnot(s, q[0], q[1]);
        case K::CZ: return cz(s, q[0], q[1]);
        case K::CPhase:
            check_distinct(q, qubit_count(s), "cphase");
            return apply_1q(s, q[1], gates::phase(g.theta), std::uint64_t{1} << q[0]);
        case K::Swap: return swap(s, q[0], q[1]);
        case K::Toffoli: return toffoli(s, q[0], q[1], q[2]);
    }
}

Circuit& Circuit::add(Gate g) {
    if (g.qubits.size() != arity(g.kind)) throw std::invalid_argument("Circuit::add: wrong qubit count");
    check_distinct(g.qubits, qubits_, "Circuit::add");
    gates_.push_back(std::move(g));
    return *this;
}

void Circuit::apply(const value::Value& s) const {
    if (qubit_count(s) != qubits_) throw std::invalid_argument("Circuit::apply: qubit count mismatch");
    for (const auto& g : gates_) quantum::apply(s, g);
}

value::Value Circuit::run() const {
    value::Value s = make_state(qubits_);
    apply(s);
    return s;
}

Circuit Circuit::inverse() const {
    Circuit c(qubits_);
    for (auto it = gates_.rbegin(); it != gates_.rend(); ++it) c.add(it->inverse());
    return c;
}

std::vector<Complex> Circuit::unitary() const {
    if (qubits_ > 12) throw std::invalid_argument("Circuit::unitary: too many qubits for a dense matrix");
    const std::size_t dim = std::size_t{1} << qubits_;
    std::vector<Complex> u(dim * dim);
    for (std::size_t c = 0; c < dim; ++c) {
        value::Value s = basis_state(qubits_, c);
        apply(s);
        for (std::size_t r = 0; r < dim; ++r) u[r * dim + c] = load(s.data(), r);
    }
    return u;
}

std::string Circuit::to_string() const {
    std::string out;
    for (std::size_t i = 0; i < gates_.size(); ++i) out += (i ? "; " : "") + gates_[i].to_string();
    return out;
}

Circuit Circuit::ghz(std::size_t n) {
    Circuit c(n);
    if (n == 0) return c;
    c.h(0);
    for (std::size_t q = 1; q < n; ++q) c.cnot(0, q);
    return c;
}

Circuit Circuit::qft(std::size_t n) {
    Circuit c(n);
    for (std::size_t j = n; j-- > 0;) {
        c.h(j);
        for (std::size_t k = j; k-- > 0;) c.cphase(k, j, std::numbers::pi / static_cast<double>(std::size_t{1} << (j - k)));
    }
    for (std::size_t i = 0; i < n / 2; ++i) c.swap(i, n - 1 - i);
    return c;
}

bool is_unitary(const std::vector<Complex>& u, double tol) {
    const auto dim = static_cast<std::size_t>(std::llround(std::sqrt(static_cast<double>(u.size()))));
    if (dim * dim != u.size()) return false;
    for (std::size_t r = 0; r < dim; ++r)
        for (std::size_t c = 0; c < dim; ++c) {
            Complex acc = 0;
            for (std::size_t k = 0; k < dim; ++k) acc += u[r * dim + k] * std::conj(u[c * dim + k]);
            if (std::abs(acc - (r == c ? 1.0 : 0.0)) > tol) return false;
        }
    return true;
}

std::optional<Circuit> synthesize_circuit(const value::Value& target, const std::vector<Gate>& alphabet,
                                          std::size_t max_depth, double tol, std::uint64_t* explored) {
    const std::size_t n = qubit_count(target);
    std::uint64_t count = 0;
    // canonical key: amplitudes with global phase removed, rounded
    auto key = [&](const value::Value& s) {
        const auto amps = amplitudes(s);
        Complex ph = 1;
        for (const auto& a : amps)
            if (std::abs(a) > 1e-9) {
                ph = std::conj(a) / std::abs(a);
                break;
            }
        std::string k;
        k.reserve(amps.size() * 16);
        for (const auto& a : amps) {
            const Complex b = a * ph;
            const long long re = std::llround(b.real() * 1e7), im = std::llround(b.imag() * 1e7);
            k.append(reinterpret_cast<const char*>(&re), sizeof re);
            k.append(reinterpret_cast<const char*>(&im), sizeof im);
        }
        return k;
    };
    struct Node {
        value::Value state;
        Circuit circuit;
    };
    std::vector<Node> frontier;
    frontier.push_back({make_state(n), Circuit(n)});
    std::unordered_set<std::string> seen{key(frontier[0].state)};
    if (fidelity(frontier[0].state, target) >= 1 - tol) return frontier[0].circuit;
    for (std::size_t depth = 1; depth <= max_depth; ++depth) {
        std::vector<Node> next;
        for (const auto& node : frontier) {
            for (const auto& g : alphabet) {
                ++count;
                value::Value s = node.state.clone();
                apply(s, g);
                if (!seen.insert(key(s)).second) continue;
                Circuit c = node.circuit;
                c.add(g);
                if (fidelity(s, target) >= 1 - tol) {
                    if (explored) *explored = count;
                    return c;
                }
                next.push_back({std::move(s), std::move(c)});
            }
        }
        frontier = std::move(next);
    }
    if (explored) *explored = count;
    return std::nullopt;
}

// --------------------------------------------------------------- spaces ---

problem::Invariant normalization_invariant(double tol) {
    return problem::Invariant(
        "sum |c_i|^2 == 1",
        [tol](const value::Value& v) { return std::fabs(norm_squared(v) - 1.0) <= tol; },
        [](const value::Value& v) { return "norm^2 = " + std::to_string(norm_squared(v)); });
}

problem::Invariant entanglement_bound(std::uint64_t mask, double bound) {
    return problem::Invariant(
        "linear entropy across mask " + std::to_string(mask) + " <= " + std::to_string(bound),
        [=](const value::Value& v) { return linear_entropy(v, mask) <= bound + 1e-12; },
        [=](const value::Value& v) { return "linear entropy = " + std::to_string(linear_entropy(v, mask)); });
}

value::TypePtr transition_type(std::size_t qubits) {
    auto st = state_type(qubits);
    return value::ValueType::structure("QTransition", {{"before", st}, {"after", st}});
}

problem::Invariant unitary_transition(const Circuit& circuit, double tol) {
    return problem::Invariant(
        "after == U(" + std::to_string(circuit.size()) + " gates) before",
        [circuit, tol](const value::Value& t) {
            value::Value e = t.reference_member("before").clone();
            circuit.apply(e);
            const value::Value after = t.reference_member("after");
            const std::size_t dim = std::size_t{1} << circuit.qubits();
            for (std::size_t i = 0; i < dim; ++i)
                if (std::abs(load(e.data(), i) - load(after.data(), i)) > tol) return false;
            return true;
        },
        [circuit](const value::Value& t) {
            value::Value e = t.reference_member("before").clone();
            circuit.apply(e);
            return "fidelity with expected = " + std::to_string(fidelity(e, t.reference_member("after")));
        });
}

StateSpace::StateSpace(std::size_t qubits, double tolerance)
    : qubits_(qubits), tolerance_(tolerance), type_(state_type(qubits)) {}

std::string StateSpace::description() const { return std::to_string(qubits_) + "-qubit pure states"; }

std::vector<problem::Invariant> StateSpace::structural_invariants() const {
    return {problem::Invariant("finite amplitudes", [](const value::Value& v) {
        for (const auto& a : amplitudes(v))
            if (!std::isfinite(a.real()) || !std::isfinite(a.imag())) return false;
        return true;
    })};
}

std::vector<problem::Invariant> StateSpace::semantic_invariants() const { return {normalization_invariant(tolerance_)}; }

std::optional<value::Value> StateSpace::generate(problem::Rng& rng) const {
    value::Value v(type_, "psi");
    std::normal_distribution<double> g(0, 1);
    for (std::size_t i = 0; i < (std::size_t{1} << qubits_); ++i) store(v.data(), i, {g(rng), g(rng)});
    normalize(v);
    return v;
}

std::optional<value::Value> StateSpace::project(const value::Value& v) const {
    if (!v.type()->layout_equals(*type_)) return std::nullopt;
    value::Value out = v.clone();
    const double n2 = norm_squared(out);
    if (!(n2 > 0) || !std::isfinite(n2)) return make_state(qubits_);
    normalize(out);
    return out;
}

}  // namespace combinatorial::quantum
