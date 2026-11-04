#include "alg_exp/problem.hpp"
#include "alg_exp/value.hpp"
#include "fuzz_common.hpp"

using namespace alg_exp;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{"(", ")", "define-fun", " ", "|x|", "|v[1]|", "|nope|", "()", "Int", "Real",
                                            "(- ", "(/ ", "3", "4.5", "1e400", ";c\n", "|", "\"s\"", "sat"};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static value::TypeRegistry reg;
    static const value::TypePtr t = reg.define_struct("M", {{"x", "i32"}, {"v", "u8[3]"}, {"w", "f64"}});
    const std::string_view text(reinterpret_cast<const char*>(data), size);
    FUZZ_GUARD({
        auto model = problem::parse_smt_model(text);
        value::Value v = problem::apply_model(t, model);
        FUZZ_CHECK(v.size() == t->byte_size());
    });
    return 0;
}
