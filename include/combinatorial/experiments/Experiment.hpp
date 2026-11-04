#pragma once
// Experiment harness: a catalogue of synthesis/verification problems, each
// with its own input generator, reference semantics, invariant verifier,
// search budget and held-out generalisation set.
//
// A Problem's run() searches for a solution using training examples only,
// then validates it on fresh inputs it never saw (often from a wider
// distribution, e.g. longer arrays or bigger numbers). The Outcome records
// both, plus search effort, so results are comparable across problems.

#include "combinatorial/automaton/Machine.hpp"
#include "combinatorial/automaton/ProgramSynthesizer.hpp"
#include "combinatorial/problem/Invariant.hpp"
#include "combinatorial/value/Value.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <random>
#include <string>
#include <vector>

namespace combinatorial::experiments {

using Rng = std::mt19937_64;

struct RunConfig {
    std::uint64_t seed = 1;
    /// Smaller held-out sets and step counts (for debug builds and CI).
    bool quick = false;
};

struct Outcome {
    bool solved = false;          ///< a candidate satisfied every training example
    bool verified = false;        ///< it also passed every held-out check
    std::size_t train_examples = 0;
    std::size_t test_cases = 0;
    std::size_t test_passed = 0;
    std::uint64_t search_states = 0;
    double search_seconds = 0;
    double verify_seconds = 0;
    std::string solution;         ///< human-readable solution (program, circuit, ...)
    std::string notes;            ///< method, restrictions, first failure
};

class Problem {
public:
    virtual ~Problem() = default;
    virtual std::string name() const = 0;
    /// "synthesis" (straight-line search), "sketch" (loop template holes),
    /// "contract" (body from an iteration contract), "verification".
    virtual std::string mode() const = 0;
    virtual std::string description() const = 0;
    virtual Outcome run(const RunConfig& config) const = 0;
};

using ProblemPtr = std::shared_ptr<const Problem>;

/// Every registered problem, in tier order.
std::vector<ProblemPtr> catalog();
/// Looks a problem up by name (nullptr if absent).
ProblemPtr find_problem(const std::string& name);
/// Fixed-width text table of outcomes.
std::string format_report(const std::vector<std::pair<ProblemPtr, Outcome>>& results);

// ---- machine-program helpers --------------------------------------------------

/// Builds a machine input state from a random generator.
using InputGenerator = std::function<value::Value(Rng&)>;
/// The goal (over the final state) a correct program must reach from `input`.
using GoalFor = std::function<problem::Invariant(const value::Value& input)>;

struct HeldOut {
    std::size_t cases = 0;
    std::size_t passed = 0;
    std::string first_failure;
};

/// Runs `program` from fresh inputs and checks the goal for each one.
HeldOut validate_program(const automaton::Machine& m, const automaton::Program& program, const InputGenerator& gen,
                         const GoalFor& goal, std::size_t cases, Rng& rng, std::uint64_t max_steps);

/// Training examples from a generator and goal.
std::vector<automaton::Example> make_examples(const InputGenerator& gen, const GoalFor& goal, std::size_t count,
                                              Rng& rng);

/// Fills `out` from a straight-line search result and a held-out validation.
void record_search(Outcome& out, const automaton::ProgramSynthesizer& ps,
                   const std::optional<automaton::Program>& program);
void record_validation(Outcome& out, const HeldOut& h, double seconds);

}  // namespace combinatorial::experiments
