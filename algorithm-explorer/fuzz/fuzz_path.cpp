#include "combinatorial/value.hpp"
#include "fuzz_common.hpp"

#include <vector>

using namespace combinatorial::value;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{"a", "b", "c", "pts", "x", "y", ".", "[", "]", ",", "0", "1", "2", "3",
                                            "7", "99999999999999999999", " ", "[1,2]", "..", "[]", "-1"};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static TypeRegistry reg;
    static const TypePtr P = reg.define_struct("P", {{"x", "f64"}, {"y", "i8"}});
    static const TypePtr Q = reg.define_struct("Q", {{"a", "u16"}, {"pts", "P[3,2]"}, {"b", "u8[4]"}, {"c", "P"}});
    static const Value root(reg.get("Q[2]"));
    const std::string_view path(reinterpret_cast<const char*>(data), size);
    FUZZ_GUARD({
        FieldIndex fi = root.locate(path);
        // a successful resolution always lies inside the root buffer
        FUZZ_CHECK(fi.offset + fi.length <= root.size());
        FUZZ_CHECK(fi.length == fi.value_type->byte_size());
        Value v = root.reference(path);
        FUZZ_CHECK(v.aliases(root) || v.size() == 0);
        if (v.type()->is_primitive() && v.type()->is_scalar() && prim_is_numeric(v.type()->prim())) v.assign(1.0);
    });
    return 0;
}
