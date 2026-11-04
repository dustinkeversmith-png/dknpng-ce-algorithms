#include "alg_exp/experiments/Experiment.hpp"

#include <algorithm>
#include <iomanip>
#include <sstream>

namespace alg_exp::experiments {

// Each tier registers its problems through these (defined in the tier files).
std::vector<ProblemPtr> tier1_problems();
std::vector<ProblemPtr> tier2_problems();
std::vector<ProblemPtr> tier3_problems();

std::vector<ProblemPtr> catalog() {
    std::vector<ProblemPtr> all;
    for (auto&& tier : {tier1_problems(), tier2_problems(), tier3_problems()})
        all.insert(all.end(), tier.begin(), tier.end());
    return all;
}

ProblemPtr find_problem(const std::string& name) {
    for (auto& p : catalog())
        if (p->name() == name) return p;
    return nullptr;
}

HeldOut validate_program(const automaton::Machine& m, const automaton::Program& program, const InputGenerator& gen,
                         const GoalFor& goal, std::size_t cases, Rng& rng, std::uint64_t max_steps) {
    HeldOut h;
    for (std::size_t i = 0; i < cases; ++i) {
        value::Value input = gen(rng);
        value::Value state = input.clone();
        const auto r = m.run(state, program, max_steps);
        ++h.cases;
        const problem::Invariant g = goal(input);
        std::string why;
        if (r.reason == automaton::HaltReason::Fault) why = "faulted";
        else if (r.reason == automaton::HaltReason::Running) why = "did not halt within " + std::to_string(max_steps) + " steps";
        else if (auto e = g.explain(state)) why = *e;
        if (why.empty()) {
            ++h.passed;
        } else if (h.first_failure.empty()) {
            h.first_failure = "case " + std::to_string(i) + ": " + why;
        }
    }
    return h;
}

std::vector<automaton::Example> make_examples(const InputGenerator& gen, const GoalFor& goal, std::size_t count,
                                              Rng& rng) {
    std::vector<automaton::Example> ex;
    for (std::size_t i = 0; i < count; ++i) {
        value::Value in = gen(rng);
        ex.push_back({in, goal(in)});
    }
    return ex;
}

void record_search(Outcome& out, const automaton::ProgramSynthesizer& ps,
                   const std::optional<automaton::Program>& program) {
    out.search_states += ps.stats().states;
    out.search_seconds += ps.stats().seconds;
    out.solved = program.has_value();
    if (program) out.solution = automaton::to_string(*program);
    else out.notes += ps.stats().budget_exhausted ? "search budget exhausted; " : "no program within length bound; ";
}

void record_validation(Outcome& out, const HeldOut& h, double seconds) {
    out.test_cases += h.cases;
    out.test_passed += h.passed;
    out.verify_seconds += seconds;
    out.verified = out.solved && out.test_cases > 0 && out.test_passed == out.test_cases;
    if (!h.first_failure.empty()) out.notes += "held-out " + h.first_failure + "; ";
}

std::string format_report(const std::vector<std::pair<ProblemPtr, Outcome>>& results) {
    std::ostringstream os;
    os << std::left << std::setw(24) << "problem" << std::setw(13) << "mode" << std::setw(8) << "found"
       << std::setw(12) << "held-out" << std::setw(13) << "states" << std::setw(10) << "search s" << "solution / notes\n";
    os << std::string(110, '-') << "\n";
    for (const auto& [p, o] : results) {
        std::ostringstream ho;
        ho << o.test_passed << "/" << o.test_cases;
        std::ostringstream secs;
        secs << std::fixed << std::setprecision(3) << o.search_seconds;
        std::string sol = o.solution;
        if (sol.size() > 70) sol = sol.substr(0, 67) + "...";
        os << std::left << std::setw(24) << p->name() << std::setw(13) << p->mode() << std::setw(8)
           << (o.solved ? "yes" : "no") << std::setw(12) << (o.verified ? ho.str() + " ok" : ho.str()) << std::setw(13)
           << o.search_states << std::setw(10) << secs.str() << sol << "\n";
        if (!o.notes.empty()) os << std::string(24, ' ') << "note: " << o.notes << "\n";
    }
    return os.str();
}

}  // namespace alg_exp::experiments
