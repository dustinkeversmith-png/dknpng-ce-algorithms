#include "alg_exp/problem/Invariant.hpp"

#include <stdexcept>

namespace alg_exp::problem {

struct Invariant::Node {
    Kind kind;
    std::string description;
    Predicate predicate;
    Explainer explainer;
    std::optional<std::set<std::string>> deps;
    std::optional<Constraint> constraint;
    std::vector<Invariant> children;
    std::size_t size = 1;
};

namespace {
std::size_t tree_size(const std::vector<Invariant>& xs) {
    std::size_t n = 1;
    for (const auto& x : xs) n += x.size();
    return n;
}
}  // namespace

Invariant::Invariant(std::string description, Predicate predicate, Explainer explainer,
                     std::vector<std::string> depends_on) {
    if (!predicate) throw std::invalid_argument("Invariant '" + description + "': null predicate");
    auto n = std::make_shared<Node>();
    n->kind = Kind::Predicate;
    n->description = std::move(description);
    n->predicate = std::move(predicate);
    n->explainer = std::move(explainer);
    if (!depends_on.empty()) n->deps = std::set<std::string>(depends_on.begin(), depends_on.end());
    node_ = std::move(n);
}

Invariant::Invariant(std::string description, Constraint constraint) {
    auto n = std::make_shared<Node>();
    n->kind = Kind::Symbolic;
    n->description = std::move(description);
    n->deps = constraint.variables();
    n->constraint = std::move(constraint);
    node_ = std::move(n);
}

Invariant::Invariant(Constraint constraint) : Invariant(constraint.to_string(), constraint) {}

Invariant Invariant::always() { return Invariant("true", Constraint::always()); }
Invariant Invariant::never() { return Invariant("false", Constraint::never()); }

Invariant Invariant::all_of(const std::vector<Invariant>& xs) {
    if (xs.empty()) return always();
    if (xs.size() == 1) return xs.front();
    auto n = std::make_shared<Node>();
    n->kind = Kind::And;
    n->description = "(";
    for (std::size_t i = 0; i < xs.size(); ++i) n->description += (i ? " && " : "") + xs[i].description();
    n->description += ")";
    n->children = xs;
    n->size = tree_size(xs);
    return Invariant(std::shared_ptr<const Node>(std::move(n)));
}

Invariant Invariant::any_of(const std::vector<Invariant>& xs) {
    if (xs.empty()) return never();
    if (xs.size() == 1) return xs.front();
    auto n = std::make_shared<Node>();
    n->kind = Kind::Or;
    n->description = "(";
    for (std::size_t i = 0; i < xs.size(); ++i) n->description += (i ? " || " : "") + xs[i].description();
    n->description += ")";
    n->children = xs;
    n->size = tree_size(xs);
    return Invariant(std::shared_ptr<const Node>(std::move(n)));
}

Invariant::Kind Invariant::kind() const noexcept { return node_->kind; }
const std::string& Invariant::description() const noexcept { return node_->description; }
const std::vector<Invariant>& Invariant::children() const noexcept { return node_->children; }
std::size_t Invariant::size() const noexcept { return node_->size; }

bool Invariant::holds(const value::Value& v) const {
    const Node& n = *node_;
    switch (n.kind) {
        case Kind::Predicate: return n.predicate(v);
        case Kind::Symbolic: return n.constraint->eval(v);
        case Kind::And:
            for (const auto& c : n.children)
                if (!c.holds(v)) return false;
            return true;
        case Kind::Or:
            for (const auto& c : n.children)
                if (c.holds(v)) return true;
            return false;
        case Kind::Not: return !n.children[0].holds(v);
    }
    return false;
}

std::optional<std::string> Invariant::explain(const value::Value& v) const {
    if (holds(v)) return std::nullopt;
    const Node& n = *node_;
    switch (n.kind) {
        case Kind::Predicate:
            return n.explainer ? n.description + ": " + n.explainer(v) : n.description + " violated";
        case Kind::Symbolic: return n.description + ": " + n.constraint->explain(ValueEnv(v));
        case Kind::And:
            for (const auto& c : n.children)
                if (auto e = c.explain(v)) return e;
            return std::nullopt;
        case Kind::Or: {
            std::string s = "none of " + std::to_string(n.children.size()) + " alternatives hold: ";
            for (std::size_t i = 0; i < n.children.size(); ++i) s += (i ? "; " : "") + *n.children[i].explain(v);
            return s;
        }
        case Kind::Not: return "expected NOT " + n.children[0].description() + ", but it holds";
    }
    return std::nullopt;
}

void Invariant::collect_violations(const value::Value& v, std::vector<std::string>& out) const {
    const Node& n = *node_;
    if (n.kind == Kind::And) {
        for (const auto& c : n.children) c.collect_violations(v, out);
        return;
    }
    if (auto e = explain(v)) out.push_back(std::move(*e));
}

std::vector<std::string> Invariant::violations(const value::Value& v) const {
    std::vector<std::string> out;
    collect_violations(v, out);
    return out;
}

std::optional<std::set<std::string>> Invariant::dependencies() const {
    const Node& n = *node_;
    if (n.kind == Kind::Predicate || n.kind == Kind::Symbolic) return n.deps;
    std::set<std::string> out;
    for (const auto& c : n.children) {
        auto d = c.dependencies();
        if (!d) return std::nullopt;
        out.insert(d->begin(), d->end());
    }
    return out;
}

std::optional<Constraint> Invariant::constraint() const {
    const Node& n = *node_;
    switch (n.kind) {
        case Kind::Predicate: return std::nullopt;
        case Kind::Symbolic: return n.constraint;
        case Kind::Not: {
            auto c = n.children[0].constraint();
            if (!c) return std::nullopt;
            return !*c;
        }
        case Kind::And:
        case Kind::Or: {
            std::vector<Constraint> cs;
            for (const auto& ch : n.children) {
                auto c = ch.constraint();
                if (!c) return std::nullopt;
                cs.push_back(std::move(*c));
            }
            return n.kind == Kind::And ? Constraint::all_of(std::move(cs)) : Constraint::any_of(std::move(cs));
        }
    }
    return std::nullopt;
}

Tri Invariant::feasible(const Env& env) const {
    const Node& n = *node_;
    switch (n.kind) {
        case Kind::Predicate: return Tri::Unknown;
        case Kind::Symbolic: return n.constraint->feasible(env);
        case Kind::Not: {
            Tri t = n.children[0].feasible(env);
            return t == Tri::True ? Tri::False : (t == Tri::False ? Tri::True : Tri::Unknown);
        }
        case Kind::And: {
            Tri r = Tri::True;
            for (const auto& c : n.children) {
                Tri t = c.feasible(env);
                if (t == Tri::False) return Tri::False;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
        case Kind::Or: {
            Tri r = Tri::False;
            for (const auto& c : n.children) {
                Tri t = c.feasible(env);
                if (t == Tri::True) return Tri::True;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
    }
    return Tri::Unknown;
}

Invariant operator&&(const Invariant& a, const Invariant& b) {
    std::vector<Invariant> xs;
    for (const Invariant* x : {&a, &b}) {
        if (x->kind() == Invariant::Kind::And) xs.insert(xs.end(), x->children().begin(), x->children().end());
        else xs.push_back(*x);
    }
    return Invariant::all_of(xs);
}

Invariant operator||(const Invariant& a, const Invariant& b) {
    std::vector<Invariant> xs;
    for (const Invariant* x : {&a, &b}) {
        if (x->kind() == Invariant::Kind::Or) xs.insert(xs.end(), x->children().begin(), x->children().end());
        else xs.push_back(*x);
    }
    return Invariant::any_of(xs);
}

Invariant operator!(const Invariant& a) {
    auto n = std::make_shared<Invariant::Node>();
    n->kind = Invariant::Kind::Not;
    n->description = "!" + a.description();
    n->children = {a};
    n->size = 1 + a.size();
    return Invariant(std::shared_ptr<const Invariant::Node>(std::move(n)));
}

}  // namespace alg_exp::problem
