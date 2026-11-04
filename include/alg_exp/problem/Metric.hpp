#pragma once
// Metrics over shaped buffers. Distances are taken leaf by leaf over the
// numeric slots of two values with identical layouts; opaque leaves count as
// a unit difference when their bytes differ.

#include "alg_exp/value/Value.hpp"

#include <cstdint>
#include <string_view>

namespace alg_exp::problem {

enum class Norm : std::uint8_t { L1, L2, LInf, Hamming };

std::string_view norm_name(Norm n) noexcept;
double distance(const value::Value& a, const value::Value& b, Norm norm = Norm::L2);
/// Per-leaf contribution folded into a running cost (used by branch & bound).
double norm_accumulate(Norm norm, double acc, double diff) noexcept;
/// Final transform of an accumulated cost (sqrt for L2).
double norm_finish(Norm norm, double acc) noexcept;

}  // namespace alg_exp::problem
