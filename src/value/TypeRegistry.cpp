#include "alg_exp/value/TypeRegistry.hpp"

#include <charconv>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <type_traits>

namespace alg_exp::value {

namespace {

std::string_view trim(std::string_view s) {
    while (!s.empty() && s.front() == ' ') s.remove_prefix(1);
    while (!s.empty() && s.back() == ' ') s.remove_suffix(1);
    return s;
}

const Value& require_numeric(const Value& v, std::string_view op) {
    const ValueType& t = *v.type();
    if (!t.is_primitive() || !prim_is_numeric(t.prim()))
        throw std::invalid_argument("operator '" + std::string(op) + "': operand " + t.signature() +
                                    " is not a numeric primitive");
    return v;
}

/// Shape of the broadcast result of a and b (equal shapes, or one side holds a single element).
const std::vector<std::size_t>& broadcast_shape(const Value& a, const Value& b, std::string_view op) {
    if (a.type()->shape() == b.type()->shape()) return a.type()->shape();
    if (b.type()->element_count() == 1) return a.type()->shape();
    if (a.type()->element_count() == 1) return b.type()->shape();
    throw std::invalid_argument("operator '" + std::string(op) + "': shape mismatch " + a.type()->signature() +
                                " vs " + b.type()->signature());
}

enum class BinOp { Add, Sub, Mul, Div, Min, Max };
enum class CmpOp { Eq, Ne, Lt, Le, Gt, Ge };

template <class T>
T native_binop(BinOp op, T x, T y) {
    if constexpr (std::is_integral_v<T> && !std::is_same_v<T, bool>) {
        using U = std::make_unsigned_t<T>;
        switch (op) {
            case BinOp::Add: return static_cast<T>(static_cast<U>(static_cast<U>(x) + static_cast<U>(y)));
            case BinOp::Sub: return static_cast<T>(static_cast<U>(static_cast<U>(x) - static_cast<U>(y)));
            case BinOp::Mul: return static_cast<T>(static_cast<U>(static_cast<U>(x) * static_cast<U>(y)));
            case BinOp::Div:
                if (y == 0) throw std::domain_error("operator 'div': integer division by zero");
                if constexpr (std::is_signed_v<T>) {
                    if (x == std::numeric_limits<T>::min() && y == T(-1))
                        throw std::domain_error("operator 'div': signed overflow");
                }
                return static_cast<T>(x / y);
            case BinOp::Min: return x < y ? x : y;
            case BinOp::Max: return x < y ? y : x;
        }
    } else {
        switch (op) {
            case BinOp::Add: return static_cast<T>(x + y);
            case BinOp::Sub: return static_cast<T>(x - y);
            case BinOp::Mul: return static_cast<T>(x * y);
            case BinOp::Div: return static_cast<T>(x / y);
            case BinOp::Min: return x < y ? x : y;
            case BinOp::Max: return x < y ? y : x;
        }
    }
    return x;
}

Value binary(std::span<const Value> args, BinOp op, std::string_view name) {
    if (args.size() != 2) throw std::invalid_argument("operator '" + std::string(name) + "' expects 2 operands");
    const Value& a = require_numeric(args[0], name);
    const Value& b = require_numeric(args[1], name);
    const auto& shape = broadcast_shape(a, b, name);
    const Prim prim = a.type()->prim();
    Value out(ValueType::primitive(std::string(prim_name(prim)), prim, shape), std::string(name));
    const std::size_t n = out.type()->element_count();
    const std::size_t na = a.type()->element_count(), nb = b.type()->element_count();
    if (prim == b.type()->prim() && prim != Prim::Bool) {
        visit_prim(prim, [&]<class T>(std::type_identity<T>) {
            const std::size_t sz = sizeof(T);
            for (std::size_t i = 0; i < n; ++i) {
                T x = load_scalar<T>(a.data() + (na == 1 ? 0 : i) * sz);
                T y = load_scalar<T>(b.data() + (nb == 1 ? 0 : i) * sz);
                store_scalar<T>(out.data() + i * sz, native_binop<T>(op, x, y));
            }
        });
    } else {
        for (std::size_t i = 0; i < n; ++i) {
            double x = a.scalar(na == 1 ? 0 : i), y = b.scalar(nb == 1 ? 0 : i);
            out.set_scalar(i, native_binop<double>(op, x, y));
        }
    }
    return out;
}

Value compare(std::span<const Value> args, CmpOp op, std::string_view name) {
    if (args.size() != 2) throw std::invalid_argument("operator '" + std::string(name) + "' expects 2 operands");
    const Value& a = require_numeric(args[0], name);
    const Value& b = require_numeric(args[1], name);
    const auto& shape = broadcast_shape(a, b, name);
    Value out(ValueType::primitive("bool", Prim::Bool, shape), std::string(name));
    const std::size_t n = out.type()->element_count();
    const std::size_t na = a.type()->element_count(), nb = b.type()->element_count();
    const bool native = a.type()->prim() == b.type()->prim();
    for (std::size_t i = 0; i < n; ++i) {
        const std::size_t ia = na == 1 ? 0 : i, ib = nb == 1 ? 0 : i;
        int c = 0;
        if (native) {
            visit_prim(a.type()->prim(), [&]<class T>(std::type_identity<T>) {
                T x = load_scalar<T>(a.data() + ia * sizeof(T));
                T y = load_scalar<T>(b.data() + ib * sizeof(T));
                c = x < y ? -1 : (y < x ? 1 : (x == y ? 0 : 2));  // 2 = unordered (NaN)
            });
        } else {
            double x = a.scalar(ia), y = b.scalar(ib);
            c = x < y ? -1 : (y < x ? 1 : (x == y ? 0 : 2));
        }
        bool r = false;
        switch (op) {
            case CmpOp::Eq: r = c == 0; break;
            case CmpOp::Ne: r = c != 0; break;
            case CmpOp::Lt: r = c == -1; break;
            case CmpOp::Le: r = c == -1 || c == 0; break;
            case CmpOp::Gt: r = c == 1; break;
            case CmpOp::Ge: r = c == 1 || c == 0; break;
        }
        out.set_scalar(i, r ? 1.0 : 0.0);
    }
    return out;
}

Value unary(std::span<const Value> args, std::string_view name, double (*fn)(double)) {
    if (args.size() != 1) throw std::invalid_argument("operator '" + std::string(name) + "' expects 1 operand");
    const Value& a = require_numeric(args[0], name);
    Value out(a.type(), std::string(name));
    for (std::size_t i = 0; i < a.type()->element_count(); ++i) out.set_scalar(i, fn(a.scalar(i)));
    return out;
}

Value reduce_sum(std::span<const Value> args) {
    if (args.size() != 1) throw std::invalid_argument("operator 'sum' expects 1 operand");
    const Value& a = require_numeric(args[0], "sum");
    Value out(ValueType::primitive("f64", Prim::F64), "sum");
    double s = 0;
    for (std::size_t i = 0; i < a.type()->element_count(); ++i) s += a.scalar(i);
    out.set<double>(s);
    return out;
}

Value dot(std::span<const Value> args) {
    if (args.size() != 2) throw std::invalid_argument("operator 'dot' expects 2 operands");
    const Value& a = require_numeric(args[0], "dot");
    const Value& b = require_numeric(args[1], "dot");
    if (a.type()->element_count() != b.type()->element_count())
        throw std::invalid_argument("operator 'dot': element count mismatch");
    Value out(ValueType::primitive("f64", Prim::F64), "dot");
    double s = 0;
    for (std::size_t i = 0; i < a.type()->element_count(); ++i) s += a.scalar(i) * b.scalar(i);
    out.set<double>(s);
    return out;
}

}  // namespace

TypeRegistry::TypeRegistry() {
    for (Prim p : {Prim::U8, Prim::I8, Prim::U16, Prim::I16, Prim::U32, Prim::I32, Prim::U64, Prim::I64, Prim::F32,
                   Prim::F64, Prim::Bool}) {
        types_.emplace(std::string(prim_name(p)), ValueType::primitive(std::string(prim_name(p)), p));
    }
    define_alias("byte", get("u8"));
    define_alias("int", get("i32"));
    define_alias("long", get("i64"));
    define_alias("float", get("f32"));
    define_alias("double", get("f64"));
    install_builtin_operators();
}

TypePtr TypeRegistry::define_primitive(std::string name, std::size_t size) {
    if (contains(name)) throw std::invalid_argument("TypeRegistry: type '" + name + "' already defined");
    auto t = ValueType::primitive(name, Prim::Opaque, {}, size);
    types_.emplace(std::move(name), t);
    return t;
}

TypePtr TypeRegistry::define_struct(std::string name, const std::vector<std::pair<std::string, std::string>>& fields) {
    std::vector<std::pair<std::string, TypePtr>> resolved;
    resolved.reserve(fields.size());
    for (const auto& [fname, expr] : fields) resolved.emplace_back(fname, get(expr));
    return define_struct(std::move(name), std::move(resolved));
}

TypePtr TypeRegistry::define_struct(std::string name, std::vector<std::pair<std::string, TypePtr>> fields) {
    if (contains(name)) throw std::invalid_argument("TypeRegistry: type '" + name + "' already defined");
    auto t = ValueType::structure(name, std::move(fields));
    types_.emplace(std::move(name), t);
    return t;
}

TypePtr TypeRegistry::define_alias(std::string name, TypePtr type) {
    if (!type) throw std::invalid_argument("TypeRegistry: null alias target");
    if (contains(name)) throw std::invalid_argument("TypeRegistry: type '" + name + "' already defined");
    types_.emplace(std::move(name), type);
    return type;
}

bool TypeRegistry::contains(std::string_view name) const noexcept { return types_.find(name) != types_.end(); }

TypePtr TypeRegistry::get(std::string_view expr) const {
    expr = trim(expr);
    const std::size_t bracket = expr.find('[');
    std::string_view base = trim(expr.substr(0, bracket));
    auto it = types_.find(base);
    if (it == types_.end()) throw std::invalid_argument("TypeRegistry: unknown type '" + std::string(base) + "'");
    if (bracket == std::string_view::npos) return it->second;

    std::vector<std::size_t> dims;
    std::size_t pos = bracket;
    while (pos < expr.size()) {
        if (expr[pos] != '[') throw std::invalid_argument("TypeRegistry: malformed type expression '" + std::string(expr) + "'");
        ++pos;
        for (;;) {
            while (pos < expr.size() && expr[pos] == ' ') ++pos;
            std::size_t d = 0;
            auto [ptr, ec] = std::from_chars(expr.data() + pos, expr.data() + expr.size(), d);
            if (ec != std::errc{})
                throw std::invalid_argument("TypeRegistry: expected dimension in '" + std::string(expr) + "'");
            pos = static_cast<std::size_t>(ptr - expr.data());
            dims.push_back(d);
            while (pos < expr.size() && expr[pos] == ' ') ++pos;
            if (pos < expr.size() && expr[pos] == ',') { ++pos; continue; }
            if (pos < expr.size() && expr[pos] == ']') { ++pos; break; }
            throw std::invalid_argument("TypeRegistry: expected ',' or ']' in '" + std::string(expr) + "'");
        }
        while (pos < expr.size() && expr[pos] == ' ') ++pos;
    }
    const auto& inner = it->second->shape();
    dims.insert(dims.end(), inner.begin(), inner.end());
    return it->second->reshaped(std::move(dims));
}

std::size_t TypeRegistry::primitive_size(std::string_view name) const {
    TypePtr t = get(name);
    if (!t->is_primitive()) throw std::invalid_argument("TypeRegistry: '" + std::string(name) + "' is not a primitive");
    return t->element_size();
}

std::vector<std::string> TypeRegistry::names() const {
    std::vector<std::string> out;
    out.reserve(types_.size());
    for (const auto& [k, v] : types_) out.push_back(k);
    return out;
}

void TypeRegistry::define_operator(std::string op, std::string type_t, Operator fn) {
    if (!fn) throw std::invalid_argument("TypeRegistry: null operator '" + op + "'");
    operators_[{std::move(op), std::move(type_t)}] = std::move(fn);
}

bool TypeRegistry::has_operator(std::string_view op, std::string_view type_t) const noexcept {
    return operators_.find(std::pair<std::string, std::string>(op, type_t)) != operators_.end();
}

Value TypeRegistry::apply(std::string_view op, std::span<const Value> args) const {
    if (args.empty()) throw std::invalid_argument("TypeRegistry::apply: operator '" + std::string(op) + "' with no operands");
    const ValueType& t0 = *args[0].type();
    auto it = operators_.find(std::pair<std::string, std::string>(op, t0.t()));
    if (it == operators_.end() && t0.is_primitive() && prim_is_numeric(t0.prim()))
        it = operators_.find(std::pair<std::string, std::string>(op, kAnyNumeric));
    if (it == operators_.end())
        throw std::invalid_argument("TypeRegistry::apply: no operator '" + std::string(op) + "' for type '" + t0.t() + "'");
    return it->second(args);
}

void TypeRegistry::install_builtin_operators() {
    const std::string any(kAnyNumeric);
    const std::pair<const char*, BinOp> bins[] = {{"add", BinOp::Add}, {"sub", BinOp::Sub}, {"mul", BinOp::Mul},
                                                  {"div", BinOp::Div}, {"min", BinOp::Min}, {"max", BinOp::Max}};
    for (auto [name, op] : bins) {
        std::string n = name;
        define_operator(n, any, [op, n](std::span<const Value> a) { return binary(a, op, n); });
    }
    const std::pair<const char*, CmpOp> cmps[] = {{"eq", CmpOp::Eq}, {"ne", CmpOp::Ne}, {"lt", CmpOp::Lt},
                                                  {"le", CmpOp::Le}, {"gt", CmpOp::Gt}, {"ge", CmpOp::Ge}};
    for (auto [name, op] : cmps) {
        std::string n = name;
        define_operator(n, any, [op, n](std::span<const Value> a) { return compare(a, op, n); });
    }
    define_operator("neg", any, [](std::span<const Value> a) { return unary(a, "neg", [](double x) { return -x; }); });
    define_operator("abs", any, [](std::span<const Value> a) { return unary(a, "abs", [](double x) { return std::fabs(x); }); });
    define_operator("sum", any, [](std::span<const Value> a) { return reduce_sum(a); });
    define_operator("dot", any, [](std::span<const Value> a) { return dot(a); });
}

}  // namespace alg_exp::value
