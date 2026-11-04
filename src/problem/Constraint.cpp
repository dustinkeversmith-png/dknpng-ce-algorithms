#include "combinatorial/problem/Constraint.hpp"

#include "IntervalMath.hpp"

#include <algorithm>
#include <cmath>
#include <sstream>
#include <stdexcept>

namespace combinatorial::problem {

// ------------------------------------------------------------------ Env ---

double ValueEnv::value(std::string_view path) const {
    value::FieldIndex fi = v_.locate(path);
    const value::ValueType& t = *fi.value_type;
    if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
        throw std::invalid_argument("constraint variable '" + std::string(path) + "' is not a numeric scalar leaf (" +
                                    t.signature() + ")");
    return value::load_as_double(v_.memory()->data() + fi.offset, t.prim());
}

double OffsetEnv::value(std::string_view path) const {
    auto it = cache_.find(path);
    if (it == cache_.end()) {
        value::FieldIndex fi = value::locate(type_, path);
        const value::ValueType& t = *fi.value_type;
        if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
            throw std::invalid_argument("constraint variable '" + std::string(path) + "' is not a numeric scalar leaf (" +
                                        t.signature() + ")");
        it = cache_.emplace(std::string(path), Slot{fi.offset, t.prim()}).first;
    }
    if (!base_) throw std::logic_error("OffsetEnv: no buffer bound");
    return value::load_as_double(base_ + it->second.offset, it->second.prim);
}

// ----------------------------------------------------------------- Expr ---

struct Expr::Node {
    Op op;
    double value = 0;
    std::string path;
    std::vector<Expr> args;
};

namespace {

using detail::compare_intervals;
using detail::compare_values;

std::string fmt(double v) {
    std::ostringstream os;
    os << v;
    return os.str();
}

}  // namespace

Expr::Expr(double constant) : node_(std::make_shared<Node>(Node{Op::Const, constant, {}, {}})) {}

Expr Expr::var(std::string path) { return Expr(std::make_shared<Node>(Node{Op::Var, 0, std::move(path), {}})); }

Expr::Op Expr::op() const noexcept { return node_->op; }
double Expr::constant_value() const noexcept { return node_->value; }
const std::string& Expr::path() const noexcept { return node_->path; }
const std::vector<Expr>& Expr::args() const noexcept { return node_->args; }

Expr operator+(const Expr& a, const Expr& b) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Add, 0, {}, {a, b}})); }
Expr operator-(const Expr& a, const Expr& b) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Sub, 0, {}, {a, b}})); }
Expr operator*(const Expr& a, const Expr& b) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Mul, 0, {}, {a, b}})); }
Expr operator-(const Expr& a) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Neg, 0, {}, {a}})); }
Expr abs(const Expr& a) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Abs, 0, {}, {a}})); }
Expr min(const Expr& a, const Expr& b) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Min, 0, {}, {a, b}})); }
Expr max(const Expr& a, const Expr& b) { return Expr(std::make_shared<Expr::Node>(Expr::Node{Expr::Op::Max, 0, {}, {a, b}})); }

double Expr::eval(const Env& env) const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::Const: return n.value;
        case Op::Var: return env.value(n.path);
        case Op::Add: return n.args[0].eval(env) + n.args[1].eval(env);
        case Op::Sub: return n.args[0].eval(env) - n.args[1].eval(env);
        case Op::Mul: return n.args[0].eval(env) * n.args[1].eval(env);
        case Op::Neg: return -n.args[0].eval(env);
        case Op::Abs: return std::fabs(n.args[0].eval(env));
        case Op::Min: return std::min(n.args[0].eval(env), n.args[1].eval(env));
        case Op::Max: return std::max(n.args[0].eval(env), n.args[1].eval(env));
    }
    return 0;
}

Interval Expr::bounds(const Env& env) const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::Const: return Interval::point(n.value);
        case Op::Var: return env.range(n.path);
        case Op::Add: return detail::iv_add(n.args[0].bounds(env), n.args[1].bounds(env));
        case Op::Sub: return detail::iv_add(n.args[0].bounds(env), detail::iv_neg(n.args[1].bounds(env)));
        case Op::Mul: return detail::iv_mul(n.args[0].bounds(env), n.args[1].bounds(env));
        case Op::Neg: return detail::iv_neg(n.args[0].bounds(env));
        case Op::Abs: return detail::iv_abs(n.args[0].bounds(env));
        case Op::Min: return detail::iv_min(n.args[0].bounds(env), n.args[1].bounds(env));
        case Op::Max: return detail::iv_max(n.args[0].bounds(env), n.args[1].bounds(env));
    }
    return Interval::everything();
}

