#pragma once
// Concrete spaces:
//   ConstrainedSpace - finite: decision leaves over Domains plus invariants.
//                      Generation, enumeration and exact projection are done
//                      by the Synthesizer. Also exposes the space's topology:
//                      the neighbourhood graph where two members are adjacent
//                      when they differ in one decision leaf by one domain step.
//   BoxSpace         - continuous axis-aligned box over every numeric leaf,
//                      with exact clamping projection.

#include "combinatorial/problem/Space.hpp"
#include "combinatorial/problem/Synthesizer.hpp"

#include <map>

namespace combinatorial::problem {

class ConstrainedSpace : public Space {
public:
    ConstrainedSpace(std::string description, value::Value base, std::vector<DecisionLeaf> leaves,
                     std::vector<Invariant> semantic, Norm norm = Norm::L2, SynthesisOptions options = {});

    /// Every primitive leaf of `type` is a decision over `domain`.
    static std::shared_ptr<ConstrainedSpace> over_all_leaves(std::string description, value::TypePtr type,
                                                             const Domain& domain, std::vector<Invariant> semantic,
                                                             Norm norm = Norm::L2, SynthesisOptions options = {});

    std::string description() const override { return description_; }
    value::TypePtr value_type() const override { return base_.type(); }
    std::vector<Invariant> structural_invariants() const override { return structural_; }
    std::vector<Invariant> semantic_invariants() const override { return semantic_; }
    Norm norm() const override { return norm_; }

    std::optional<value::Value> generate(Rng& rng) const override;
    bool is_finite() const override { return true; }
    std::vector<value::Value> enumerate(std::size_t limit) const override;
    std::optional<value::Value> project(const value::Value& v) const override;

    std::size_t count(std::size_t limit = SIZE_MAX) const;
    Synthesizer synthesizer(SynthesisOptions options) const;
    Synthesizer synthesizer() const { return synthesizer(options_); }
    const std::vector<DecisionLeaf>& leaves() const noexcept { return leaves_; }

    // ---- topology ------------------------------------------------------
    /// Members differing from `v` in exactly one leaf by one domain step.
    std::vector<value::Value> neighbors(const value::Value& v) const;
    /// Breadth-first closure of `from` under neighbors(), at most `limit` states.
    std::vector<value::Value> reachable(const value::Value& from, std::size_t limit = 100000) const;
    /// Connected components of the neighbourhood graph (enumerates the space).
    std::size_t connected_components(std::size_t limit = 100000) const;

private:
    std::string description_;
    value::Value base_;
    std::vector<DecisionLeaf> leaves_;
    std::vector<Invariant> semantic_;
    std::vector<Invariant> structural_;
    Norm norm_;
    SynthesisOptions options_;
};

class BoxSpace : public Space {
public:
    /// Every numeric leaf constrained to [lo, hi]; `overrides` sets per-leaf bounds.
    BoxSpace(std::string description, value::TypePtr type, Interval bounds,
             std::map<std::string, Interval> overrides = {}, Norm norm = Norm::L2);

    std::string description() const override { return description_; }
    value::TypePtr value_type() const override { return type_; }
    std::vector<Invariant> structural_invariants() const override;
    std::vector<Invariant> semantic_invariants() const override { return {}; }
    bool contains(const value::Value& v) const override;
    Norm norm() const override { return norm_; }
    std::optional<value::Value> project(const value::Value& v) const override;
    std::optional<value::Value> generate(Rng& rng) const override;

private:
    struct Slot {
        std::string path;
        std::size_t offset;
        value::Prim prim;
        Interval bounds;
    };
    std::string description_;
    value::TypePtr type_;
    std::vector<Slot> slots_;
    Norm norm_;
};

}  // namespace combinatorial::problem
