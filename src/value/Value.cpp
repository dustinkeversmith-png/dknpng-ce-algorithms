#include "alg_exp/value/Value.hpp"

#include "alg_exp/value/Layout.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstring>
#include <sstream>

namespace alg_exp::value {

namespace {

[[noreturn]] void path_error(std::string_view path, std::size_t pos, const std::string& what) {
    throw std::invalid_argument("Value::reference: " + what + " at position " + std::to_string(pos) + " in path '" +
                                std::string(path) + "'");
}

bool is_ident_char(char c) { return std::isalnum(static_cast<unsigned char>(c)) || c == '_'; }

}  // namespace

Value::Value(TypePtr type, std::string name) : type_(std::move(type)), name_(std::move(name)) {
    if (!type_) throw std::invalid_argument("Value: null type");
    memory_ = std::make_shared<Memory>(type_->byte_size(), std::byte{0});
}

Value::Value(TypePtr type, std::shared_ptr<Memory> memory, std::size_t offset, std::string name)
    : type_(std::move(type)), memory_(std::move(memory)), offset_(offset), name_(std::move(name)) {
    if (!type_) throw std::invalid_argument("Value: null type");
    if (!memory_) throw std::invalid_argument("Value: null memory");
    if (offset_ > memory_->size() || type_->byte_size() > memory_->size() - offset_)
        throw std::out_of_range("Value: view [" + std::to_string(offset_) + ", +" + std::to_string(type_->byte_size()) +
                                ") exceeds backing buffer of " + std::to_string(memory_->size()) + " bytes");
}

FieldIndex Value::index_dimension(std::span<const std::size_t> indices) const {
    const ValueType& t = *type_;
    if (indices.size() > t.rank())
        throw std::out_of_range("Value::index_dimension: " + std::to_string(indices.size()) + " indices for rank " +
                                std::to_string(t.rank()) + " value '" + name_ + "'");
    std::size_t off = offset_;
    const auto& shape = t.shape();
    const auto& strides = t.strides();
    for (std::size_t d = 0; d < indices.size(); ++d) {
        if (indices[d] >= shape[d])
            throw std::out_of_range("Value::index_dimension: index " + std::to_string(indices[d]) + " >= extent " +
                                    std::to_string(shape[d]) + " in dimension " + std::to_string(d));
        off += indices[d] * strides[d];
    }
    TypePtr sub = t.suffix(indices.size());
    const std::size_t len = sub->byte_size();
    return FieldIndex{off, len, std::move(sub)};
}

Value Value::reference_element(std::span<const std::size_t> indices) const {
    FieldIndex fi = index_dimension(indices);
    return Value(std::move(fi.value_type), memory_, fi.offset, name_);
}

Value Value::operator[](std::size_t i) const {
    const std::size_t idx[1] = {i};
    return reference_element(idx);
}

Value Value::flat(std::size_t i) const {
    if (i >= type_->element_count())
        throw std::out_of_range("Value::flat: index " + std::to_string(i) + " >= " +
                                std::to_string(type_->element_count()));
    return Value(type_->element(), memory_, offset_ + i * type_->element_size(), name_);
}

bool Value::has_member(std::string_view member) const noexcept {
    return type_->is_scalar() && type_->field(member) != nullptr;
}

Value Value::reference_member(std::string_view member) const {
    if (!type_->is_scalar())
        throw std::invalid_argument("Value::reference_member: '" + std::string(member) + "' requested on shaped value '" +
                                    name_ + "'; index it first");
    const Field* f = type_->field(member);
    if (!f)
        throw std::invalid_argument("Value::reference_member: type '" + type_->name() + "' has no member '" +
                                    std::string(member) + "'");
    return Value(f->type, memory_, offset_ + f->offset, std::string(member));
}

FieldIndex Value::locate(std::string_view path) const { return value::locate(type_, path, offset_); }

FieldIndex locate(const TypePtr& type, std::string_view path, std::size_t base) {
    TypePtr cur = type;
    std::size_t off = base;
    std::size_t pos = 0;
    const std::size_t n = path.size();
    bool expect_segment = true;
    while (pos < n || expect_segment) {
        if (pos >= n) {
            if (pos == 0) break;  // empty path refers to the value itself
            path_error(path, pos, "dangling '.'");
        }
        // member name
        std::size_t start = pos;
        while (pos < n && is_ident_char(path[pos])) ++pos;
        if (pos > start) {
            std::string_view member = path.substr(start, pos - start);
            if (!cur->is_scalar()) path_error(path, start, "member '" + std::string(member) + "' on shaped value");
            const Field* f = cur->field(member);
            if (!f) path_error(path, start, "unknown member '" + std::string(member) + "' of " + cur->name());
            off += f->offset;
            cur = f->type;
        }
        // index groups
        bool had_index = false;
        while (pos < n && path[pos] == '[') {
            had_index = true;
            ++pos;
            std::size_t dim = 0;
            for (;;) {
                while (pos < n && path[pos] == ' ') ++pos;
                std::size_t value = 0;
                auto [ptr, ec] = std::from_chars(path.data() + pos, path.data() + n, value);
                if (ec != std::errc{}) path_error(path, pos, "expected index");
                pos = static_cast<std::size_t>(ptr - path.data());
                while (pos < n && path[pos] == ' ') ++pos;
                if (cur->rank() == 0) path_error(path, pos, "too many indices");
                if (value >= cur->shape()[0])
                    throw std::out_of_range("Value::reference: index " + std::to_string(value) + " >= extent " +
                                            std::to_string(cur->shape()[0]) + " in path '" + std::string(path) + "'");
                off += value * cur->strides()[0];
                cur = cur->suffix(1);
                ++dim;
                if (pos < n && path[pos] == ',') {
                    ++pos;
                    continue;
                }
                if (pos < n && path[pos] == ']') {
                    ++pos;
                    break;
                }
                path_error(path, pos, "expected ',' or ']'");
            }
            (void)dim;
        }
        if (pos == start && !had_index) path_error(path, pos, "expected member or index");
        expect_segment = false;
        if (pos < n) {
            if (path[pos] != '.') path_error(path, pos, "unexpected character");
            ++pos;
            expect_segment = true;
        }
    }
    return FieldIndex{off, cur->byte_size(), cur};
}

Value Value::reference(std::string_view path) const {
    FieldIndex fi = locate(path);
    return Value(std::move(fi.value_type), memory_, fi.offset, std::string(path));
}

void Value::insert(std::span<const std::byte> src, std::size_t at) const {
    if (at > size() || src.size() > size() - at)
        throw std::out_of_range("Value::insert: " + std::to_string(src.size()) + " bytes at " + std::to_string(at) +
                                " overruns " + std::to_string(size()) + "-byte value");
    if (!src.empty()) std::memmove(data() + at, src.data(), src.size());
}

void Value::read(std::span<std::byte> dst, std::size_t from) const {
    if (from > size() || dst.size() > size() - from)
        throw std::out_of_range("Value::read: " + std::to_string(dst.size()) + " bytes at " + std::to_string(from) +
                                " overruns " + std::to_string(size()) + "-byte value");
    if (!dst.empty()) std::memmove(dst.data(), data() + from, dst.size());
}

void Value::fill_zero() const noexcept {
    if (size()) std::memset(data(), 0, size());
}

void Value::copy_from(const Value& src) const {
    if (!type_->layout_equals(*src.type_))
        throw std::invalid_argument("Value::copy_from: layout mismatch " + src.type_->signature() + " -> " +
                                    type_->signature());
    if (size()) std::memmove(data(), src.data(), size());
}

Value Value::clone() const {
    Value out(type_, name_);
    if (size()) std::memcpy(out.data(), data(), size());
    return out;
}

bool Value::aliases(const Value& other) const noexcept {
    if (memory_ != other.memory_) return false;
    if (size() == 0 || other.size() == 0) return false;
    return offset_ < other.offset_ + other.size() && other.offset_ < offset_ + size();
}

void Value::require_scalar_slot(std::size_t bytes, const char* op) const {
    if (!type_->is_scalar() || !type_->is_primitive())
        throw std::invalid_argument(std::string("Value::") + op + ": '" + name_ + "' of type " + type_->signature() +
                                    " is not a primitive scalar");
    if (bytes != type_->element_size())
        throw std::invalid_argument(std::string("Value::") + op + ": " + std::to_string(bytes) +
                                    "-byte access to " + std::to_string(type_->element_size()) + "-byte slot '" +
                                    name_ + "' (" + type_->t() + ")");
}

void Value::require_numeric_scalar(const char* op) const {
    if (!type_->is_scalar() || !type_->is_primitive() || !prim_is_numeric(type_->prim()))
        throw std::invalid_argument(std::string("Value::") + op + ": '" + name_ + "' of type " + type_->signature() +
                                    " is not a numeric scalar");
}

double Value::scalar(std::size_t i) const {
    if (!type_->is_primitive() || !prim_is_numeric(type_->prim()))
        throw std::invalid_argument("Value::scalar: " + type_->signature() + " is not a numeric primitive array");
    if (i >= type_->element_count()) throw std::out_of_range("Value::scalar: index out of range");
    return load_as_double(data() + i * type_->element_size(), type_->prim());
}

void Value::set_scalar(std::size_t i, double v) const {
    if (!type_->is_primitive() || !prim_is_numeric(type_->prim()))
        throw std::invalid_argument("Value::set_scalar: " + type_->signature() + " is not a numeric primitive array");
    if (i >= type_->element_count()) throw std::out_of_range("Value::set_scalar: index out of range");
    store_from_double(data() + i * type_->element_size(), type_->prim(), v);
}

bool Value::operator==(const Value& other) const noexcept {
    if (!type_->equals(*other.type_)) return false;
    if (size() == 0) return true;
    return std::memcmp(data(), other.data(), size()) == 0;
}

std::size_t Value::hash() const noexcept {
    // FNV-1a over the byte image, seeded with the byte size.
    std::uint64_t h = 1469598103934665603ULL ^ static_cast<std::uint64_t>(size());
    const std::byte* p = data();
    for (std::size_t i = 0; i < size(); ++i) {
        h ^= std::to_integer<std::uint64_t>(p[i]);
        h *= 1099511628211ULL;
    }
    return static_cast<std::size_t>(h);
}

namespace {

void print(std::ostringstream& os, const ValueType& type, const std::byte* base) {
    auto print_elem = [&](const std::byte* at) {
        const ValueType& e = *type.element();
        if (e.is_primitive()) {
            if (!prim_is_numeric(e.prim())) {
                os << "0x";
                static const char* hex = "0123456789abcdef";
                for (std::size_t i = 0; i < e.element_size(); ++i) {
                    auto b = std::to_integer<unsigned>(at[i]);
                    os << hex[b >> 4] << hex[b & 15];
                }
            } else if (e.prim() == Prim::Bool) {
                os << (load_scalar<bool>(at) ? "true" : "false");
            } else {
                os << load_as_double(at, e.prim());
            }
        } else {
            os << '{';
            for (std::size_t i = 0; i < e.fields().size(); ++i) {
                const Field& f = e.fields()[i];
                os << (i ? ", " : "") << f.name << ": ";
                print(os, *f.type, at + f.offset);
            }
            os << '}';
        }
    };
    if (type.rank() == 0) {
        print_elem(base);
        return;
    }
    // nested brackets per dimension
    const auto& shape = type.shape();
    std::vector<std::size_t> idx(shape.size(), 0);
    for (std::size_t flat = 0; flat < type.element_count(); ++flat) {
        std::size_t opened = 0;
        for (std::size_t d = shape.size(); d-- > 0;) {
            if (idx[d] != 0) break;
            ++opened;
        }
        if (flat) os << ", ";
        for (std::size_t k = 0; k < opened; ++k) os << '[';
        print_elem(base + flat * type.element_size());
        // advance
        std::size_t closed = 0;
        for (std::size_t d = shape.size(); d-- > 0;) {
            if (++idx[d] < shape[d]) break;
            idx[d] = 0;
            ++closed;
        }
        for (std::size_t k = 0; k < closed; ++k) os << ']';
    }
    if (type.element_count() == 0) os << "[]";
}

}  // namespace

std::string Value::to_string() const {
    std::ostringstream os;
    print(os, *type_, data());
    return os.str();
}

}  // namespace alg_exp::value
