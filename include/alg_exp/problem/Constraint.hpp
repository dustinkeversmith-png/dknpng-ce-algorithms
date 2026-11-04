#pragma once
// Symbolic constraints over the scalar leaves of a Value.
//
// `Expr` is an arithmetic term over leaf paths ("regs[2]", "pos.x") and
// constants; `Constraint` is a boolean formula of comparisons. Both are
// immutable shared ASTs. They can be
//   * evaluated against a concrete Value or any Env,
//   * bounded with interval arithmetic (three-valued feasibility, used by the
//     synthesizer to prune partial assignments), and
//   * lowered to SMT-LIB2 (see Smt.hpp).
//
// Evaluation is performed in double precision; integer leaves are exact up
// to 2^53.

#include "alg_exp/value/Value.hpp"

#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <string_view>
#include <vector>

namespace alg_exp::problem {

struct Interval {
    double lo = -std::numeric_limits<double>::infinity();
    double hi = std::numeric_limits<double>::infinity();

    static Interval point(double v) { return {v, v}; }
    static Interval everything() { return {}; }
    bool is_point() const noexcept { return lo == hi; }
    bool contains(double v) const noexcept { return lo <= v && v <= hi; }
};

/// Three-valued truth for partial assignments.
enum class Tri : std::uint8_t { False, True, Unknown };

/// Variable lookup used by evaluation and interval bounding.
class Env {
public:
    virtual ~Env() = default;
    virtual double value(std::string_view path) const = 0;
    /// Range of possible values; defaults to the point value().
    virtual Interval range(std::string_view path) const { return Interval::point(value(path)); }
};

/// Env reading leaves directly from a Value (paths resolved per lookup).
class ValueEnv final : public Env {
public:
    explicit ValueEnv(const value::Value& v) : v_(v) {}
    double value(std::string_view path) const override;

private:
    const value::Value& v_;
};

/// Env over raw state bytes of a fixed type. Each path is resolved against the
/// type once and cached; rebind() points the env at another buffer, so one env
/// can evaluate constraints over many candidate states cheaply.
class OffsetEnv final : public Env {
public:
    explicit OffsetEnv(value::TypePtr type, const std::byte* base = nullptr) : type_(std::move(type)), base_(base) {}
    void rebind(const std::byte* base) noexcept { base_ = base; }
    double value(std::string_view path) const override;

private:
    struct Slot {
        std::size_t offset;
        value::Prim prim;
    };
    value::TypePtr type_;
    const std::byte* base_;
    mutable std::map<std::string, Slot, std::less<>> cache_;
};

class Expr {
public:
    enum class Op : std::uint8_t { Const, Var, Add, Sub, Mul, Neg, Abs, Min, Max };

    Expr(double constant);  // NOLINT(google-explicit-constructor): literals read naturally in formulas
    static Expr var(std::string path);
    static Expr constant(double v) { return Expr(v); }

    Op op() const noexcept;
    double constant_value() const noexcept;
    const std::string& path() const noexcept;
    const std::vector<Expr>& args() const noexcept;

    double eval(const Env& env) const;
    Interval bounds(const Env& env) const;
    void collect_variables(std::set<std::string>& out) const;
    std::string to_string() const;

    friend Expr operator+(const Expr& a, const Expr& b);
    friend Expr operator-(const Expr& a, const Expr& b);
    friend Expr operator*(const Expr& a, const Expr& b);
    friend Expr operator-(const Expr& a);
    friend Expr abs(const Expr& a);
    friend Expr min(const Expr& a, const Expr& b);
    friend Expr max(const Expr& a, const Expr& b);

    struct Node;

private:
    explicit Expr(std::shared_ptr<const Node> n) : node_(std::move(n)) {}
    std::shared_ptr<const Node> node_;
};

inline Expr var(std::string path) { return Expr::var(std::move(path)); }

class Constraint {
public:
    enum class Op : std::uint8_t { True, False, Eq, Ne, Lt, Le, Gt, Ge, And, Or, Not };

    static Constraint always();
    static Constraint never();
    static Constraint compare(Op op, Expr lhs, Expr rhs);
    /// Conjunction / disjunction over a list (empty -> true / false).
    static Constraint all_of(std::vector<Constraint> cs);
    static Constraint any_of(std::vector<Constraint> cs);
    /// Pairwise distinctness of the given terms.
    static Constraint all_different(const std::vector<Expr>& terms);
    /// lo <= e <= hi
    static Constraint between(const Expr& e, double lo, double hi);

    Op op() const noexcept;
    bool is_comparison() const noexcept;
    const Expr& lhs() const;
    const Expr& rhs() const;
    const std::vector<Constraint>& args() const noexcept;

    bool eval(const Env& env) const;
    bool eval(const value::Value& v) const { return eval(ValueEnv(v)); }
    /// Three-valued evaluation where every variable ranges over env.range().
    Tri feasible(const Env& env) const;
    std::set<std::string> variables() const;
    void collect_variables(std::set<std::string>& out) const;
    std::string to_string() const;
    /// Explanation of why the constraint fails under env (empty if it holds).
    std::string explain(const Env& env) const;

    friend Constraint operator&&(const Constraint& a, const Constraint& b);
    friend Constraint operator||(const Constraint& a, const Constraint& b);
    friend Constraint operator!(const Constraint& a);

    struct Node;

private:
    explicit Constraint(std::shared_ptr<const Node> n) : node_(std::move(n)) {}
    std::shared_ptr<const Node> node_;
};

Constraint operator==(const Expr& a, const Expr& b);
Constraint operator!=(const Expr& a, const Expr& b);
Constraint operator<(const Expr& a, const Expr& b);
Constraint operator<=(const Expr& a, const Expr& b);
Constraint operator>(const Expr& a, const Expr& b);
Constraint operator>=(const Expr& a, const Expr& b);

}  // namespace alg_exp::problem
