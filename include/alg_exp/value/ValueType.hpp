#pragma once
// ValueType: an immutable description of a flat byte layout.
//
// A type is either a primitive leaf or a struct of named fields, optionally
// repeated over a row-major tensor `shape`. Layouts are packed: field offsets
// are the running sum of preceding field sizes, so the byte image of a value
// is fully determined by its type and independent of host struct alignment.
//
// Types are shared and immutable (`TypePtr = shared_ptr<const ValueType>`).
// For a shaped type, the types obtained by stripping leading dimensions are
// precomputed so that element/sub-array views never allocate a new type.

#include "alg_exp/value/Primitive.hpp"

#include <cstddef>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace alg_exp::value {

class ValueType;
using TypePtr = std::shared_ptr<const ValueType>;

struct Field {
    std::string name;
    TypePtr type;
    std::size_t offset;  ///< byte offset inside one element of the parent struct
};

class ValueType : public std::enable_shared_from_this<ValueType> {
    struct PassKey {};

public:
    enum class Kind : std::uint8_t { Primitive, Struct };

    /// Primitive leaf. `size` defaults to the intrinsic size of `prim`; it must
    /// be given explicitly for `Prim::Opaque`.
    static TypePtr primitive(std::string name, Prim prim, std::vector<std::size_t> shape = {},
                             std::size_t size = 0);
    /// Struct of named fields laid out in declaration order.
    static TypePtr structure(std::string name, std::vector<std::pair<std::string, TypePtr>> fields,
                             std::vector<std::size_t> shape = {});

    /// Same element layout, different tensor shape.
    TypePtr reshaped(std::vector<std::size_t> shape) const;
    /// Type of a single element (shape stripped). Never allocates.
    TypePtr element() const { return suffixes_.empty() ? shared_from_this() : suffixes_.back(); }
    /// Type with the first `dropped` dimensions removed (0 < dropped <= rank). Never allocates.
    TypePtr suffix(std::size_t dropped) const;

    const std::string& name() const noexcept { return name_; }
    /// Canonical type name: primitive name ("f64") or the struct's name.
    const std::string& t() const noexcept { return t_; }
    Kind kind() const noexcept { return kind_; }
    bool is_primitive() const noexcept { return kind_ == Kind::Primitive; }
    bool is_struct() const noexcept { return kind_ == Kind::Struct; }
    bool is_scalar() const noexcept { return shape_.empty(); }
    Prim prim() const noexcept { return prim_; }

    const std::vector<std::size_t>& shape() const noexcept { return shape_; }
    std::size_t rank() const noexcept { return shape_.size(); }
    /// Byte stride of each dimension (row-major).
    const std::vector<std::size_t>& strides() const noexcept { return strides_; }
    std::size_t element_count() const noexcept { return element_count_; }
    std::size_t element_size() const noexcept { return element_size_; }
    std::size_t byte_size() const noexcept { return byte_size_; }

    const std::vector<Field>& fields() const noexcept { return fields_; }
    const Field* field(std::string_view name) const noexcept;

    /// Structural equality: kind, canonical name, primitive, shape and fields
    /// (recursively). The display `name` is ignored.
    bool equals(const ValueType& other) const noexcept;
    /// Layout compatibility: identical byte image interpretation, ignoring names.
    bool layout_equals(const ValueType& other) const noexcept;

    /// Human readable signature, e.g. `Point{x:f64,y:f64}[3,4]`.
    std::string signature() const;

    ValueType(PassKey, std::string name, std::string t, Kind kind, Prim prim, std::size_t element_size,
              std::vector<Field> fields, std::vector<std::size_t> shape);

private:
    static TypePtr build(std::string name, std::string t, Kind kind, Prim prim, std::size_t element_size,
                         std::vector<Field> fields, std::vector<std::size_t> shape);

    std::string name_;
    std::string t_;
    Kind kind_;
    Prim prim_;
    std::vector<std::size_t> shape_;
    std::vector<std::size_t> strides_;
    std::size_t element_count_ = 1;
    std::size_t element_size_ = 0;
    std::size_t byte_size_ = 0;
    std::vector<Field> fields_;
    std::map<std::string, std::size_t, std::less<>> field_index_;
    // suffixes_[k-1] is this type with the first k dimensions dropped.
    std::vector<TypePtr> suffixes_;
    friend class TypeRegistry;
};

/// Overflow-checked size arithmetic (throws std::length_error).
std::size_t checked_mul(std::size_t a, std::size_t b);
std::size_t checked_add(std::size_t a, std::size_t b);

}  // namespace alg_exp::value
