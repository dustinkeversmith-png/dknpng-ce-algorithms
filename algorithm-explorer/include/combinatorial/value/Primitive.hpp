#pragma once
// Primitive scalar kinds understood by the value runtime.
//
// Every leaf of a ValueType layout is one of these. `Opaque` marks a
// user-registered primitive that has a size but no numeric interpretation
// (e.g. a hash digest or a packed bitfield); numeric operators reject it.

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <type_traits>

namespace combinatorial::value {

enum class Prim : std::uint8_t { U8, I8, U16, I16, U32, I32, U64, I64, F32, F64, Bool, Opaque };

constexpr std::size_t prim_size(Prim p) noexcept {
    switch (p) {
        case Prim::U8: case Prim::I8: case Prim::Bool: return 1;
        case Prim::U16: case Prim::I16: return 2;
        case Prim::U32: case Prim::I32: case Prim::F32: return 4;
        case Prim::U64: case Prim::I64: case Prim::F64: return 8;
        case Prim::Opaque: return 0;
    }
    return 0;
}

constexpr std::string_view prim_name(Prim p) noexcept {
    switch (p) {
        case Prim::U8: return "u8";
        case Prim::I8: return "i8";
        case Prim::U16: return "u16";
        case Prim::I16: return "i16";
        case Prim::U32: return "u32";
        case Prim::I32: return "i32";
        case Prim::U64: return "u64";
        case Prim::I64: return "i64";
        case Prim::F32: return "f32";
        case Prim::F64: return "f64";
        case Prim::Bool: return "bool";
        case Prim::Opaque: return "opaque";
    }
    return "?";
}

constexpr bool prim_is_float(Prim p) noexcept { return p == Prim::F32 || p == Prim::F64; }
constexpr bool prim_is_signed(Prim p) noexcept {
    return p == Prim::I8 || p == Prim::I16 || p == Prim::I32 || p == Prim::I64 || prim_is_float(p);
}
constexpr bool prim_is_numeric(Prim p) noexcept { return p != Prim::Opaque; }

std::optional<Prim> prim_from_name(std::string_view name) noexcept;

template <class T> struct prim_of;
template <> struct prim_of<std::uint8_t>  { static constexpr Prim value = Prim::U8; };
template <> struct prim_of<std::int8_t>   { static constexpr Prim value = Prim::I8; };
template <> struct prim_of<std::uint16_t> { static constexpr Prim value = Prim::U16; };
template <> struct prim_of<std::int16_t>  { static constexpr Prim value = Prim::I16; };
template <> struct prim_of<std::uint32_t> { static constexpr Prim value = Prim::U32; };
template <> struct prim_of<std::int32_t>  { static constexpr Prim value = Prim::I32; };
template <> struct prim_of<std::uint64_t> { static constexpr Prim value = Prim::U64; };
template <> struct prim_of<std::int64_t>  { static constexpr Prim value = Prim::I64; };
template <> struct prim_of<float>         { static constexpr Prim value = Prim::F32; };
template <> struct prim_of<double>        { static constexpr Prim value = Prim::F64; };
template <> struct prim_of<bool>          { static constexpr Prim value = Prim::Bool; };

template <class T>
concept PrimitiveScalar = requires { prim_of<std::remove_cv_t<T>>::value; };

template <class T>
inline constexpr Prim prim_of_v = prim_of<std::remove_cv_t<T>>::value;

/// Calls `f(std::type_identity<T>{})` with the C++ type matching `p`.
template <class F>
decltype(auto) visit_prim(Prim p, F&& f) {
    switch (p) {
        case Prim::U8: return f(std::type_identity<std::uint8_t>{});
        case Prim::I8: return f(std::type_identity<std::int8_t>{});
        case Prim::U16: return f(std::type_identity<std::uint16_t>{});
        case Prim::I16: return f(std::type_identity<std::int16_t>{});
        case Prim::U32: return f(std::type_identity<std::uint32_t>{});
        case Prim::I32: return f(std::type_identity<std::int32_t>{});
        case Prim::U64: return f(std::type_identity<std::uint64_t>{});
        case Prim::I64: return f(std::type_identity<std::int64_t>{});
        case Prim::F32: return f(std::type_identity<float>{});
        case Prim::F64: return f(std::type_identity<double>{});
        case Prim::Bool: return f(std::type_identity<bool>{});
        case Prim::Opaque: break;
    }
    throw std::invalid_argument("visit_prim: opaque primitive has no numeric representation");
}

/// Unaligned, aliasing-safe load of a scalar from raw bytes. Bools are
/// normalised (any non-zero byte reads as true) so arbitrary byte patterns
/// never produce an invalid bool object.
template <PrimitiveScalar T>
inline T load_scalar(const std::byte* src) noexcept {
    if constexpr (std::is_same_v<std::remove_cv_t<T>, bool>) {
        return std::to_integer<std::uint8_t>(*src) != 0;
    } else {
        T out;
        std::memcpy(&out, src, sizeof(T));
        return out;
    }
}

template <PrimitiveScalar T>
inline void store_scalar(std::byte* dst, T v) noexcept {
    if constexpr (std::is_same_v<std::remove_cv_t<T>, bool>) {
        *dst = std::byte{static_cast<unsigned char>(v ? 1 : 0)};
    } else {
        std::memcpy(dst, &v, sizeof(T));
    }
}

/// Reads a numeric leaf of kind `p` and widens it to double.
double load_as_double(const std::byte* src, Prim p);
/// Narrows `v` into a numeric leaf of kind `p` (saturating for integers, NaN -> 0).
void store_from_double(std::byte* dst, Prim p, double v);

}  // namespace combinatorial::value
