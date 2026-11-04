#pragma once
// Space: a set of Values of one ValueType, characterised by structural and
// semantic invariants, with a metric, a projection onto the set and a
// constructive generator. Spaces compose with intersect / unite / difference.

#include "alg_exp/problem/Invariant.hpp"
#include "alg_exp/problem/Metric.hpp"
#include "alg_exp/value/Value.hpp"

#include <memory>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace alg_exp::problem {

using Rng = std::mt19937_64;

class Space {
public:
    virtual ~Space() = default;

    virtual std::string description() const = 0;
    virtual value::TypePtr value_type() const = 0;
    /// Shape/encoding validity (e.g. domain membership). Default: none.
    virtual std::vector<Invariant> structural_invariants() const { return {}; }
    /// Problem-level meaning (e.g. "no two queens attack").
    virtual std::vector<Invariant> semantic_invariants() const = 0;

    std::vector<Invariant> invariants() const;
    Invariant invariant() const { return Invariant::all_of(invariants()); }

    /// Layout check followed by every invariant.
    virtual bool contains(const value::Value& v) const;
    /// Every violated invariant's diagnostic (layout mismatch reported first).
    std::vector<std::string> explain(const value::Value& v) const;

    virtual Norm norm() const { return Norm::L2; }
    virtual double distance(const value::Value& a, const value::Value& b) const { return problem::distance(a, b, norm()); }

    /// Nearest member of the space to `v`, if one can be found. The default
    /// returns v itself when it is a member, otherwise the nearest of the
    /// enumerated members (finite spaces) or of `projection_samples()` draws.
    virtual std::optional<value::Value> project(const value::Value& v) const;
    /// A member of the space, or nullopt if none could be constructed.
    virtual std::optional<value::Value> generate(Rng& rng) const = 0;

    /// True if enumerate() lists the whole space (up to `limit`).
    virtual bool is_finite() const { return false; }
    virtual std::vector<value::Value> enumerate(std::size_t limit) const;
    virtual std::size_t projection_samples() const { return 256; }
};

using SpacePtr = std::shared_ptr<const Space>;

SpacePtr intersect(SpacePtr a, SpacePtr b);
SpacePtr unite(SpacePtr a, SpacePtr b);
SpacePtr difference(SpacePtr a, SpacePtr b);

inline SpacePtr operator&(SpacePtr a, SpacePtr b) { return intersect(std::move(a), std::move(b)); }
inline SpacePtr operator|(SpacePtr a, SpacePtr b) { return unite(std::move(a), std::move(b)); }
inline SpacePtr operator-(SpacePtr a, SpacePtr b) { return difference(std::move(a), std::move(b)); }

/// A space defined by a type, invariants and a caller-supplied generator.
class PredicateSpace : public Space {
public:
    using Generator = std::function<std::optional<value::Value>(Rng&)>;
    PredicateSpace(std::string description, value::TypePtr type, std::vector<Invariant> semantic,
                   Generator generator, std::vector<Invariant> structural = {});

    std::string description() const override { return description_; }
    value::TypePtr value_type() const override { return type_; }
    std::vector<Invariant> structural_invariants() const override { return structural_; }
    std::vector<Invariant> semantic_invariants() const override { return semantic_; }
    std::optional<value::Value> generate(Rng& rng) const override;

private:
    std::string description_;
    value::TypePtr type_;
    std::vector<Invariant> semantic_;
    Generator generator_;
    std::vector<Invariant> structural_;
};

}  // namespace alg_exp::problem
