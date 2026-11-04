#pragma once
// The space of valid single-step transitions T(S_t, S_{t+1}).
//
// A transition value is the struct {before: State, instr: Instr, after: State}.
// Structural invariants check the instruction encoding; semantic invariants
// are the step relation decomposed per state component (pc, sp, flags, halt,
// registers, memory), so a bad transition is explained by the component it
// gets wrong rather than by a single opaque "not a step".

#include "combinatorial/automaton/Machine.hpp"
#include "combinatorial/problem/Space.hpp"

namespace combinatorial::automaton {

class TransitionSpace : public problem::Space {
public:
    explicit TransitionSpace(const Machine& machine, std::vector<Op> ops = {});

    std::string description() const override { return "machine step transitions"; }
    value::TypePtr value_type() const override { return type_; }
    std::vector<problem::Invariant> structural_invariants() const override { return structural_; }
    std::vector<problem::Invariant> semantic_invariants() const override { return semantic_; }
    std::optional<value::Value> generate(problem::Rng& rng) const override;
    /// Projection: keep `before` and `instr`, recompute `after`.
    std::optional<value::Value> project(const value::Value& v) const override;

    /// Builds a transition value from a state and instruction.
    value::Value make(const value::Value& before, const Instruction& ins) const;
    /// An invariant over consecutive state pairs suitable for checking traces.
    bool valid_trace(const std::vector<value::Value>& states, const Program& program) const;

private:
    const Machine& machine_;
    std::vector<Op> ops_;
    value::TypePtr type_;
    std::vector<problem::Invariant> structural_;
    std::vector<problem::Invariant> semantic_;
};

}  // namespace combinatorial::automaton
