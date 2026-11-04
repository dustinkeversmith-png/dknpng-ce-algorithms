#pragma once
// TypeRegistry: named types, primitive sizes, a type-expression parser and a
// dynamic operator table keyed by (operator name, canonical type).

#include "alg_exp/value/Value.hpp"
#include "alg_exp/value/ValueType.hpp"

#include <functional>
#include <map>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace alg_exp::value {

class TypeRegistry {
public:
    using Operator = std::function<Value(std::span<const Value>)>;
    /// Wildcard canonical type: operator applies to every numeric primitive.
    static constexpr std::string_view kAnyNumeric = "*";

    /// Registers u8..i64, f32, f64, bool, aliases (byte, int, long, float,
    /// double) and the builtin elementwise numeric operators.
    TypeRegistry();

    /// Opaque primitive with an explicit byte size.
    TypePtr define_primitive(std::string name, std::size_t size);
    /// Struct whose fields are type expressions resolved in this registry.
    TypePtr define_struct(std::string name, const std::vector<std::pair<std::string, std::string>>& fields);
    TypePtr define_struct(std::string name, std::vector<std::pair<std::string, TypePtr>> fields);
    TypePtr define_alias(std::string name, TypePtr type);

    bool contains(std::string_view name) const noexcept;
    /// Resolves a type expression: `name`, `name[4]`, `name[4][4]` or `name[4,4]`.
    TypePtr get(std::string_view type_expr) const;
    std::size_t measure(std::string_view type_expr) const { return get(type_expr)->byte_size(); }
    std::size_t primitive_size(std::string_view name) const;
    std::vector<std::string> names() const;

    void define_operator(std::string op, std::string type_t, Operator fn);
    bool has_operator(std::string_view op, std::string_view type_t) const noexcept;
    /// Dispatches on args[0].type()->t(); falls back to the numeric wildcard.
    Value apply(std::string_view op, std::span<const Value> args) const;
    Value apply(std::string_view op, std::initializer_list<Value> args) const {
        return apply(op, std::span<const Value>(args.begin(), args.size()));
    }

private:
    void install_builtin_operators();

    std::map<std::string, TypePtr, std::less<>> types_;
    std::map<std::pair<std::string, std::string>, Operator, std::less<>> operators_;
};

}  // namespace alg_exp::value