void Expr::collect_variables(std::set<std::string>& out) const {
    if (node_->op == Op::Var) out.insert(node_->path);
    for (const Expr& a : node_->args) a.collect_variables(out);
}

std::string Expr::to_string() const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::Const: return fmt(n.value);
        case Op::Var: return n.path.empty() ? "$" : n.path;
        case Op::Add: return "(" + n.args[0].to_string() + " + " + n.args[1].to_string() + ")";
        case Op::Sub: return "(" + n.args[0].to_string() + " - " + n.args[1].to_string() + ")";
        case Op::Mul: return "(" + n.args[0].to_string() + " * " + n.args[1].to_string() + ")";
        case Op::Neg: return "-" + n.args[0].to_string();
        case Op::Abs: return "|" + n.args[0].to_string() + "|";
        case Op::Min: return "min(" + n.args[0].to_string() + ", " + n.args[1].to_string() + ")";
        case Op::Max: return "max(" + n.args[0].to_string() + ", " + n.args[1].to_string() + ")";
    }
    return "?";
}

// ----------------------------------------------------------- Constraint ---

struct Constraint::Node {
    Op op;
    std::vector<Expr> terms;          // comparisons: {lhs, rhs}
    std::vector<Constraint> args;     // And / Or / Not
};

namespace {
const char* cmp_symbol(Constraint::Op op) {
    switch (op) {
        case Constraint::Op::Eq: return "==";
        case Constraint::Op::Ne: return "!=";
        case Constraint::Op::Lt: return "<";
        case Constraint::Op::Le: return "<=";
        case Constraint::Op::Gt: return ">";
        case Constraint::Op::Ge: return ">=";
        default: return "?";
    }
}

}  // namespace

Constraint Constraint::always() { return Constraint(std::make_shared<Node>(Node{Op::True, {}, {}})); }
Constraint Constraint::never() { return Constraint(std::make_shared<Node>(Node{Op::False, {}, {}})); }

Constraint Constraint::compare(Op op, Expr lhs, Expr rhs) {
    if (op != Op::Eq && op != Op::Ne && op != Op::Lt && op != Op::Le && op != Op::Gt && op != Op::Ge)
        throw std::invalid_argument("Constraint::compare: not a comparison operator");
    return Constraint(std::make_shared<Node>(Node{op, {std::move(lhs), std::move(rhs)}, {}}));
}

Constraint Constraint::all_of(std::vector<Constraint> cs) {
    if (cs.empty()) return always();
    if (cs.size() == 1) return cs.front();
    return Constraint(std::make_shared<Node>(Node{Op::And, {}, std::move(cs)}));
}

Constraint Constraint::any_of(std::vector<Constraint> cs) {
    if (cs.empty()) return never();
    if (cs.size() == 1) return cs.front();
    return Constraint(std::make_shared<Node>(Node{Op::Or, {}, std::move(cs)}));
}

Constraint Constraint::all_different(const std::vector<Expr>& terms) {
    std::vector<Constraint> cs;
    for (std::size_t i = 0; i < terms.size(); ++i)
        for (std::size_t j = i + 1; j < terms.size(); ++j) cs.push_back(terms[i] != terms[j]);
    return all_of(std::move(cs));
}

Constraint Constraint::between(const Expr& e, double lo, double hi) { return (e >= Expr(lo)) && (e <= Expr(hi)); }

Constraint::Op Constraint::op() const noexcept { return node_->op; }
bool Constraint::is_comparison() const noexcept { return !node_->terms.empty(); }
const Expr& Constraint::lhs() const {
    if (!is_comparison()) throw std::logic_error("Constraint::lhs on non-comparison");
    return node_->terms[0];
}
const Expr& Constraint::rhs() const {
    if (!is_comparison()) throw std::logic_error("Constraint::rhs on non-comparison");
    return node_->terms[1];
}
const std::vector<Constraint>& Constraint::args() const noexcept { return node_->args; }

