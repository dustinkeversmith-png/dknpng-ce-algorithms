#pragma once
// A micro-instruction register machine whose entire state is a Value.
//
// State layout (packed, see Machine::state_type()):
//   pc     u32        program counter (instruction index)
//   sp     u32        stack pointer (byte address, grows down from mem size)
//   flags  u8         Z=1 N=2 C=4 V=8 F(ault)=16
//   halted u8         0 running, 1 HALT, 2 end of program, 3 fault
//   regs   i64[8]     general registers r0..r7
//   mem    u8[M]      flat byte memory
//
// Instructions are 8 bytes: opcode u8, a u8, b u8, c u8, imm i32.
// All arithmetic is two's-complement wrapping on 64 bits; memory words are
// little-endian. Faults (bad register, bad address, stack over/underflow,
// division by zero, invalid opcode) halt the machine with F set.

#include "combinatorial/value/TypeRegistry.hpp"
#include "combinatorial/value/Value.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace combinatorial::automaton {

enum class Op : std::uint8_t {
    Nop, Halt,
    Movi,   // ra <- imm
    Mov,    // ra <- rb
    Add, Sub, Mul, Div, Mod, And, Or, Xor, Shl, Shr,  // ra <- rb (op) rc
    Addi,   // ra <- rb + imm
    Neg,    // ra <- -rb
    Not,    // ra <- ~rb
    Cmp,    // flags <- ra - rb
    Jmp,    // pc <- imm
    Jz, Jnz, Jn,  // conditional on flags
    Ld,     // ra <- mem64[rb + imm]
    St,     // mem64[rb + imm] <- ra
    Ldb,    // ra <- mem8[rb + imm] (zero-extended)
    Stb,    // mem8[rb + imm] <- ra & 0xff
    Push,   // sp -= 8; mem64[sp] <- ra
    Pop,    // ra <- mem64[sp]; sp += 8
    Call,   // push pc+1; pc <- imm
    Ret,    // pc <- pop
    Count_
};

constexpr std::size_t kOpCount = static_cast<std::size_t>(Op::Count_);
constexpr std::size_t kRegisters = 8;

std::string_view op_name(Op op) noexcept;
std::optional<Op> op_from_name(std::string_view name) noexcept;

namespace flag {
constexpr std::uint8_t Z = 1, N = 2, C = 4, V = 8, F = 16;
}

enum class HaltReason : std::uint8_t { Running = 0, Halt = 1, EndOfProgram = 2, Fault = 3 };

struct Instruction {
    Op op = Op::Nop;
    std::uint8_t a = 0, b = 0, c = 0;
    std::int32_t imm = 0;

    bool operator==(const Instruction&) const = default;
    std::string to_string() const;
};

using Program = std::vector<Instruction>;
std::string to_string(const Program& p);
/// Parses one instruction per line/`;`, e.g. "movi r0 5; add r1 r0 r0; halt".
Program assemble(std::string_view text);

struct RunResult {
    std::uint64_t steps = 0;
    HaltReason reason = HaltReason::Running;
};

class Machine {
public:
    explicit Machine(std::size_t memory_bytes = 256);

    const value::TypePtr& state_type() const noexcept { return state_type_; }
    const value::TypePtr& instruction_type() const noexcept { return instr_type_; }
    std::size_t memory_bytes() const noexcept { return memory_bytes_; }
    value::TypeRegistry& registry() noexcept { return registry_; }

    /// Fresh state: zero registers and memory, sp = memory size.
    value::Value initial_state() const;

    /// Executes one instruction in place (does not consult or advance through a
    /// program; pc is updated as the instruction dictates).
    void execute(const value::Value& state, const Instruction& ins) const;
    /// Same as execute() on the raw bytes of a state (no layout check). The
    /// caller guarantees `state` points at state_type()->byte_size() bytes.
    void execute_raw(std::byte* state, const Instruction& ins) const;
    /// Fetches program[pc] and executes it; halts with EndOfProgram past the end.
    void step(const value::Value& state, const Program& program) const;
    RunResult run(const value::Value& state, const Program& program, std::uint64_t max_steps = 1'000'000) const;

    // ---- typed accessors on a state value ---------------------------------
    std::int64_t reg(const value::Value& s, std::size_t r) const;
    void set_reg(const value::Value& s, std::size_t r, std::int64_t v) const;
    std::uint32_t pc(const value::Value& s) const;
    void set_pc(const value::Value& s, std::uint32_t v) const;
    std::uint32_t sp(const value::Value& s) const;
    std::uint8_t flags(const value::Value& s) const;
    HaltReason halted(const value::Value& s) const;
    std::uint8_t mem(const value::Value& s, std::size_t addr) const;
    void set_mem(const value::Value& s, std::size_t addr, std::uint8_t v) const;

    // ---- encoding ----------------------------------------------------------
    value::Value encode(const Instruction& ins) const;
    Instruction decode(const value::Value& v) const;
    value::Value encode(const Program& p) const;            ///< Instr[n]
    Program decode_program(const value::Value& v) const;

    /// Byte offsets inside a state (relative to the state's own offset).
    struct Layout {
        std::size_t pc, sp, flags, halted, regs, mem;
    };
    const Layout& layout() const noexcept { return layout_; }

private:
    std::size_t memory_bytes_;
    value::TypeRegistry registry_;
    value::TypePtr state_type_;
    value::TypePtr instr_type_;
    Layout layout_{};
};

}  // namespace combinatorial::automaton
