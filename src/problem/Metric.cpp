#include "alg_exp/problem/Metric.hpp"

#include "alg_exp/value/Layout.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace alg_exp::problem {

std::string_view norm_name(Norm n) noexcept {
    switch (n) {
        case Norm::L1: return "L1";
        case Norm::L2: return "L2";
        case Norm::LInf: return "Linf";
        case Norm::Hamming: return "Hamming";
    }
    return "?";
}

double norm_accumulate(Norm norm, double acc, double diff) noexcept {
    const double d = std::fabs(diff);
    switch (norm) {
        case Norm::L1: return acc + d;
        case Norm::L2: return acc + d * d;
        case Norm::LInf: return std::max(acc, d);
        case Norm::Hamming: return acc + (d != 0 ? 1.0 : 0.0);
    }
    return acc;
}

double norm_finish(Norm norm, double acc) noexcept { return norm == Norm::L2 ? std::sqrt(acc) : acc; }

double distance(const value::Value& a, const value::Value& b, Norm norm) {
    if (!a.type()->layout_equals(*b.type()))
        throw std::invalid_argument("distance: layout mismatch " + a.type()->signature() + " vs " +
                                    b.type()->signature());
    const std::byte* pa = a.data();
    const std::byte* pb = b.data();
    double acc = 0;
    value::for_each_leaf(*a.type(), [&](std::size_t off, value::Prim prim, std::size_t size) {
        double diff;
        if (value::prim_is_numeric(prim)) {
            const double x = value::load_as_double(pa + off, prim), y = value::load_as_double(pb + off, prim);
            diff = (x == y) ? 0.0 : x - y;  // equal infinities contribute 0
            if (std::isnan(diff)) diff = std::numeric_limits<double>::infinity();
        } else {
            diff = std::memcmp(pa + off, pb + off, size) == 0 ? 0.0 : 1.0;
        }
        acc = norm_accumulate(norm, acc, diff);
    });
    return norm_finish(norm, acc);
}

}  // namespace alg_exp::problem
