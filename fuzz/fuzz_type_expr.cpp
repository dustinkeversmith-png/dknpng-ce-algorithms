#include "alg_exp/value.hpp"
#include "fuzz_common.hpp"

#include <vector>

using namespace alg_exp::value;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{"f64", "u8", "bool", "Pt", "int", "[", "]", ",", "0", "1", "3", "1024",
                                            "4294967296", " ", "[2,3]", "[0]", "18446744073709551615"};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static TypeRegistry reg;
    static const TypePtr pt = reg.define_struct("Pt", {{"x", "f32"}, {"y", "f32"}});
    const std::string_view expr(reinterpret_cast<const char*>(data), size);
    FUZZ_GUARD({
        TypePtr t = reg.get(expr);
        FUZZ_CHECK(t->byte_size() == t->element_size() * t->element_count());
        if (t->byte_size() <= (1u << 16)) {
            Value v(t);
            FUZZ_CHECK(v.clone() == v);
            FUZZ_CHECK(leaf_count(*t) == flatten(*t).size());
        }
    });
    return 0;
}
