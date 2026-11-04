#pragma once
// Invariant: a named predicate over Values, composable into predicate trees.
//
// Leaves are either opaque C++ predicates (with optional explainer and
// optional dependency paths) or symbolic Constraints. Composite nodes are
// And / Or / Not. The tree supports
//   * holds(v)                  - truth
//   * explain(v)                - first-cause diagnostic, nullopt if it holds
//   * violations(v)             - every failing leaf's diagnostic
//   * dependencies()            - leaf paths the predicate reads, if known
//   * constraint()              - an equivalent symbolic Constraint, if every
//                                 leaf is symbolic (enables SMT lowering and
//                                 interval pruning)
//
// Nodes are immutable and shared, so composing invariants is cheap and the
// resulting trees are safe to copy across threads.

#include "alg_exp/problem/Constraint.hpp"
#include "alg_exp/value/Value.hpp"

#include <functional>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace alg_exp::problem {

class Invariant {
public:
    using Predicate = std::function<bool(const value::Value&)>;
    using Explainer = std::function<std::string(const value::Value&)>;

    enum class Kind : std::uint8_t { Predicate, Symbolic, And, Or, Not };

    /// Opaque predicate. `depends_on` lists the paths read by the predicate;
    /// leave empty when unknown (the synthesizer then checks it only once the
    /// whole value is assigned).
    Invariant(std::string description, Predicate predicate, Explainer explainer = {},
              std::vector<std::string> depends_on = {});
    /// Symbolic invariant backed by a Constraint.
    Invariant(std::string description, Constraint constraint);
    explicit Invariant(Constraint constraint);

    static Invariant always();
    static Invariant never();
    static Invariant all_of(const std::vector<Invariant>& xs);
    static Invariant any_of(const std::vector<Invariant>& xs);

    Kind kind() const noexcept;
    const std::string& description() const noexcept;
    const std::vector<Invariant>& children() const noexcept;

    bool holds(const value::Value& v) const;
    std::optional<std::string> explain(const value::Value& v) const;
    std::vector<std::string> violations(const value::Value& v) const;

    /// Paths this invariant reads; nullopt if any leaf has unknown dependencies.
    std::optional<std::set<std::string>> dependencies() const;
    /// Equivalent symbolic constraint, if every leaf is symbolic.
    std::optional<Constraint> constraint() const;
    /// Three-valued check under interval ranges; opaque leaves yield Unknown.
    Tri feasible(const Env& env) const;
    /// Number of nodes in the tree.
    std::size_t size() const noexcept;

    friend Invariant operator&&(const Invariant& a, const Invariant& b);
    friend Invariant operator||(const Invariant& a, const Invariant& b);
    friend Invariant operator!(const Invariant& a);

    struct Node;

private:
    explicit Invariant(std::shared_ptr<const Node> n) : node_(std::move(n)) {}
    void collect_violations(const value::Value& v, std::vector<std::string>& out) const;
    std::shared_ptr<const Node> node_;
};

}  // namespace alg_exp::problem
