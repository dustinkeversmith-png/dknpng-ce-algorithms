#pragma once
// Value: a typed window onto a shared flat byte buffer.
//
// A root Value owns a fresh zero-initialised buffer; every reference derived
// from it (elements, sub-arrays, members, paths) is a zero-copy view sharing
// the same buffer. Views have shallow constness: a `const Value&` still lets
// you write through it, exactly like a pointer to non-const data held in a
// const handle. Use clone() to obtain an independent deep copy.

#include "combinatorial/value/FieldIndex.hpp"
#include "combinatorial/value/ValueType.hpp"

#include <cstddef>
#include <initializer_list>
#include <memory>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace combinatorial::value {

class Value {
public:
    using Memory = std::vector<std::byte>;

    /// Root allocation: a fresh zero-filled buffer of `type->byte_size()` bytes.
    explicit Value(TypePtr type, std::string name = {});
    /// Zero-copy view of `type` at byte `offset` inside `memory`.
    Value(TypePtr type, std::shared_ptr<Memory> memory, std::size_t offset, std::string name = {});

    const TypePtr& type() const noexcept { return type_; }
    const std::string& name() const noexcept { return name_; }
    const std::shared_ptr<Memory>& memory() const noexcept { return memory_; }
    std::size_t offset() const noexcept { return offset_; }
    std::size_t size() const noexcept { return type_->byte_size(); }
    std::size_t element_size() const noexcept { return type_->element_size(); }
    const std::vector<std::size_t>& strides() const noexcept { return type_->strides(); }

    std::span<std::byte> bytes() const noexcept { return {memory_->data() + offset_, size()}; }
    std::byte* data() const noexcept { return memory_->data() + offset_; }

    // ---- strided indexing -------------------------------------------------
    /// Resolves a (possibly partial) multi-index. With k < rank indices the
    /// result describes the sub-array with the remaining rank-k dimensions.
    FieldIndex index_dimension(std::span<const std::size_t> indices) const;
    FieldIndex index_dimension(std::initializer_list<std::size_t> indices) const {
        return index_dimension(std::span<const std::size_t>(indices.begin(), indices.size()));
    }
    Value reference_element(std::span<const std::size_t> indices) const;
    Value at(std::initializer_list<std::size_t> indices) const {
        return reference_element(std::span<const std::size_t>(indices.begin(), indices.size()));
    }
    /// Index the leading dimension.
    Value operator[](std::size_t i) const;

    /// Flat (row-major) element view, i.e. element number `i` of element_count().
    Value flat(std::size_t i) const;

    // ---- members & paths ---------------------------------------------------
    Value reference_member(std::string_view member) const;
    /// Path grammar: segment ('.' segment)*, segment := ident? ('[' i (',' i)* ']')*
    /// e.g. "regs[3]", "grid[1,2].cell", "[0].x", "a.b[1][2].c".
    FieldIndex locate(std::string_view path) const;
    Value reference(std::string_view path) const;
    bool has_member(std::string_view member) const noexcept;

    // ---- raw memory ----------------------------------------------------------
    void insert(std::span<const std::byte> src, std::size_t at = 0) const;
    void read(std::span<std::byte> dst, std::size_t from = 0) const;
    void fill_zero() const noexcept;
    /// Copies bytes from a value with an identical layout.
    void copy_from(const Value& src) const;
    /// Deep copy into a new root buffer.
    Value clone() const;
    /// True if both values overlap in the same backing buffer.
    bool aliases(const Value& other) const noexcept;

    // ---- typed scalar access --------------------------------------------------
    /// Bitwise read of a scalar slot. Requires sizeof(T) == element size of a
    /// primitive scalar view. Reinterpreting between same-sized primitives is
    /// allowed (register casting, e.g. reading an f64 slot as u64 bits).
    template <PrimitiveScalar T>
    T get() const {
        require_scalar_slot(sizeof(T), "get");
        return load_scalar<T>(data());
    }
    template <PrimitiveScalar T>
    void set(T v) const {
        require_scalar_slot(sizeof(T), "set");
        store_scalar<T>(data(), v);
    }
    /// Numeric conversion from the stored primitive to T (value cast, not bitwise).
    template <PrimitiveScalar T>
    T as() const {
        require_numeric_scalar("as");
        return static_cast<T>(load_as_double(data(), type_->prim()));
    }
    /// Numeric conversion from v into the stored primitive (saturating).
    void assign(double v) const {
        require_numeric_scalar("assign");
        store_from_double(data(), type_->prim(), v);
    }

    /// Element `i` (flat) of a primitive array, widened to double.
    double scalar(std::size_t i) const;
    void set_scalar(std::size_t i, double v) const;

    /// Copies a primitive array out as T (numeric conversion).
    template <PrimitiveScalar T>
    std::vector<T> to_vector() const {
        std::vector<T> out(type_->element_count());
        for (std::size_t i = 0; i < out.size(); ++i) out[i] = static_cast<T>(scalar(i));
        return out;
    }
    template <PrimitiveScalar T>
    void from_vector(std::span<const T> src) const {
        if (src.size() != type_->element_count()) throw std::invalid_argument("from_vector: element count mismatch");
        for (std::size_t i = 0; i < src.size(); ++i) set_scalar(i, static_cast<double>(src[i]));
    }

    // ---- comparison / printing ------------------------------------------------
    /// Structural type equality and identical bytes.
    bool operator==(const Value& other) const noexcept;
    std::size_t hash() const noexcept;
    std::string to_string() const;

private:
    void require_scalar_slot(std::size_t bytes, const char* op) const;
    void require_numeric_scalar(const char* op) const;

    TypePtr type_;
    std::shared_ptr<Memory> memory_;
    std::size_t offset_ = 0;
    std::string name_;
};

}  // namespace combinatorial::value
