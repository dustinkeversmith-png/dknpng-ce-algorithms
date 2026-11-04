// Executes arbitrary 8-byte instruction words (including invalid opcodes and
// register numbers) on a machine with arbitrary register/memory contents.
#include "combinatorial/automaton.hpp"
#include "fuzz_common.hpp"

#include <cstring>

using namespace combinatorial::automaton;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{std::string("\x04\x00\x01\x02\x00\x00\x00\x00", 8),
                                            std::string("\x16\x01\x00\x00\xf8\xff\xff\xff", 8),
                                            std::string("\x1a\x03\x00\x00\x00\x00\x00\x00", 8),
                                            std::string("\x1d\x00\x00\x00\x00\x00\x00\x00", 8),
                                            std::string("\x07\x00\x00\x00\x00\x00\x00\x00", 8)};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static const Machine m(32);
    auto s = m.initial_state();
    std::size_t pos = 0;
    // first bytes seed the registers
    for (std::size_t r = 0; r < kRegisters && pos + 8 <= size; ++r, pos += 8) {
        std::int64_t v;
        std::memcpy(&v, data + pos, 8);
        m.set_reg(s, r, v % 64);
    }
    Program p;
    for (; pos + 8 <= size; pos += 8) {
        Instruction ins;
        ins.op = static_cast<Op>(data[pos]);
        ins.a = data[pos + 1];
        ins.b = data[pos + 2];
        ins.c = data[pos + 3];
        std::memcpy(&ins.imm, data + pos + 4, 4);
        p.push_back(ins);
    }
    FUZZ_GUARD({
        m.run(s, p, 2000);
        FUZZ_CHECK(m.sp(s) <= m.memory_bytes());
        if (m.halted(s) == HaltReason::Fault) FUZZ_CHECK(m.flags(s) & flag::F);
    });
    return 0;
}
