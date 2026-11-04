#pragma once
// Program sketches: assembly with labels and holes, plus a synthesizer that
// fills the holes.
//
//   top:  addi r1 r1 0     # sets Z from r1
//         jz done
//         ?? 3             # hole: up to 3 instructions from the default alphabet
//         jmp top
//   done:
//
// A hole line is `?? N [alphabet]`. Labels may precede holes; jump targets are
// resolved after the holes are filled, so loops stay well-formed whatever the
// fill lengths. The synthesizer enumerates fills in order of total length
// (so the first solution is a shortest one), runs every candidate on the
// training examples under a step budget, and stops at the first failing
// example, which then moves to the front ("failure-first").

#include "alg_exp/automaton/Machine.hpp"
#include "alg_exp/automaton/ProgramSynthesizer.hpp"

#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace alg_exp::automaton {

class Sketch {
public:
    static Sketch parse(std::string_view text);

    std::size_t holes() const noexcept { return holes_.size(); }
    std::size_t hole_max(std::size_t h) const { return holes_.at(h).max_len; }
    const std::string& hole_alphabet(std::size_t h) const { return holes_.at(h).alphabet; }

    /// Concrete program for the given fills (fills[h].size() <= hole_max(h)).
    Program instantiate(const std::vector<std::vector<Instruction>>& fills) const;
    /// Text with each hole shown as `?? N`, for reports.
    std::string to_string() const;

private:
    struct Item {
        bool is_hole = false;
        Instruction ins{};          // fixed instruction
        std::string label_ref;      // fixed instruction whose imm is a label
        std::size_t hole = 0;       // hole index
    };
    struct Hole {
        std::size_t max_len;
        std::string alphabet;
    };
    std::vector<Item> items_;
    std::vector<Hole> holes_;
    std::map<std::string, std::size_t> labels_;  // label -> item index it precedes (items_.size() = end)
};

struct SketchOptions {
    /// Alphabets by name; "" is the default used by unnamed holes.
    std::map<std::string, ProgramSearchOptions> alphabets;
    std::uint64_t max_candidates = 50'000'000;
    std::uint64_t max_steps = 4'000;      ///< per example run
    bool prune_dead_writes = true;
};

struct SketchStats {
    std::uint64_t candidates = 0;         ///< complete programs evaluated
    std::uint64_t pruned = 0;             ///< fills skipped by dead-write pruning
    std::uint64_t example_runs = 0;
    bool budget_exhausted = false;
    double seconds = 0;
    double candidates_per_second() const noexcept { return seconds > 0 ? double(candidates) / seconds : 0; }
};

class SketchSynthesizer {
public:
    SketchSynthesizer(const Machine& machine, Sketch sketch, std::vector<Example> examples, SketchOptions options);

    std::optional<Program> synthesize();
    /// Fills of the last successful synthesize(), one per hole.
    const std::vector<std::vector<Instruction>>& fills() const noexcept { return fills_; }
    const SketchStats& stats() const noexcept { return stats_; }
    bool verify(const Program& program) const;

private:
    bool passes(const Program& p);

    const Machine& machine_;
    Sketch sketch_;
    std::vector<Example> examples_;
    SketchOptions options_;
    std::vector<std::vector<Instruction>> alphabets_;  // per hole
    std::vector<std::size_t> order_;                   // example evaluation order
    std::vector<std::vector<Instruction>> fills_;
    SketchStats stats_;
};

/// True if `next` overwrites the register `prev` writes without reading it
/// first and `prev` has no other observable effect (memory, flags read later).
bool is_dead_write_pair(const Instruction& prev, const Instruction& next);

}  // namespace alg_exp::automaton
