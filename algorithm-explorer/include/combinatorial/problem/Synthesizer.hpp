#pragma once
// Constructive synthesis of Values satisfying invariants.
//
// A synthesis problem is a ValueType, a set of decision leaves each with a
// finite Domain, a base Value supplying every non-decision leaf, and a list
// of invariants. Strategies:
//
//   solve / enumerate  - depth-first backtracking. Each invariant is checked
//                        as soon as every leaf it depends on is assigned
//                        (forward checking); symbolic invariants are also
//                        bounded with interval arithmetic over the remaining
//                        domains, pruning subtrees that cannot succeed.
//   nearest            - branch & bound for the member closest to a target
//                        under a Norm (exact projection onto finite spaces).
//   min_conflicts      - stochastic local search for large, loosely
//                        constrained problems (e.g. N-queens, colouring).

#include "combinatorial/problem/Domain.hpp"
#include "combinatorial/problem/Invariant.hpp"
#include "combinatorial/problem/Metric.hpp"
#include "combinatorial/value/Value.hpp"

#include <chrono>
#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace combinatorial::problem {

struct DecisionLeaf {
    std::string path;
    Domain domain;
};

struct SynthesisOptions {
    std::uint64_t max_nodes = 50'000'000;  ///< search budget (assignments tried)
    bool interval_pruning = true;
    bool shuffle_values = false;           ///< randomise value order (seeded)
    std::uint64_t seed = 0;
    enum class Order : std::uint8_t { Declaration, SmallestDomainFirst, MostConstrainedFirst } order =
        Order::MostConstrainedFirst;
};

struct SynthesisStats {
    std::uint64_t nodes = 0;        ///< assignments tried
    std::uint64_t checks = 0;       ///< invariant evaluations
    std::uint64_t prunes = 0;       ///< subtrees cut by interval reasoning
    std::uint64_t backtracks = 0;
    std::uint64_t solutions = 0;
    bool exhausted = false;         ///< search space fully explored within budget
    double seconds = 0;
    double nodes_per_second() const noexcept { return seconds > 0 ? static_cast<double>(nodes) / seconds : 0; }
};

class Synthesizer {
public:
    Synthesizer(value::Value base, std::vector<DecisionLeaf> leaves, std::vector<Invariant> invariants,
                SynthesisOptions options = {});

    std::optional<value::Value> solve();
    std::vector<value::Value> enumerate(std::size_t limit);
    std::size_t count(std::size_t limit = SIZE_MAX);
    std::optional<value::Value> nearest(const value::Value& target, Norm norm = Norm::L2);
    std::optional<value::Value> min_conflicts(std::uint64_t max_steps, double noise = 0.1);

    const SynthesisStats& stats() const noexcept { return stats_; }
    std::size_t decision_count() const noexcept { return vars_.size(); }

    // ---- implementation details (public only so the evaluator can name them)
    /// A leaf read by a compiled constraint: absolute byte offset in the
    /// working value, primitive kind, and decision index (SIZE_MAX if fixed).
    struct Slot {
        std::size_t offset;
        value::Prim prim;
        std::size_t var;
    };
    /// Flat, slot-indexed form of a symbolic constraint (see Synthesizer.cpp).
    struct Compiled;

private:
    struct Var {
        std::string path;
        std::size_t offset;
        value::Prim prim;
        Domain domain;
    };
    struct Check {
        Invariant invariant;
        std::vector<std::size_t> vars;      // decision indices it reads (sorted, by order position)
        bool unknown_deps = false;
        bool symbolic = false;
        std::size_t ready_at = 0;           // position in order after which it is fully assigned
        std::shared_ptr<const Compiled> compiled;  // set for symbolic checks
    };

    void reset_stats();
    void finish_stats(std::chrono::steady_clock::time_point start);
    bool consistent_at(std::size_t pos);
    bool check_holds(const Check& c) const;
    Tri check_feasible(const Check& c) const;
    std::size_t slot_for(const std::string& path);
    template <class OnSolution>
    void dfs(std::size_t pos, OnSolution& on_solution, bool& stop);
    double read(std::size_t var) const;
    void write(std::size_t var, double v);
    const std::vector<double>& value_order(std::size_t var, std::size_t pos);

    value::Value work_;
    std::vector<Var> vars_;
    std::vector<std::size_t> order_;                   // position -> var index
    std::vector<std::size_t> position_;                // var index -> position
    std::vector<Check> checks_;
    std::vector<std::vector<std::size_t>> ready_;      // position -> checks fully assigned there
    std::vector<std::vector<std::size_t>> touching_;   // position -> symbolic checks still partial there
    std::vector<std::size_t> root_checks_;             // checks independent of decisions
    std::vector<bool> assigned_;
    std::map<std::string, std::size_t, std::less<>> var_by_path_;
    std::vector<Slot> slots_;
    std::map<std::string, std::size_t, std::less<>> slot_by_path_;
    std::vector<double> hull_lo_, hull_hi_;            // per decision: domain bounds
    std::vector<std::vector<double>> shuffle_buf_;     // per depth, when shuffling
    SynthesisOptions options_;
    SynthesisStats stats_;
    std::mt19937_64 rng_;
};

}  // namespace combinatorial::problem
