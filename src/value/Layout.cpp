#include "alg_exp/value/Layout.hpp"

namespace alg_exp::value {

namespace {

void walk_paths(const ValueType& type, std::size_t base, const std::string& prefix, std::vector<Leaf>& out) {
    const ValueType& elem = *type.element();
    for (std::size_t flat = 0; flat < type.element_count(); ++flat) {
        std::string path = prefix;
        if (type.rank() > 0) {
            // decompose flat index into a multi-index
            std::vector<std::size_t> idx(type.rank());
            std::size_t rem = flat;
            for (std::size_t d = type.rank(); d-- > 0;) {
                idx[d] = rem % type.shape()[d];
                rem /= type.shape()[d];
            }
            path += '[';
            for (std::size_t d = 0; d < idx.size(); ++d) path += (d ? "," : "") + std::to_string(idx[d]);
            path += ']';
        }
        const std::size_t at = base + flat * type.element_size();
        if (elem.is_primitive()) {
            out.push_back(Leaf{path.empty() ? std::string("") : path, at, elem.prim(), elem.element_size()});
        } else {
            for (const Field& f : elem.fields()) {
                walk_paths(*f.type, at + f.offset, path.empty() ? f.name : path + "." + f.name, out);
            }
        }
    }
}

void walk(const ValueType& type, std::size_t base,
          const std::function<void(std::size_t, Prim, std::size_t)>& fn) {
    const ValueType& elem = *type.element();
    for (std::size_t flat = 0; flat < type.element_count(); ++flat) {
        const std::size_t at = base + flat * type.element_size();
        if (elem.is_primitive()) {
            fn(at, elem.prim(), elem.element_size());
        } else {
            for (const Field& f : elem.fields()) walk(*f.type, at + f.offset, fn);
        }
    }
}

}  // namespace

std::vector<Leaf> flatten(const ValueType& type) {
    std::vector<Leaf> out;
    out.reserve(leaf_count(type));
    walk_paths(type, 0, "", out);
    return out;
}

void for_each_leaf(const ValueType& type, const std::function<void(std::size_t, Prim, std::size_t)>& fn) {
    walk(type, 0, fn);
}

std::size_t leaf_count(const ValueType& type) {
    const ValueType& elem = *type.element();
    std::size_t per = 0;
    if (elem.is_primitive()) {
        per = 1;
    } else {
        for (const Field& f : elem.fields()) per = checked_add(per, leaf_count(*f.type));
    }
    return checked_mul(per, type.element_count());
}

bool is_numeric_layout(const ValueType& type) {
    const ValueType& elem = *type.element();
    if (elem.is_primitive()) return prim_is_numeric(elem.prim());
    for (const Field& f : elem.fields())
        if (!is_numeric_layout(*f.type)) return false;
    return true;
}

}  // namespace alg_exp::value
