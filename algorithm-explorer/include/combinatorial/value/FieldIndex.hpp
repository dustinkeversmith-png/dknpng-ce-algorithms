#pragma once
#include "combinatorial/value/ValueType.hpp"

#include <cstddef>

namespace combinatorial::value {

/// A resolved location inside a backing buffer: absolute byte offset, byte
/// length and the type describing those bytes.
struct FieldIndex {
    std::size_t offset = 0;
    std::size_t length = 0;
    TypePtr value_type;
};

}  // namespace combinatorial::value
