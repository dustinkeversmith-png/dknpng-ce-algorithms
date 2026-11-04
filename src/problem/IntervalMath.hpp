#pragma once
// Interval arithmetic and comparison semantics shared by the Constraint AST
// and the Synthesizer's compiled evaluator, so both always agree.

#include "alg_exp/problem/Constraint.hpp"

#include <algorithm>
#include <cmath>

namespace alg_exp::problem::detail {

inline Interval iv_add(Interval a, Interval b) { return {a.lo + b.lo, a.hi + b.hi}; }
inline Interval iv_neg(Interval a) { return {-a.hi, -a.lo}; }

inline double mul_bound(double x, double y) {
    // 0 * inf contributes 0 to a product bound (the zero endpoint is attained).
    if (x == 0 || y == 0) return 0;
    return x * y;
}

inline Interval iv_mul(Interval a, Interval b) {
    const double c[4] = {mul_bound(a.lo, b.lo), mul_bound(a.lo, b.hi), mul_bound(a.hi, b.lo), mul_bound(a.hi, b.hi)};
    return {*std::min_element(c, c + 4), *std::max_element(c, c + 4)};
}

inline Interval iv_abs(Interval a) {
    if (a.lo >= 0) return a;
    if (a.hi <= 0) return iv_neg(a);
    return {0, std::max(-a.lo, a.hi)};
}

inline Interval iv_min(Interval a, Interval b) { return {std::min(a.lo, b.lo), std::min(a.hi, b.hi)}; }
inline Interval iv_max(Interval a, Interval b) { return {std::max(a.lo, b.lo), std::max(a.hi, b.hi)}; }

inline bool compare_values(Constraint::Op op, double a, double b) {
    switch (op) {
        case Constraint::Op::Eq: return a == b;
        case Constraint::Op::Ne: return a != b;
        case Constraint::Op::Lt: return a < b;
        case Constraint::Op::Le: return a <= b;
        case Constraint::Op::Gt: return a > b;
        case Constraint::Op::Ge: return a >= b;
        default: return false;
    }
}

inline Tri compare_intervals(Constraint::Op op, Interval a, Interval b) {
    if (std::isnan(a.lo) || std::isnan(a.hi) || std::isnan(b.lo) || std::isnan(b.hi)) return Tri::Unknown;
    switch (op) {
        case Constraint::Op::Lt:
            if (a.hi < b.lo) return Tri::True;
            if (a.lo >= b.hi) return Tri::False;
            return Tri::Unknown;
        case Constraint::Op::Le:
            if (a.hi <= b.lo) return Tri::True;
            if (a.lo > b.hi) return Tri::False;
            return Tri::Unknown;
        case Constraint::Op::Gt: return compare_intervals(Constraint::Op::Lt, b, a);
        case Constraint::Op::Ge: return compare_intervals(Constraint::Op::Le, b, a);
        case Constraint::Op::Eq:
            if (a.hi < b.lo || b.hi < a.lo) return Tri::False;
            if (a.is_point() && b.is_point()) return Tri::True;  // overlapping points are equal
            return Tri::Unknown;
        case Constraint::Op::Ne: {
            const Tri eq = compare_intervals(Constraint::Op::Eq, a, b);
            return eq == Tri::True ? Tri::False : (eq == Tri::False ? Tri::True : Tri::Unknown);
        }
        default: return Tri::Unknown;
    }
}

inline Tri tri_not(Tri t) { return t == Tri::True ? Tri::False : (t == Tri::False ? Tri::True : Tri::Unknown); }

}  // namespace alg_exp::problem::detail
