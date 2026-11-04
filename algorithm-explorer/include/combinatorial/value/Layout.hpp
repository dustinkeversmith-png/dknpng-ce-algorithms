#pragma once
// Leaf-level views of a layout: every primitive scalar slot of a type with
// its byte offset. Used by metrics, synthesizers, fuzzers and printers.

#include "combinatorial/value/ValueType.hpp"

#include <cstddef>
#include <functional>
#include <string>
#include <vector>

namespace combinatorial::value {

struct Leaf {
    std::string path;     ///< reference path, e.g. "regs[3]" or "pts[1].x"
    std::size_t offset;   ///< byte offset relative to the start of the value
    Prim prim;
    std::size_t size;     ///< byte size of the leaf
};

/// All primitive scalar slots of `type` in memory order.
std::vector<Leaf> flatten(const ValueType& type);

/// Visits every primitive scalar slot without materialising path strings.
void for_each_leaf(const ValueType& type, const std::function<void(std::size_t offset, Prim prim, std::size_t size)>& fn);

/// Number of primitive scalar slots.
std::size_t leaf_count(const ValueType& type);

/// True if every leaf of the type is numeric (no opaque primitives).
bool is_numeric_layout(const ValueType& type);

}  // namespace combinatorial::value
