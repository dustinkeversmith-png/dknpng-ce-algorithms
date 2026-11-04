#include "combinatorial/problem/Synthesizer.hpp"

#include "combinatorial/value/Layout.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <set>
#include <stdexcept>

namespace combinatorial::problem {

namespace {

constexpr std::size_t kNone = std::numeric_limits<std::size_t>::max();

void flatten_and(const Invariant& inv, std::vector<Invariant>& out) {
    if (inv.kind() == Invariant::Kind::And) {
        for (const auto& c : inv.children()) flatten_and(c, out);
    } else {
        out.push_back(inv);
    }
}

}  // namespace

/// Env over the partially assigned working value: assigned decision leaves
/// and fixed leaves are points, unassigned decision leaves span their domain.
class SearchEnv final : public Env {
public:
    explicit SearchEnv(const Synthesizer& s) : s_(s) {}

    double value(std::string_view path) const override {
        const Slot& slot = resolve(path);
        return value::load_as_double(s_.work_.memory()->data() + slot.offset, slot.prim);
    }
    Interval range(std::string_view path) const override {
        const Slot& slot = resolve(path);
        if (slot.var != kNone && !s_.assigned_[slot.var]) {
            const Domain& d = s_.vars_[slot.var].domain;
            return {d.lo(), d.hi()};
        }
        return Interval::point(value::load_as_double(s_.work_.memory()->data() + slot.offset, slot.prim));
    }

private:
    struct Slot {
        std::size_t offset;
        value::Prim prim;
        std::size_t var;
    };
    const Slot& resolve(std::string_view path) const {
        auto it = cache_.find(path);
        if (it != cache_.end()) return it->second;
        value::FieldIndex fi = s_.work_.locate(path);
        const value::ValueType& t = *fi.value_type;
        if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
            throw std::invalid_argument("constraint variable '" + std::string(path) + "' is not a numeric scalar leaf");
        std::size_t var = kNone;
        for (std::size_t i = 0; i < s_.vars_.size(); ++i)
            if (s_.vars_[i].offset == fi.offset) var = i;
        return cache_.emplace(std::string(path), Slot{fi.offset, t.prim(), var}).first->second;
    }
    const Synthesizer& s_;
    mutable std::map<std::string, Slot, std::less<>> cache_;
};

Synthesizer::Synthesizer(value::Value base, std::vector<DecisionLeaf> leaves, std::vector<Invariant> invariants,
                         SynthesisOptions options)
    : work_(base.clone()), options_(options), rng_(options.seed) {
    std::set<std::size_t> seen_offsets;
    for (auto& leaf : leaves) {
        value::FieldIndex fi = work_.locate(leaf.path);
        const value::ValueType& t = *fi.value_type;
        if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
            throw std::invalid_argument("Synthesizer: decision '" + leaf.path + "' is not a numeric scalar leaf");
        if (leaf.domain.empty()) throw std::invalid_argument("Synthesizer: empty domain for '" + leaf.path + "'");
        if (!seen_offsets.insert(fi.offset).second)
            throw std::invalid_argument("Synthesizer: duplicate decision leaf '" + leaf.path + "'");
        var_by_path_.emplace(leaf.path, vars_.size());
        vars_.push_back(Var{leaf.path, fi.offset, t.prim(), std::move(leaf.domain)});
    }
    const std::size_t n = vars_.size();

    // Split conjunctions so each conjunct is checked as early as possible.
    std::vector<Invariant> flat;
    for (const auto& inv : invariants) flatten_and(inv, flat);

    std::vector<std::size_t> degree(n, 0);
    for (auto& inv : flat) {
        Check c{inv, {}, false, inv.constraint().has_value(), 0};
        auto deps = inv.dependencies();
        if (!deps) {
            c.unknown_deps = true;
            for (std::size_t i = 0; i < n; ++i) c.vars.push_back(i);
        } else {
            std::set<std::size_t> vs;
            for (const auto& p : *deps) {
                value::FieldIndex fi = work_.locate(p);
                for (std::size_t i = 0; i < n; ++i)
                    if (vars_[i].offset >= fi.offset && vars_[i].offset < fi.offset + fi.length) vs.insert(i);
            }
            c.vars.assign(vs.begin(), vs.end());
        }
        if (!c.unknown_deps)
            for (std::size_t v : c.vars) ++degree[v];
        checks_.push_back(std::move(c));
    }

    order_.resize(n);
    for (std::size_t i = 0; i < n; ++i) order_[i] = i;
    using Order = SynthesisOptions::Order;
    if (options_.order == Order::SmallestDomainFirst) {
        std::stable_sort(order_.begin(), order_.end(), [&](std::size_t a, std::size_t b) {
            if (vars_[a].domain.size() != vars_[b].domain.size()) return vars_[a].domain.size() < vars_[b].domain.size();
            return degree[a] > degree[b];
        });
    } else if (options_.order == Order::MostConstrainedFirst) {
        std::stable_sort(order_.begin(), order_.end(), [&](std::size_t a, std::size_t b) {
            if (degree[a] != degree[b]) return degree[a] > degree[b];
            return vars_[a].domain.size() < vars_[b].domain.size();
        });
    }
    position_.resize(n);
    for (std::size_t p = 0; p < n; ++p) position_[order_[p]] = p;

    ready_.assign(n, {});
    touching_.assign(n, {});
    for (std::size_t ci = 0; ci < checks_.size(); ++ci) {
        Check& c = checks_[ci];
        if (c.vars.empty()) {
            root_checks_.push_back(ci);
            continue;
        }
        std::size_t last = 0;
        for (std::size_t v : c.vars) last = std::max(last, position_[v]);
        c.ready_at = last;
        ready_[last].push_back(ci);
        if (c.symbolic && !c.unknown_deps) {
            for (std::size_t v : c.vars)
                if (position_[v] < last) touching_[position_[v]].push_back(ci);
        }
    }
    for (auto& t : touching_) {
        std::sort(t.begin(), t.end());
        t.erase(std::unique(t.begin(), t.end()), t.end());
    }
    assigned_.assign(n, false);
}

double Synthesizer::read(std::size_t var) const {
    return value::load_as_double(work_.memory()->data() + vars_[var].offset, vars_[var].prim);
}

void Synthesizer::write(std::size_t var, double v) {
    value::store_from_double(work_.memory()->data() + vars_[var].offset, vars_[var].prim, v);
}

std::vector<double> Synthesizer::value_order(std::size_t var) {
    std::vector<double> vs = vars_[var].domain.values;
    if (options_.shuffle_values) std::shuffle(vs.begin(), vs.end(), rng_);
    return vs;
}

void Synthesizer::reset_stats() {
    stats_ = SynthesisStats{};
    std::fill(assigned_.begin(), assigned_.end(), false);
}

void Synthesizer::finish_stats(std::chrono::steady_clock::time_point start) {
    stats_.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
}

bool Synthesizer::consistent_at(std::size_t pos) {
    SearchEnv env(*this);
    for (std::size_t ci : ready_[pos]) {
        ++stats_.checks;
        const Check& c = checks_[ci];
        const bool ok = c.symbolic ? c.invariant.constraint()->eval(env) : c.invariant.holds(work_);
        if (!ok) return false;
    }
    if (options_.interval_pruning) {
        for (std::size_t ci : touching_[pos]) {
            ++stats_.checks;
            if (checks_[ci].invariant.feasible(env) == Tri::False) {
                ++stats_.prunes;
                return false;
            }
        }
    }
    return true;
}

template <class OnSolution>
void Synthesizer::dfs(std::size_t pos, OnSolution& on_solution, bool& stop) {
    if (pos == vars_.size()) {
        ++stats_.solutions;
        if (!on_solution(work_)) stop = true;
        return;
    }
    const std::size_t var = order_[pos];
    for (double v : value_order(var)) {
        if (stats_.nodes >= options_.max_nodes) {
            stop = true;
            return;
        }
        ++stats_.nodes;
        write(var, v);
        assigned_[var] = true;
        if (consistent_at(pos)) dfs(pos + 1, on_solution, stop);
        assigned_[var] = false;
        if (stop) return;
    }
    ++stats_.backtracks;
}

namespace {
bool root_ok(const std::vector<std::size_t>& roots, const auto& checks, const value::Value& v, SynthesisStats& st) {
    for (std::size_t ci : roots) {
        ++st.checks;
        if (!checks[ci].invariant.holds(v)) return false;
    }
    return true;
}
}  // namespace

std::vector<value::Value> Synthesizer::enumerate(std::size_t limit) {
    reset_stats();
    const auto start = std::chrono::steady_clock::now();
    std::vector<value::Value> out;
    bool stop = false;
    if (limit > 0 && root_ok(root_checks_, checks_, work_, stats_)) {
        auto on_solution = [&](const value::Value& v) {
            out.push_back(v.clone());
            return out.size() < limit;
        };
        dfs(0, on_solution, stop);
    }
    stats_.exhausted = !stop;
    finish_stats(start);
    return out;
}

std::optional<value::Value> Synthesizer::solve() {
    auto r = enumerate(1);
    if (r.empty()) return std::nullopt;
    return std::move(r.front());
}

std::size_t Synthesizer::count(std::size_t limit) {
    reset_stats();
    const auto start = std::chrono::steady_clock::now();
    std::size_t n = 0;
    bool stop = false;
    if (limit > 0 && root_ok(root_checks_, checks_, work_, stats_)) {
        auto on_solution = [&](const value::Value&) { return ++n < limit; };
        dfs(0, on_solution, stop);
    }
    stats_.exhausted = !stop;
    finish_stats(start);
    return n;
}

std::optional<value::Value> Synthesizer::nearest(const value::Value& target, Norm norm) {
    if (!target.type()->layout_equals(*work_.type()))
        throw std::invalid_argument("Synthesizer::nearest: target layout mismatch");
    reset_stats();
    const auto start = std::chrono::steady_clock::now();
    std::optional<value::Value> best;
    if (!root_ok(root_checks_, checks_, work_, stats_)) {
        stats_.exhausted = true;
        finish_stats(start);
        return best;
    }

    // Cost contributed by fixed (non-decision) leaves.
    std::set<std::size_t> decision_offsets;
    for (const auto& v : vars_) decision_offsets.insert(v.offset - work_.offset());
    double fixed = 0;
    value::for_each_leaf(*work_.type(), [&](std::size_t off, value::Prim prim, std::size_t size) {
        if (decision_offsets.count(off)) return;
        double diff;
        if (value::prim_is_numeric(prim)) {
            const double a = value::load_as_double(work_.data() + off, prim);
            const double b = value::load_as_double(target.data() + off, prim);
            diff = a == b ? 0.0 : a - b;
        } else {
            diff = std::memcmp(work_.data() + off, target.data() + off, size) == 0 ? 0.0 : 1.0;
        }
        fixed = norm_accumulate(norm, fixed, diff);
    });

    std::vector<double> target_vals(vars_.size());
    for (std::size_t i = 0; i < vars_.size(); ++i)
        target_vals[i] = value::load_as_double(target.memory()->data() + target.offset() +
                                                   (vars_[i].offset - work_.offset()),
                                               vars_[i].prim);

    double best_cost = std::numeric_limits<double>::infinity();
    bool stop = false;
    auto rec = [&](auto& self, std::size_t pos, double acc) -> void {
        if (pos == vars_.size()) {
            ++stats_.solutions;
            if (acc < best_cost) {
                best_cost = acc;
                best = work_.clone();
            }
            return;
        }
        const std::size_t var = order_[pos];
        std::vector<double> vs = vars_[var].domain.values;
        const double t = target_vals[var];
        std::stable_sort(vs.begin(), vs.end(), [t](double a, double b) { return std::fabs(a - t) < std::fabs(b - t); });
        for (double v : vs) {
            const double next = norm_accumulate(norm, acc, v - t);
            if (next >= best_cost) {
                ++stats_.prunes;
                break;  // values are sorted by distance, so every later value is no better
            }
            if (stats_.nodes >= options_.max_nodes) {
                stop = true;
                return;
            }
            ++stats_.nodes;
            write(var, v);
            assigned_[var] = true;
            if (consistent_at(pos)) self(self, pos + 1, next);
            assigned_[var] = false;
            if (stop) return;
        }
        ++stats_.backtracks;
    };
    rec(rec, 0, fixed);
    stats_.exhausted = !stop;
    finish_stats(start);
    return best;
}

std::size_t Synthesizer::violated_count() {
    std::size_t bad = 0;
    for (const auto& c : checks_) {
        ++stats_.checks;
        if (!c.invariant.holds(work_)) ++bad;
    }
    return bad;
}

std::optional<value::Value> Synthesizer::min_conflicts(std::uint64_t max_steps, double noise) {
    reset_stats();
    const auto start = std::chrono::steady_clock::now();
    const std::size_t n = vars_.size();
    std::vector<std::vector<std::size_t>> var_checks(n);
    for (std::size_t ci = 0; ci < checks_.size(); ++ci)
        for (std::size_t v : checks_[ci].vars) var_checks[v].push_back(ci);

    for (std::size_t i = 0; i < n; ++i) {
        const auto& d = vars_[i].domain.values;
        write(i, d[std::uniform_int_distribution<std::size_t>(0, d.size() - 1)(rng_)]);
        assigned_[i] = true;
    }
    if (!root_ok(root_checks_, checks_, work_, stats_)) {
        finish_stats(start);
        return std::nullopt;
    }
    std::bernoulli_distribution explore(noise);
    std::vector<std::size_t> violated;
    for (std::uint64_t step = 0; step < max_steps; ++step) {
        violated.clear();
        for (std::size_t ci = 0; ci < checks_.size(); ++ci) {
            ++stats_.checks;
            if (!checks_[ci].invariant.holds(work_)) violated.push_back(ci);
        }
        if (violated.empty()) {
            ++stats_.solutions;
            finish_stats(start);
            return work_.clone();
        }
        if (n == 0) break;
        ++stats_.nodes;
        const Check& c = checks_[violated[std::uniform_int_distribution<std::size_t>(0, violated.size() - 1)(rng_)]];
        if (c.vars.empty()) break;  // violated with nothing to change
        const std::size_t var = c.vars[std::uniform_int_distribution<std::size_t>(0, c.vars.size() - 1)(rng_)];
        const auto& dom = vars_[var].domain.values;
        if (explore(rng_)) {
            write(var, dom[std::uniform_int_distribution<std::size_t>(0, dom.size() - 1)(rng_)]);
            continue;
        }
        std::vector<double> candidates;
        if (dom.size() <= 64) {
            candidates = dom;
        } else {
            for (int k = 0; k < 64; ++k) candidates.push_back(dom[std::uniform_int_distribution<std::size_t>(0, dom.size() - 1)(rng_)]);
            candidates.push_back(read(var));
        }
        std::size_t best_bad = SIZE_MAX;
        std::vector<double> best_vals;
        for (double v : candidates) {
            write(var, v);
            std::size_t bad = 0;
            for (std::size_t ci : var_checks[var]) {
                ++stats_.checks;
                if (!checks_[ci].invariant.holds(work_)) ++bad;
            }
            if (bad < best_bad) {
                best_bad = bad;
                best_vals.assign(1, v);
            } else if (bad == best_bad) {
                best_vals.push_back(v);
            }
        }
        write(var, best_vals[std::uniform_int_distribution<std::size_t>(0, best_vals.size() - 1)(rng_)]);
    }
    finish_stats(start);
    return std::nullopt;
}

}  // namespace combinatorial::problem
