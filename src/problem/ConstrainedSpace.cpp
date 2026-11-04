#include "combinatorial/problem/ConstrainedSpace.hpp"

#include "combinatorial/value/Layout.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <limits>
#include <set>
#include <stdexcept>
#include <unordered_set>

namespace combinatorial::problem {

namespace {
std::string byte_key(const value::Value& v) {
    return std::string(reinterpret_cast<const char*>(v.data()), v.size());
}
}  // namespace

ConstrainedSpace::ConstrainedSpace(std::string description, value::Value base, std::vector<DecisionLeaf> leaves,
                                   std::vector<Invariant> semantic, Norm norm, SynthesisOptions options)
    : description_(std::move(description)),
      base_(base.clone()),
      leaves_(std::move(leaves)),
      semantic_(std::move(semantic)),
      norm_(norm),
      options_(options) {
    // Structural invariants: each decision leaf lies in its domain, and every
    // non-decision leaf keeps its base value.
    std::set<std::size_t> decision_offsets;
    for (const auto& leaf : leaves_) {
        value::FieldIndex fi = base_.locate(leaf.path);
        if (!fi.value_type->is_primitive() || !fi.value_type->is_scalar() ||
            !value::prim_is_numeric(fi.value_type->prim()))
            throw std::invalid_argument("ConstrainedSpace: decision '" + leaf.path + "' is not a numeric scalar leaf");
        decision_offsets.insert(fi.offset);
        const std::string path = leaf.path;
        const Domain dom = leaf.domain;
        structural_.emplace_back(
            path + " in domain",
            [path, dom](const value::Value& v) { return dom.contains(v.reference(path).as<double>()); },
            [path](const value::Value& v) { return path + " = " + v.reference(path).to_string() + " not in domain"; },
            std::vector<std::string>{path});
    }
    std::vector<std::pair<std::size_t, std::size_t>> fixed;  // (offset, size)
    value::for_each_leaf(*base_.type(), [&](std::size_t off, value::Prim, std::size_t size) {
        if (!decision_offsets.count(off)) fixed.emplace_back(off, size);
    });
    if (!fixed.empty()) {
        value::Value ref = base_;
        structural_.emplace_back(
            "fixed leaves match base",
            [ref, fixed](const value::Value& v) {
                for (auto [off, size] : fixed)
                    if (std::memcmp(v.data() + off, ref.data() + off, size) != 0) return false;
                return true;
            },
            [fixed, ref](const value::Value& v) {
                for (auto [off, size] : fixed)
                    if (std::memcmp(v.data() + off, ref.data() + off, size) != 0)
                        return "non-decision leaf at byte " + std::to_string(off) + " differs from base";
                return std::string();
            });
    }
}

std::shared_ptr<ConstrainedSpace> ConstrainedSpace::over_all_leaves(std::string description, value::TypePtr type,
                                                                    const Domain& domain,
                                                                    std::vector<Invariant> semantic, Norm norm,
                                                                    SynthesisOptions options) {
    std::vector<DecisionLeaf> leaves;
    for (const auto& leaf : value::flatten(*type)) leaves.push_back(DecisionLeaf{leaf.path, domain});
    return std::make_shared<ConstrainedSpace>(std::move(description), value::Value(type), std::move(leaves),
                                              std::move(semantic), norm, options);
}

Synthesizer ConstrainedSpace::synthesizer(SynthesisOptions options) const {
    return Synthesizer(base_, leaves_, semantic_, options);
}

std::optional<value::Value> ConstrainedSpace::generate(Rng& rng) const {
    SynthesisOptions o = options_;
    o.shuffle_values = true;
    o.seed = rng();
    return synthesizer(o).solve();
}

std::vector<value::Value> ConstrainedSpace::enumerate(std::size_t limit) const { return synthesizer().enumerate(limit); }

std::size_t ConstrainedSpace::count(std::size_t limit) const { return synthesizer().count(limit); }

std::optional<value::Value> ConstrainedSpace::project(const value::Value& v) const {
    if (contains(v)) return v.clone();
    return synthesizer().nearest(v, norm_);
}

std::vector<value::Value> ConstrainedSpace::neighbors(const value::Value& v) const {
    std::vector<value::Value> out;
    for (const auto& leaf : leaves_) {
        value::Value slot = v.reference(leaf.path);
        const double cur = slot.as<double>();
        const std::size_t idx = leaf.domain.index_of(cur);
        if (idx == leaf.domain.size()) continue;
        for (std::size_t j : {idx - 1, idx + 1}) {
            if (j >= leaf.domain.size()) continue;  // also catches idx-1 underflow
            value::Value n = v.clone();
            n.reference(leaf.path).assign(leaf.domain.values[j]);
            if (contains(n)) out.push_back(std::move(n));
        }
    }
    return out;
}

std::vector<value::Value> ConstrainedSpace::reachable(const value::Value& from, std::size_t limit) const {
    std::vector<value::Value> out;
    if (!contains(from) || limit == 0) return out;
    std::unordered_set<std::string> seen{byte_key(from)};
    std::deque<value::Value> q{from.clone()};
    while (!q.empty() && out.size() < limit) {
        value::Value cur = std::move(q.front());
        q.pop_front();
        for (auto& n : neighbors(cur))
            if (seen.insert(byte_key(n)).second) q.push_back(std::move(n));
        out.push_back(std::move(cur));
    }
    return out;
}

std::size_t ConstrainedSpace::connected_components(std::size_t limit) const {
    auto members = enumerate(limit);
    std::unordered_set<std::string> seen;
    std::size_t components = 0;
    for (const auto& m : members) {
        if (seen.count(byte_key(m))) continue;
        ++components;
        for (const auto& r : reachable(m, limit)) seen.insert(byte_key(r));
    }
    return components;
}

// ------------------------------------------------------------- BoxSpace ---

BoxSpace::BoxSpace(std::string description, value::TypePtr type, Interval bounds,
                   std::map<std::string, Interval> overrides, Norm norm)
    : description_(std::move(description)), type_(std::move(type)), norm_(norm) {
    if (!value::is_numeric_layout(*type_)) throw std::invalid_argument("BoxSpace: layout has opaque leaves");
    for (auto& leaf : value::flatten(*type_)) {
        Interval b = bounds;
        if (auto it = overrides.find(leaf.path); it != overrides.end()) {
            b = it->second;
            overrides.erase(it);
        }
        if (b.lo > b.hi) throw std::invalid_argument("BoxSpace: empty interval for '" + leaf.path + "'");
        slots_.push_back(Slot{leaf.path, leaf.offset, leaf.prim, b});
    }
    if (!overrides.empty())
        throw std::invalid_argument("BoxSpace: override for unknown leaf '" + overrides.begin()->first + "'");
}

std::vector<Invariant> BoxSpace::structural_invariants() const {
    std::vector<Invariant> out;
    out.reserve(slots_.size());
    for (const auto& s : slots_) out.emplace_back(Constraint::between(var(s.path), s.bounds.lo, s.bounds.hi));
    return out;
}

bool BoxSpace::contains(const value::Value& v) const {
    if (!v.type()->layout_equals(*type_)) return false;
    for (const auto& s : slots_) {
        const double x = value::load_as_double(v.data() + s.offset, s.prim);
        if (!(s.bounds.lo <= x && x <= s.bounds.hi)) return false;
    }
    return true;
}

std::optional<value::Value> BoxSpace::project(const value::Value& v) const {
    if (!v.type()->layout_equals(*type_)) throw std::invalid_argument("BoxSpace::project: layout mismatch");
    value::Value out = v.clone();
    for (const auto& s : slots_) {
        double x = value::load_as_double(out.data() + s.offset, s.prim);
        if (std::isnan(x)) x = std::isfinite(s.bounds.lo) ? s.bounds.lo : (std::isfinite(s.bounds.hi) ? s.bounds.hi : 0.0);
        double lo = s.bounds.lo, hi = s.bounds.hi;
        if (!value::prim_is_float(s.prim)) {
            lo = std::ceil(lo);
            hi = std::floor(hi);
        }
        value::store_from_double(out.data() + s.offset, s.prim, std::clamp(x, lo, hi));
    }
    return out;
}

std::optional<value::Value> BoxSpace::generate(Rng& rng) const {
    value::Value out(type_);
    for (const auto& s : slots_) {
        const double lo = std::max(s.bounds.lo, -1e300), hi = std::min(s.bounds.hi, 1e300);
        double x;
        if (value::prim_is_float(s.prim)) {
            x = lo == hi ? lo : std::uniform_real_distribution<double>(lo, hi)(rng);
        } else {
            const double a = std::ceil(lo), b = std::floor(hi);
            if (a > b) return std::nullopt;
            x = std::floor(std::uniform_real_distribution<double>(a, std::nextafter(b + 1, b))(rng));
            x = std::clamp(x, a, b);
        }
        value::store_from_double(out.data() + s.offset, s.prim, x);
    }
    return out;
}

}  // namespace combinatorial::problem
