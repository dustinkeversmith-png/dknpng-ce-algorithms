#pragma once
// Synthesis of straight-line instruction sequences from input/output examples.
//
// Each example pairs an input machine state with an Invariant the final state
// must satisfy. The search enumerates programs over an instruction alphabet
// (opcode subset x usable registers x immediate pool, with commutative
// operand symmetry broken) in order of length, so the first program found is
// a shortest one. Two strategies:
//
//   observational equivalence (default) - breadth-first over the tuple of
//       states reached on all examples; programs that produce an
//       already-seen tuple are discarded. Exponentially fewer nodes.
//   plain enumeration - iterative-deepening DFS over all sequences, kept as a
//       baseline and for benchmarking raw exploration throughput.

#include "combinatorial/automaton/Machine.hpp"
#include "combinatorial/problem/Invariant.hpp"

#include <cstdint>
#include <optional>
#include <vector>

namespace combinatorial::automaton {

struct Example {
    value::Value input;
    problem::Invariant goal;  ///< over the final state
};

struct ProgramSearchOptions {
    std::vector<Op> ops = {Op::Movi, Op::Mov, Op::Add, Op::Sub, Op::Mul, Op::And,
                           Op::Or,   Op::Xor, Op::Shl, Op::Shr, Op::Addi, Op::Neg, Op::Not};
    std::vector<std::int32_t> immediates = {0, 1, 2, -1};
    std::size_t registers = 4;          ///< r0 .. r(registers-1) usable
    std::size_t max_length = 4;
    std::uint64_t max_states = 20'000'000;
    bool observational_equivalence = true;
};

struct ProgramSearchStats {
    std::uint64_t states = 0;           ///< instruction applications (per example tuple)
    std::uint64_t distinct = 0;         ///< distinct state tuples kept (OE mode)
    std::size_t depth = 0;              ///< deepest length fully or partially explored
    bool budget_exhausted = false;
    double seconds = 0;
    double states_per_second() const noexcept { return seconds > 0 ? static_cast<double>(states) / seconds : 0; }
};

class ProgramSynthesizer {
public:
    ProgramSynthesizer(const Machine& machine, std::vector<Example> examples, ProgramSearchOptions options = {});

    std::optional<Program> synthesize();
    const std::vector<Instruction>& alphabet() const noexcept { return alphabet_; }
    const ProgramSearchStats& stats() const noexcept { return stats_; }

    /// Runs `program` on every example input and checks every goal.
    bool verify(const Program& program) const;

private:
    bool goals_hold(const std::vector<value::Value>& states) const;
    std::optional<Program> search_oe();
    std::optional<Program> search_dfs();

    const Machine& machine_;
    std::vector<Example> examples_;
    ProgramSearchOptions options_;
    std::vector<Instruction> alphabet_;
    ProgramSearchStats stats_;
    // Goal fast path: symbolic goals are evaluated through a cached-offset env.
    std::vector<std::optional<problem::Constraint>> goal_constraints_;
    mutable problem::OffsetEnv goal_env_;
};

}  // namespace combinatorial::automaton
