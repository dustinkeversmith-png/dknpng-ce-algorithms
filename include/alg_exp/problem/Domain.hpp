#pragma once
// Finite candidate sets for scalar leaves.

#include "alg_exp/value/Primitive.hpp"

#include <initializer_list>
#include <vector>

namespace alg_exp::problem {

struct Domain {
    std::vector<double> values;  ///< sorted, unique

    Domain() = default;
    explicit Domain(std::vector<double> vs);
    Domain(std::initializer_list<double> vs) : Domain(std::vector<double>(vs)) {}

    /// lo, lo+step, ..., <= hi
    static Domain range(double lo, double hi, double step = 1.0);
    /// Every representable value of a small primitive (bool, u8, i8).
    static Domain of_prim(value::Prim p);

    std::size_t size() const noexcept { return values.size(); }
    bool empty() const noexcept { return values.empty(); }
    double lo() const;
    double hi() const;
    bool contains(double v) const noexcept;
    /// Index of the value (exact match) or size() if absent.
    std::size_t index_of(double v) const noexcept;
};

}  // namespace alg_exp::problem
