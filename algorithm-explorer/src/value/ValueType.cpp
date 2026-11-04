#include "combinatorial/value/ValueType.hpp"

#include <limits>
#include <sstream>
#include <stdexcept>

namespace combinatorial::value {

std::size_t checked_mul(std::size_t a, std::size_t b) {
    if (a != 0 && b > std::numeric_limits<std::size_t>::max() / a)
        throw std::length_error("ValueType: layout size overflows size_t");
    return a * b;
}

std::size_t checked_add(std::size_t a, std::size_t b) {
    if (b > std::numeric_limits<std::size_t>::max() - a)
        throw std::length_error("ValueType: layout size overflows size_t");
    return a + b;
}

ValueType::ValueType(PassKey, std::string name, std::string t, Kind kind, Prim prim, std::size_t element_size,
                     std::vector<Field> fields, std::vector<std::size_t> shape)
    : name_(std::move(name)),
      t_(std::move(t)),
      kind_(kind),
      prim_(prim),
      shape_(std::move(shape)),
      element_size_(element_size),
      fields_(std::move(fields)) {
    strides_.resize(shape_.size());
    std::size_t stride = element_size_;
    std::size_t count = 1;
    for (std::size_t i = shape_.size(); i-- > 0;) {
        strides_[i] = stride;
        stride = checked_mul(stride, shape_[i]);
        count = checked_mul(count, shape_[i]);
    }
    element_count_ = count;
    byte_size_ = checked_mul(element_size_, element_count_);
    for (std::size_t i = 0; i < fields_.size(); ++i) {
        if (!field_index_.emplace(fields_[i].name, i).second)
            throw std::invalid_argument("ValueType '" + name_ + "': duplicate field '" + fields_[i].name + "'");
    }
}

TypePtr ValueType::build(std::string name, std::string t, Kind kind, Prim prim, std::size_t element_size,
                         std::vector<Field> fields, std::vector<std::size_t> shape) {
    // Build innermost first so every type can reference its (smaller) suffixes.
    const std::size_t rank = shape.size();
    std::vector<std::shared_ptr<ValueType>> chain(rank + 1);
    for (std::size_t k = rank + 1; k-- > 0;) {
        std::vector<std::size_t> sub(shape.begin() + static_cast<std::ptrdiff_t>(k), shape.end());
        chain[k] = std::make_shared<ValueType>(PassKey{}, name, t, kind, prim, element_size, fields, std::move(sub));
        for (std::size_t j = k + 1; j <= rank; ++j) chain[k]->suffixes_.push_back(chain[j]);
    }
    return chain[0];
}

TypePtr ValueType::primitive(std::string name, Prim prim, std::vector<std::size_t> shape, std::size_t size) {
    if (size == 0) size = prim_size(prim);
    if (size == 0) throw std::invalid_argument("ValueType::primitive: opaque primitive '" + name + "' needs a size");
    if (prim != Prim::Opaque && size != prim_size(prim))
        throw std::invalid_argument("ValueType::primitive: size does not match primitive kind");
    std::string t = prim == Prim::Opaque ? name : std::string(prim_name(prim));
    return build(std::move(name), std::move(t), Kind::Primitive, prim, size, {}, std::move(shape));
}

TypePtr ValueType::structure(std::string name, std::vector<std::pair<std::string, TypePtr>> members,
                             std::vector<std::size_t> shape) {
    std::vector<Field> fields;
    fields.reserve(members.size());
    std::size_t offset = 0;
    for (auto& [fname, ftype] : members) {
        if (!ftype) throw std::invalid_argument("ValueType::structure: null type for field '" + fname + "'");
        if (fname.empty()) throw std::invalid_argument("ValueType::structure: empty field name");
        fields.push_back(Field{fname, ftype, offset});
        offset = checked_add(offset, ftype->byte_size());
    }
    std::string t = name;
    return build(std::move(name), std::move(t), Kind::Struct, Prim::Opaque, offset, std::move(fields),
                 std::move(shape));
}

TypePtr ValueType::reshaped(std::vector<std::size_t> shape) const {
    return build(name_, t_, kind_, prim_, element_size_, fields_, std::move(shape));
}

TypePtr ValueType::suffix(std::size_t dropped) const {
    if (dropped == 0) return shared_from_this();
    if (dropped > suffixes_.size()) throw std::out_of_range("ValueType::suffix: dropping more dimensions than rank");
    return suffixes_[dropped - 1];
}

const Field* ValueType::field(std::string_view fname) const noexcept {
    auto it = field_index_.find(fname);
    return it == field_index_.end() ? nullptr : &fields_[it->second];
}

bool ValueType::layout_equals(const ValueType& o) const noexcept {
    if (this == &o) return true;
    if (kind_ != o.kind_ || prim_ != o.prim_ || shape_ != o.shape_ || element_size_ != o.element_size_ ||
        fields_.size() != o.fields_.size())
        return false;
    for (std::size_t i = 0; i < fields_.size(); ++i)
        if (fields_[i].offset != o.fields_[i].offset || !fields_[i].type->layout_equals(*o.fields_[i].type))
            return false;
    return true;
}

bool ValueType::equals(const ValueType& o) const noexcept {
    if (this == &o) return true;
    if (t_ != o.t_ || !layout_equals(o)) return false;
    for (std::size_t i = 0; i < fields_.size(); ++i)
        if (fields_[i].name != o.fields_[i].name || !fields_[i].type->equals(*o.fields_[i].type)) return false;
    return true;
}

std::string ValueType::signature() const {
    std::ostringstream os;
    if (is_primitive()) {
        os << t_;
    } else {
        os << t_ << '{';
        for (std::size_t i = 0; i < fields_.size(); ++i) {
            if (i) os << ',';
            os << fields_[i].name << ':' << fields_[i].type->signature();
        }
        os << '}';
    }
    if (!shape_.empty()) {
        os << '[';
        for (std::size_t i = 0; i < shape_.size(); ++i) os << (i ? "," : "") << shape_[i];
        os << ']';
    }
    return os.str();
}

}  // namespace combinatorial::value