bool Constraint::eval(const Env& env) const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::True: return true;
        case Op::False: return false;
        case Op::And:
            for (const auto& c : n.args)
                if (!c.eval(env)) return false;
            return true;
        case Op::Or:
            for (const auto& c : n.args)
                if (c.eval(env)) return true;
            return false;
        case Op::Not: return !n.args[0].eval(env);
        default: return compare_values(n.op, n.terms[0].eval(env), n.terms[1].eval(env));
    }
}

Tri Constraint::feasible(const Env& env) const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::True: return Tri::True;
        case Op::False: return Tri::False;
        case Op::And: {
            Tri r = Tri::True;
            for (const auto& c : n.args) {
                Tri t = c.feasible(env);
                if (t == Tri::False) return Tri::False;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
        case Op::Or: {
            Tri r = Tri::False;
            for (const auto& c : n.args) {
                Tri t = c.feasible(env);
                if (t == Tri::True) return Tri::True;
                if (t == Tri::Unknown) r = Tri::Unknown;
            }
            return r;
        }
        case Op::Not: {
            Tri t = n.args[0].feasible(env);
            return t == Tri::True ? Tri::False : (t == Tri::False ? Tri::True : Tri::Unknown);
        }
        default: return compare_intervals(n.op, n.terms[0].bounds(env), n.terms[1].bounds(env));
    }
}

void Constraint::collect_variables(std::set<std::string>& out) const {
    for (const auto& t : node_->terms) t.collect_variables(out);
    for (const auto& c : node_->args) c.collect_variables(out);
}

std::set<std::string> Constraint::variables() const {
    std::set<std::string> out;
    collect_variables(out);
    return out;
}

std::string Constraint::to_string() const {
    const Node& n = *node_;
    switch (n.op) {
        case Op::True: return "true";
        case Op::False: return "false";
        case Op::Not: return "!(" + n.args[0].to_string() + ")";
        case Op::And:
        case Op::Or: {
            std::string s = "(";
            for (std::size_t i = 0; i < n.args.size(); ++i) {
                if (i) s += n.op == Op::And ? " && " : " || ";
                s += n.args[i].to_string();
            }
            return s + ")";
        }
        default: return n.terms[0].to_string() + " " + cmp_symbol(n.op) + " " + n.terms[1].to_string();
    }
}

std::string Constraint::explain(const Env& env) const {
    if (eval(env)) return {};
    const Node& n = *node_;
    switch (n.op) {
        case Op::False: return "false";
        case Op::And:
            for (const auto& c : n.args)
                if (!c.eval(env)) return c.explain(env);
            return {};
        case Op::Or: {
            std::string s = "none of the alternatives hold: ";
            for (std::size_t i = 0; i < n.args.size(); ++i) s += (i ? "; " : "") + n.args[i].explain(env);
            return s;
        }
        case Op::Not: return "expected !(" + n.args[0].to_string() + ") but it holds";
        default: {
            const double a = n.terms[0].eval(env), b = n.terms[1].eval(env);
            return to_string() + " violated: " + fmt(a) + " " + cmp_symbol(n.op) + " " + fmt(b) + " is false";
        }
    }
}

Constraint operator&&(const Constraint& a, const Constraint& b) {
    std::vector<Constraint> args;
    for (const Constraint* c : {&a, &b}) {
        if (c->op() == Constraint::Op::And) args.insert(args.end(), c->args().begin(), c->args().end());
        else args.push_back(*c);
    }
    return Constraint::all_of(std::move(args));
}

Constraint operator||(const Constraint& a, const Constraint& b) {
    std::vector<Constraint> args;
    for (const Constraint* c : {&a, &b}) {
        if (c->op() == Constraint::Op::Or) args.insert(args.end(), c->args().begin(), c->args().end());
        else args.push_back(*c);
    }
    return Constraint::any_of(std::move(args));
}

Constraint operator!(const Constraint& a) {
    return Constraint(std::make_shared<Constraint::Node>(Constraint::Node{Constraint::Op::Not, {}, {a}}));
}

Constraint operator==(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Eq, a, b); }
Constraint operator!=(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Ne, a, b); }
Constraint operator<(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Lt, a, b); }
Constraint operator<=(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Le, a, b); }
Constraint operator>(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Gt, a, b); }
Constraint operator>=(const Expr& a, const Expr& b) { return Constraint::compare(Constraint::Op::Ge, a, b); }

}  // namespace combinatorial::problem
