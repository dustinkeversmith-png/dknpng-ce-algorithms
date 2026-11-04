#include "alg_exp/automaton.hpp"
#include "fuzz_common.hpp"

using namespace alg_exp::automaton;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{"movi ", "add ", "sub ", "jmp ", "jz ", "ld ", "st ", "push ", "pop ",
                                            "call ", "ret", "halt", "r0 ", "r1 ", "r7 ", "r8 ", "5", "-3", "0", "2147483648",
                                            ";", "\n", "#", ",", " "};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static const Machine m(64);
    const std::string_view src(reinterpret_cast<const char*>(data), size);
    FUZZ_GUARD({
        Program p = assemble(src);
        FUZZ_CHECK(assemble(to_string(p)) == p);  // printer/parser round trip
        FUZZ_CHECK(m.decode_program(m.encode(p)) == p);
        auto s = m.initial_state();
        const RunResult r = m.run(s, p, 500);
        FUZZ_CHECK(r.steps <= 500);
        FUZZ_CHECK(m.sp(s) <= m.memory_bytes());
    });
    return 0;
}
