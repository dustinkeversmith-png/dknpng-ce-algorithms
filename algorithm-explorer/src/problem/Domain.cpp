#include "combinatorial/problem/Domain.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>

namespace combinatorial::problem {

Domain::Domain(std::vector<double> vs) : values(std::move(vs)) {
    for (double v : values)
        if (std::isnan(v)) throw std::invalid_argument("Domain: NaN value");
    std::sort(values.begin(), values.end());
    values.erase(std::unique(values.begin(), values.end()), values.end());
}

Domain Domain::range(double lo, double hi, double step) {
    if (!(step > 0) || !std::isfinite(lo) || !std::isfinite(hi)) throw std::invalid_argument("Domain::range: bad bounds");
    const double n = std::floor((hi - lo) / step + 1e-9) + 1;
    if (n > 1e8) throw std::length_error("Domain::range: too many values");
    std::vector<double> vs;
    if (n > 0) vs.reserve(static_cast<std::size_t>(n));
    for (std::size_t i = 0; n > 0 && i < static_cast<std::size_t>(n); ++i) vs.push_back(lo + static_cast<double>(i) * step);
    return Domain(std::move(vs));
}

Domain Domain::of_prim(value::Prim p) {
    switch (p) {
        case value::Prim::Bool: return Domain{0, 1};
        case value::Prim::U8: return range(0, 255);
        case value::Prim::I8: return range(-128, 127);
        default:
            throw std::invalid_argument("Domain::of_prim: primitive " + std::string(value::prim_name(p)) +
                                        " is too large to enumerate; give an explicit domain");
    }
}

double Domain::lo() const {
    if (values.empty()) throw std::logic_error("Domain::lo on empty domain");
    return values.front();
}
double Domain::hi() const {
    if (values.empty()) throw std::logic_error("Domain::hi on empty domain");
    return values.back();
}
bool Domain::contains(double v) const noexcept { return std::binary_search(values.begin(), values.end(), v); }
std::size_t Domain::index_of(double v) const noexcept {
    auto it = std::lower_bound(values.begin(), values.end(), v);
    return (it != values.end() && *it == v) ? static_cast<std::size_t>(it - values.begin()) : values.size();
}

}  // namespace combinatorial::problem
