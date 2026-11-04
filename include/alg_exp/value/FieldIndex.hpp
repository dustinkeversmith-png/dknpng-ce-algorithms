#pragma once
#include "alg_exp/value/ValueType.hpp"

#include <cstddef>

namespace alg_exp::value {

/// A resolved location inside a backing buffer: absolute byte offset, byte
/// length and the type describing those bytes.
struct FieldIndex {
    std::size_t offset = 0;
    std::size_t length = 0;
    TypePtr value_type;
};

}  // namespace alg_exp::value
