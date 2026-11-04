#include "combinatorial/problem/Synthesizer.hpp"

#include "combinatorial/value/Layout.hpp"

#include "IntervalMath.hpp"

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

// ---------------------------------------------------- compiled formulas ---
//
// A symbolic conjunct is compiled once into two flat node arrays. Variables
// become slot indices with pre-resolved byte offsets, so evaluating at a
// search node reads the working buffer directly instead of parsing paths.
// Semantics are shared with Constraint through IntervalMath.hpp.

struct Synthesizer::Compiled {
    struct ENode {
        Expr::Op op;
        double constant = 0;
        std::uint32_t slot = 0;
        std::uint32_t a = 0, b = 0;  // child expression nodes
    };
    struct FNode {
        Constraint::Op op;
        std::uint32_t lhs = 0, rhs = 0;    // expression nodes (comparisons)
        std::vector<std::uint32_t> kids;   // formula nodes (And / Or / Not)
    };
    std::vector<ENode> e;
    std::vector<FNode> f;
    std::uint32_t root = 0;
};

namespace {

using Compiled = Synthesizer::Compiled;

/// Evaluation context: the working buffer plus what is known about each slot.
struct Ctx {
    const std::byte* base;
    const std::vector<Synthesizer::Slot>& slots;
    const std::vector<bool>& assigned;
    const std::vector<double>& lo;
    const std::vector<double>& hi;

