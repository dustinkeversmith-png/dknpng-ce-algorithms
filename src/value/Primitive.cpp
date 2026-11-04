#include "alg_exp/value/Primitive.hpp"

#include <array>
#include <cmath>
#include <limits>

namespace alg_exp::value {

std::optional<Prim> prim_from_name(std::string_view name) noexcept {
    static constexpr std::array<Prim, 11> all{Prim::U8,  Prim::I8,  Prim::U16, Prim::I16, Prim::U32, Prim::I32,
                                              Prim::U64, Prim::I64, Prim::F32, Prim::F64, Prim::Bool};
    for (Prim p : all)
        if (prim_name(p) == name) return p;
    return std::nullopt;
}

double load_as_double(const std::byte* src, Prim p) {
    return visit_prim(p, [&]<class T>(std::type_identity<T>) { return static_cast<double>(load_scalar<T>(src)); });
}

namespace {
template <class T>
T saturate(double v) {
    if constexpr (std::is_same_v<T, bool>) {
        return v != 0.0 && !std::isnan(v);
    } else if constexpr (std::is_floating_point_v<T>) {
        return static_cast<T>(v);
    } else {
        if (std::isnan(v)) return T{0};
        const double limit = std::ldexp(1.0, std::numeric_limits<T>::digits);  // 2^digits, exactly representable
        if (v >= limit) return std::numeric_limits<T>::max();
        if constexpr (std::is_signed_v<T>) {
            if (v < -limit) return std::numeric_limits<T>::min();
        } else {
            if (v <= -1.0) return T{0};
        }
        return static_cast<T>(v);
    }
}
}  // namespace

void store_from_double(std::byte* dst, Prim p, double v) {
    visit_prim(p, [&]<class T>(std::type_identity<T>) { store_scalar<T>(dst, saturate<T>(v)); });
}

}  // namespace alg_exp::value
