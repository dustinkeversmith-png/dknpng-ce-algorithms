#include "combinatorial/problem/Space.hpp"

#include <limits>
#include <stdexcept>

namespace combinatorial::problem {

std::vector<Invariant> Space::invariants() const {
    auto all = structural_invariants();
    auto sem = semantic_invariants();
    all.insert(all.end(), sem.begin(), sem.end());
    return all;
}

bool Space::contains(const value::Value& v) const {
    if (!v.type()->layout_equals(*value_type())) return false;
    for (const auto& inv : invariants())
        if (!inv.holds(v)) return false;
    return true;
}

std::vector<std::string> Space::explain(const value::Value& v) const {
    if (!v.type()->layout_equals(*value_type()))
        return {"layout mismatch: expected " + value_type()->signature() + ", got " + v.type()->signature()};
    std::vector<std::string> out;
    for (const auto& inv : invariants())
        for (auto& msg : inv.violations(v)) out.push_back(std::move(msg));
    return out;
}

std::vector<value::Value> Space::enumerate(std::size_t) const { return {}; }

std::optional<value::Value> Space::project(const value::Value& v) const {
    if (contains(v)) return v.clone();
    std::optional<value::Value> best;
    double best_d = std::numeric_limits<double>::infinity();
    auto consider = [&](const value::Value& c) {
        const double d = distance(v, c);
        if (d < best_d) {
            best_d = d;
            best = c.clone();
        }
    };
    if (is_finite()) {
        for (const auto& c : enumerate(std::numeric_limits<std::size_t>::max())) consider(c);
        return best;
    }
    Rng rng(v.hash());
    for (std::size_t i = 0; i < projection_samples(); ++i)
        if (auto c = generate(rng)) consider(*c);
    return best;
}

namespace {

void require_same_type(const SpacePtr& a, const SpacePtr& b, const char* op) {
    if (!a || !b) throw std::invalid_argument(std::string(op) + ": null space");
    if (!a->value_type()->layout_equals(*b->value_type()))
        throw std::invalid_argument(std::string(op) + ": spaces have different layouts (" +
                                    a->value_type()->signature() + " vs " + b->value_type()->signature() + ")");
}

constexpr int kRejectionAttempts = 512;

class IntersectionSpace final : public Space {
public:
    IntersectionSpace(SpacePtr a, SpacePtr b) : a_(std::move(a)), b_(std::move(b)) {}
    std::string description() const override { return "(" + a_->description() + " ∩ " + b_->description() + ")"; }
    value::TypePtr value_type() const override { return a_->value_type(); }
    std::vector<Invariant> structural_invariants() const override {
        auto s = a_->structural_invariants();
        auto t = b_->structural_invariants();
        s.insert(s.end(), t.begin(), t.end());
        return s;
    }
    std::vector<Invariant> semantic_invariants() const override {
        auto s = a_->semantic_invariants();
        auto t = b_->semantic_invariants();
        s.insert(s.end(), t.begin(), t.end());
        return s;
    }
    bool contains(const value::Value& v) const override { return a_->contains(v) && b_->contains(v); }
    Norm norm() const override { return a_->norm(); }
    double distance(const value::Value& x, const value::Value& y) const override { return a_->distance(x, y); }
    bool is_finite() const override { return a_->is_finite() || b_->is_finite(); }
    std::vector<value::Value> enumerate(std::size_t limit) const override {
        const Space& src = a_->is_finite() ? *a_ : *b_;
        const Space& other = a_->is_finite() ? *b_ : *a_;
        std::vector<value::Value> out;
        for (auto& v : src.enumerate(std::numeric_limits<std::size_t>::max())) {
            if (out.size() >= limit) break;
            if (other.contains(v)) out.push_back(std::move(v));
        }
        return out;
    }
    std::optional<value::Value> generate(Rng& rng) const override {
        for (int i = 0; i < kRejectionAttempts; ++i) {
            if (auto v = a_->generate(rng); v && b_->contains(*v)) return v;
            if (auto v = b_->generate(rng); v && a_->contains(*v)) return v;
        }
        if (is_finite()) {
            auto all = enumerate(std::numeric_limits<std::size_t>::max());
            if (!all.empty()) return all[std::uniform_int_distribution<std::size_t>(0, all.size() - 1)(rng)];
        }
        return std::nullopt;
    }

private:
    SpacePtr a_, b_;
};

class UnionSpace final : public Space {
public:
    UnionSpace(SpacePtr a, SpacePtr b) : a_(std::move(a)), b_(std::move(b)) {}
    std::string description() const override { return "(" + a_->description() + " ∪ " + b_->description() + ")"; }
    value::TypePtr value_type() const override { return a_->value_type(); }
    std::vector<Invariant> semantic_invariants() const override { return {a_->invariant() || b_->invariant()}; }
    bool contains(const value::Value& v) const override { return a_->contains(v) || b_->contains(v); }
    Norm norm() const override { return a_->norm(); }
    double distance(const value::Value& x, const value::Value& y) const override { return a_->distance(x, y); }
    bool is_finite() const override { return a_->is_finite() && b_->is_finite(); }
    std::vector<value::Value> enumerate(std::size_t limit) const override {
        auto out = a_->enumerate(limit);
        for (auto& v : b_->enumerate(std::numeric_limits<std::size_t>::max())) {
            if (out.size() >= limit) break;
            if (!a_->contains(v)) out.push_back(std::move(v));
        }
        return out;
    }
    std::optional<value::Value> project(const value::Value& v) const override {
        auto pa = a_->project(v), pb = b_->project(v);
        if (!pa) return pb;
        if (!pb) return pa;
        return distance(v, *pa) <= distance(v, *pb) ? pa : pb;
    }
    std::optional<value::Value> generate(Rng& rng) const override {
        const bool first = std::bernoulli_distribution(0.5)(rng);
        const Space& x = first ? *a_ : *b_;
        const Space& y = first ? *b_ : *a_;
        if (auto v = x.generate(rng)) return v;
        return y.generate(rng);
    }

private:
    SpacePtr a_, b_;
};

class DifferenceSpace final : public Space {
public:
    DifferenceSpace(SpacePtr a, SpacePtr b) : a_(std::move(a)), b_(std::move(b)) {}
    std::string description() const override { return "(" + a_->description() + " \\ " + b_->description() + ")"; }
    value::TypePtr value_type() const override { return a_->value_type(); }
    std::vector<Invariant> structural_invariants() const override { return a_->structural_invariants(); }
    std::vector<Invariant> semantic_invariants() const override {
        auto s = a_->semantic_invariants();
        s.push_back(!b_->invariant());
        return s;
    }
    bool contains(const value::Value& v) const override { return a_->contains(v) && !b_->contains(v); }
    Norm norm() const override { return a_->norm(); }
    double distance(const value::Value& x, const value::Value& y) const override { return a_->distance(x, y); }
    bool is_finite() const override { return a_->is_finite(); }
    std::vector<value::Value> enumerate(std::size_t limit) const override {
        std::vector<value::Value> out;
        for (auto& v : a_->enumerate(std::numeric_limits<std::size_t>::max())) {
            if (out.size() >= limit) break;
            if (!b_->contains(v)) out.push_back(std::move(v));
        }
        return out;
    }
    std::optional<value::Value> generate(Rng& rng) const override {
        for (int i = 0; i < kRejectionAttempts; ++i)
            if (auto v = a_->generate(rng); v && !b_->contains(*v)) return v;
        if (is_finite()) {
            auto all = enumerate(std::numeric_limits<std::size_t>::max());
            if (!all.empty()) return all[std::uniform_int_distribution<std::size_t>(0, all.size() - 1)(rng)];
        }
        return std::nullopt;
    }

private:
    SpacePtr a_, b_;
};

}  // namespace

SpacePtr intersect(SpacePtr a, SpacePtr b) {
    require_same_type(a, b, "intersect");
    return std::make_shared<IntersectionSpace>(std::move(a), std::move(b));
}
SpacePtr unite(SpacePtr a, SpacePtr b) {
    require_same_type(a, b, "unite");
    return std::make_shared<UnionSpace>(std::move(a), std::move(b));
}
SpacePtr difference(SpacePtr a, SpacePtr b) {
    require_same_type(a, b, "difference");
    return std::make_shared<DifferenceSpace>(std::move(a), std::move(b));
}

PredicateSpace::PredicateSpace(std::string description, value::TypePtr type, std::vector<Invariant> semantic,
                               Generator generator, std::vector<Invariant> structural)
    : description_(std::move(description)),
      type_(std::move(type)),
      semantic_(std::move(semantic)),
      generator_(std::move(generator)),
      structural_(std::move(structural)) {
    if (!type_) throw std::invalid_argument("PredicateSpace: null type");
}

std::optional<value::Value> PredicateSpace::generate(Rng& rng) const {
    if (!generator_) return std::nullopt;
    for (int i = 0; i < kRejectionAttempts; ++i)
        if (auto v = generator_(rng); v && contains(*v)) return v;
    return std::nullopt;
}

}  // namespace combinatorial::problem