    double load(std::uint32_t s) const { return value::load_as_double(base + slots[s].offset, slots[s].prim); }
    Interval range(std::uint32_t s) const {
        const std::size_t v = slots[s].var;
        if (v != kNone && !assigned[v]) return {lo[v], hi[v]};
        return Interval::point(load(s));
    }
};

double eval_e(const Compiled& c, std::uint32_t i, const Ctx& x) {
    const auto& n = c.e[i];
    switch (n.op) {
        case Expr::Op::Const: return n.constant;
        case Expr::Op::Var: return x.load(n.slot);
        case Expr::Op::Add: return eval_e(c, n.a, x) + eval_e(c, n.b, x);
        case Expr::Op::Sub: return eval_e(c, n.a, x) - eval_e(c, n.b, x);
        case Expr::Op::Mul: return eval_e(c, n.a, x) * eval_e(c, n.b, x);
        case Expr::Op::Neg: return -eval_e(c, n.a, x);
        case Expr::Op::Abs: return std::fabs(eval_e(c, n.a, x));
        case Expr::Op::Min: return std::min(eval_e(c, n.a, x), eval_e(c, n.b, x));
        case Expr::Op::Max: return std::max(eval_e(c, n.a, x), eval_e(c, n.b, x));
    }
    return 0;
}

Interval bound_e(const Compiled& c, std::uint32_t i, const Ctx& x) {
    const auto& n = c.e[i];
    switch (n.op) {
        case Expr::Op::Const: return Interval::point(n.constant);
        case Expr::Op::Var: return x.range(n.slot);
        case Expr::Op::Add: return detail::iv_add(bound_e(c, n.a, x), bound_e(c, n.b, x));
        case Expr::Op::Sub: return detail::iv_add(bound_e(c, n.a, x), detail::iv_neg(bound_e(c, n.b, x)));
        case Expr::Op::Mul: return detail::iv_mul(bound_e(c, n.a, x), bound_e(c, n.b, x));
        case Expr::Op::Neg: return detail::iv_neg(bound_e(c, n.a, x));
        case Expr::Op::Abs: return detail::iv_abs(bound_e(c, n.a, x));
        case Expr::Op::Min: return detail::iv_min(bound_e(c, n.a, x), bound_e(c, n.b, x));
        case Expr::Op::Max: return detail::iv_max(bound_e(c, n.a, x), bound_e(c, n.b, x));
    }
    return Interval::everything();
}

bool eval_f(const Compiled& c, std::uint32_t i, const Ctx& x) {
    const auto& n = c.f[i];
    switch (n.op) {
        case Constraint::Op::True: return true;
        case Constraint::Op::False: return false;
        case Constraint::Op::And:
            for (auto k : n.kids)
                if (!eval_f(c, k, x)) return false;
            return true;
        case Constraint::Op::Or:
            for (auto k : n.kids)
                if (eval_f(c, k, x)) return true;
            return false;
        case Constraint::Op::Not: return !eval_f(c, n.kids[0], x);
        default: return detail::compare_values(n.op, eval_e(c, n.lhs, x), eval_e(c, n.rhs, x));
    }
}

Tri feasible_f(const Compiled& c, std::uint32_t i, const Ctx& x) {
    const auto& n = c.f[i];
    switch (n.op) {
        case Constraint::Op::True: return Tri::True;
        case Constraint::Op::False: return Tri::False;
        case Constraint::Op::And: {
            Tri r = Tri::True;
            for (auto k : n.kids) {
                const Tri t = feasible_f(c, k, x);
                if (t == Tri::False) return Tri::False;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
        case Constraint::Op::Or: {
            Tri r = Tri::False;
            for (auto k : n.kids) {
                const Tri t = feasible_f(c, k, x);
                if (t == Tri::True) return Tri::True;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
        case Constraint::Op::Not: return detail::tri_not(feasible_f(c, n.kids[0], x));
        default: return detail::compare_intervals(n.op, bound_e(c, n.lhs, x), bound_e(c, n.rhs, x));
    }
}

template <class SlotFor>
std::uint32_t compile_e(const Expr& e, Compiled& out, SlotFor& slot_for) {
    Compiled::ENode n{e.op(), 0, 0, 0, 0};
    if (e.op() == Expr::Op::Const) n.constant = e.constant_value();
    if (e.op() == Expr::Op::Var) n.slot = static_cast<std::uint32_t>(slot_for(e.path()));
    if (!e.args().empty()) n.a = compile_e(e.args()[0], out, slot_for);
    if (e.args().size() > 1) n.b = compile_e(e.args()[1], out, slot_for);
    out.e.push_back(n);
    return static_cast<std::uint32_t>(out.e.size() - 1);
}

template <class SlotFor>
std::uint32_t compile_f(const Constraint& c, Compiled& out, SlotFor& slot_for) {
    Compiled::FNode n{c.op(), 0, 0, {}};
    if (c.is_comparison()) {
        n.lhs = compile_e(c.lhs(), out, slot_for);
        n.rhs = compile_e(c.rhs(), out, slot_for);
    }
    for (const auto& k : c.args()) n.kids.push_back(compile_f(k, out, slot_for));
    out.f.push_back(std::move(n));
    return static_cast<std::uint32_t>(out.f.size() - 1);
}

}  // namespace

std::size_t Synthesizer::slot_for(const std::string& path) {
    if (auto it = slot_by_path_.find(path); it != slot_by_path_.end()) return it->second;
    value::FieldIndex fi = work_.locate(path);
    const value::ValueType& t = *fi.value_type;
    if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
        throw std::invalid_argument("constraint variable '" + path + "' is not a numeric scalar leaf");
    std::size_t var = kNone;
    for (std::size_t i = 0; i < vars_.size(); ++i)
        if (vars_[i].offset == fi.offset) var = i;
    slots_.push_back(Slot{fi.offset, t.prim(), var});
    slot_by_path_.emplace(path, slots_.size() - 1);
    return slots_.size() - 1;
}

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
        Check c{inv, {}, false, inv.constraint().has_value(), 0, nullptr};
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

    // Compile symbolic conjuncts against resolved slots.
    hull_lo_.resize(n);
    hull_hi_.resize(n);
    for (std::size_t i = 0; i < n; ++i) {
        hull_lo_[i] = vars_[i].domain.lo();
        hull_hi_[i] = vars_[i].domain.hi();
    }
    auto resolve = [this](const std::string& p) { return slot_for(p); };
    for (Check& c : checks_) {
        if (!c.symbolic) continue;
        auto compiled = std::make_shared<Compiled>();
        compiled->root = compile_f(*c.invariant.constraint(), *compiled, resolve);
        c.compiled = std::move(compiled);
    }
    shuffle_buf_.assign(n, {});
}

bool Synthesizer::check_holds(const Check& c) const {
    if (!c.compiled) return c.invariant.holds(work_);
    const Ctx x{work_.memory()->data(), slots_, assigned_, hull_lo_, hull_hi_};
    return eval_f(*c.compiled, c.compiled->root, x);
}

Tri Synthesizer::check_feasible(const Check& c) const {
    if (!c.compiled) return Tri::Unknown;
    const Ctx x{work_.memory()->data(), slots_, assigned_, hull_lo_, hull_hi_};
    return feasible_f(*c.compiled, c.compiled->root, x);
}

double Synthesizer::read(std::size_t var) const {
    return value::load_as_double(work_.memory()->data() + vars_[var].offset, vars_[var].prim);
}

void Synthesizer::write(std::size_t var, double v) {
    value::store_from_double(work_.memory()->data() + vars_[var].offset, vars_[var].prim, v);
}

const std::vector<double>& Synthesizer::value_order(std::size_t var, std::size_t pos) {
    if (!options_.shuffle_values) return vars_[var].domain.values;
    std::vector<double>& buf = shuffle_buf_[pos];
    buf = vars_[var].domain.values;
    std::shuffle(buf.begin(), buf.end(), rng_);
    return buf;
}

void Synthesizer::reset_stats() {
    stats_ = SynthesisStats{};
    std::fill(assigned_.begin(), assigned_.end(), false);
}

void Synthesizer::finish_stats(std::chrono::steady_clock::time_point start) {
    stats_.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
}

bool Synthesizer::consistent_at(std::size_t pos) {
    for (std::size_t ci : ready_[pos]) {
        ++stats_.checks;
        if (!check_holds(checks_[ci])) return false;
    }
    if (options_.interval_pruning) {
        for (std::size_t ci : touching_[pos]) {
            ++stats_.checks;
            if (check_feasible(checks_[ci]) == Tri::False) {
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
    for (double v : value_order(var, pos)) {
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

    // Each decision's values sorted by distance to the target, computed once.
    std::vector<std::vector<double>> sorted(vars_.size());
    for (std::size_t i = 0; i < vars_.size(); ++i) {
        sorted[i] = vars_[i].domain.values;
        const double t = target_vals[i];
        std::stable_sort(sorted[i].begin(), sorted[i].end(),
                         [t](double a, double b) { return std::fabs(a - t) < std::fabs(b - t); });
    }

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
        const double t = target_vals[var];
        for (double v : sorted[var]) {
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
    std::vector<double> candidates, best_vals;
    for (std::uint64_t step = 0; step < max_steps; ++step) {
        violated.clear();
        for (std::size_t ci = 0; ci < checks_.size(); ++ci) {
            ++stats_.checks;
            if (!check_holds(checks_[ci])) violated.push_back(ci);
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
        candidates.clear();
        if (dom.size() <= 64) {
            candidates.assign(dom.begin(), dom.end());
        } else {
            for (int k = 0; k < 64; ++k) candidates.push_back(dom[std::uniform_int_distribution<std::size_t>(0, dom.size() - 1)(rng_)]);
            candidates.push_back(read(var));
        }
        std::size_t best_bad = SIZE_MAX;
        best_vals.clear();
        for (double v : candidates) {
            write(var, v);
            std::size_t bad = 0;
            for (std::size_t ci : var_checks[var]) {
                ++stats_.checks;
                if (!check_holds(checks_[ci])) ++bad;
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
