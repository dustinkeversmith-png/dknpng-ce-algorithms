#include "combinatorial/automaton/Machine.hpp"

#include <array>
#include <cctype>
#include <charconv>
#include <cstring>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace combinatorial::automaton {

namespace {

constexpr std::array<std::string_view, kOpCount> kNames = {
    "nop", "halt", "movi", "mov", "add", "sub", "mul", "div", "mod", "and", "or", "xor", "shl", "shr",
    "addi", "neg", "not", "cmp", "jmp", "jz", "jnz", "jn", "ld", "st", "ldb", "stb", "push", "pop", "call", "ret"};

/// Which operand fields of each opcode name registers: bit0=a, bit1=b, bit2=c. bit3 = uses imm.
std::uint8_t operand_mask(Op op) {
    switch (op) {
        case Op::Nop: case Op::Halt: case Op::Ret: return 0;
        case Op::Movi: return 1 | 8;
        case Op::Mov: case Op::Neg: case Op::Not: case Op::Cmp: return 1 | 2;
        case Op::Add: case Op::Sub: case Op::Mul: case Op::Div: case Op::Mod: case Op::And: case Op::Or:
        case Op::Xor: case Op::Shl: case Op::Shr: return 1 | 2 | 4;
        case Op::Addi: case Op::Ld: case Op::St: case Op::Ldb: case Op::Stb: return 1 | 2 | 8;
        case Op::Jmp: case Op::Jz: case Op::Jnz: case Op::Jn: case Op::Call: return 8;
        case Op::Push: case Op::Pop: return 1;
        case Op::Count_: break;
    }
    return 0;
}

template <class T>
T ld(const std::byte* p) {
    T v;
    std::memcpy(&v, p, sizeof(T));
    return v;
}
template <class T>
void st(std::byte* p, T v) {
    std::memcpy(p, &v, sizeof(T));
}

std::uint64_t load_le64(const std::byte* p) {
    std::uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | std::to_integer<std::uint64_t>(p[i]);
    return v;
}
void store_le64(std::byte* p, std::uint64_t v) {
    for (int i = 0; i < 8; ++i) p[i] = std::byte{static_cast<unsigned char>(v >> (8 * i))};
}

}  // namespace

std::string_view op_name(Op op) noexcept {
    const auto i = static_cast<std::size_t>(op);
    return i < kOpCount ? kNames[i] : "invalid";
}

std::optional<Op> op_from_name(std::string_view name) noexcept {
    for (std::size_t i = 0; i < kOpCount; ++i)
        if (kNames[i] == name) return static_cast<Op>(i);
    return std::nullopt;
}

std::string Instruction::to_string() const {
    std::ostringstream os;
    os << op_name(op);
    const std::uint8_t m = operand_mask(op);
    if (m & 1) os << " r" << int(a);
    if (m & 2) os << " r" << int(b);
    if (m & 4) os << " r" << int(c);
    if (m & 8) os << " " << imm;
    return os.str();
}

std::string to_string(const Program& p) {
    std::string s;
    for (std::size_t i = 0; i < p.size(); ++i) s += (i ? "; " : "") + p[i].to_string();
    return s;
}

Program assemble(std::string_view text) {
    Program out;
    std::size_t pos = 0;
    while (pos <= text.size()) {
        std::size_t end = text.find_first_of(";\n", pos);
        if (end == std::string_view::npos) end = text.size();
        std::string line(text.substr(pos, end - pos));
        pos = end + 1;
        if (auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        for (char& ch : line)
            if (ch == ',') ch = ' ';
        std::istringstream is(line);
        std::string mnemonic;
        if (!(is >> mnemonic)) continue;
        for (char& ch : mnemonic) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        auto op = op_from_name(mnemonic);
        if (!op) throw std::invalid_argument("assemble: unknown mnemonic '" + mnemonic + "'");
        Instruction ins{*op, 0, 0, 0, 0};
        const std::uint8_t m = operand_mask(*op);
        std::uint8_t* regs[3] = {&ins.a, &ins.b, &ins.c};
        for (int k = 0; k < 3; ++k) {
            if (!(m & (1 << k))) continue;
            std::string tok;
            if (!(is >> tok) || tok.size() < 2 || (tok[0] != 'r' && tok[0] != 'R'))
                throw std::invalid_argument("assemble: expected register operand in '" + line + "'");
            unsigned r = 0;
            auto [p, ec] = std::from_chars(tok.data() + 1, tok.data() + tok.size(), r);
            if (ec != std::errc{} || p != tok.data() + tok.size() || r >= kRegisters)
                throw std::invalid_argument("assemble: bad register '" + tok + "'");
            *regs[k] = static_cast<std::uint8_t>(r);
        }
        if (m & 8) {
            std::string tok;
            if (!(is >> tok)) throw std::invalid_argument("assemble: expected immediate in '" + line + "'");
            long long v = 0;
            auto [p, ec] = std::from_chars(tok.data(), tok.data() + tok.size(), v);
            if (ec != std::errc{} || p != tok.data() + tok.size() || v < std::numeric_limits<std::int32_t>::min() ||
                v > std::numeric_limits<std::int32_t>::max())
                throw std::invalid_argument("assemble: bad immediate '" + tok + "'");
            ins.imm = static_cast<std::int32_t>(v);
        }
        std::string extra;
        if (is >> extra) throw std::invalid_argument("assemble: trailing operand '" + extra + "' in '" + line + "'");
        out.push_back(ins);
    }
    return out;
}

Machine::Machine(std::size_t memory_bytes) : memory_bytes_(memory_bytes) {
    if (memory_bytes_ < 8 || memory_bytes_ > std::numeric_limits<std::uint32_t>::max())
        throw std::invalid_argument("Machine: memory size must be in [8, 2^32)");
    state_type_ = registry_.define_struct(
        "State", {{"pc", "u32"}, {"sp", "u32"}, {"flags", "u8"}, {"halted", "u8"}, {"regs", "i64[8]"},
                  {"mem", "u8[" + std::to_string(memory_bytes_) + "]"}});
    instr_type_ = registry_.define_struct("Instr", {{"op", "u8"}, {"a", "u8"}, {"b", "u8"}, {"c", "u8"}, {"imm", "i32"}});
    layout_.pc = state_type_->field("pc")->offset;
    layout_.sp = state_type_->field("sp")->offset;
    layout_.flags = state_type_->field("flags")->offset;
    layout_.halted = state_type_->field("halted")->offset;
    layout_.regs = state_type_->field("regs")->offset;
    layout_.mem = state_type_->field("mem")->offset;
}

value::Value Machine::initial_state() const {
    value::Value s(state_type_, "state");
    st<std::uint32_t>(s.data() + layout_.sp, static_cast<std::uint32_t>(memory_bytes_));
    return s;
}

namespace {
void require_state(const value::Value& s, const value::TypePtr& t) {
    if (!s.type()->layout_equals(*t)) throw std::invalid_argument("Machine: value is not a machine state");
}
}  // namespace

void Machine::execute(const value::Value& state, const Instruction& ins) const {
    require_state(state, state_type_);
    execute_raw(state.data(), ins);
}

void Machine::execute_raw(std::byte* base, const Instruction& ins) const {
    std::byte* regs = base + layout_.regs;
    std::byte* mem = base + layout_.mem;
    const std::uint32_t pc0 = ld<std::uint32_t>(base + layout_.pc);
    std::uint8_t flags = ld<std::uint8_t>(base + layout_.flags);
    std::uint32_t next = pc0 + 1;

    auto fault = [&] {
        st<std::uint8_t>(base + layout_.flags, static_cast<std::uint8_t>(flags | flag::F));
        st<std::uint8_t>(base + layout_.halted, static_cast<std::uint8_t>(HaltReason::Fault));
    };
    if (ld<std::uint8_t>(base + layout_.halted) != 0) return;
    if (static_cast<std::size_t>(ins.op) >= kOpCount) return fault();
    const std::uint8_t m = operand_mask(ins.op);
    if (((m & 1) && ins.a >= kRegisters) || ((m & 2) && ins.b >= kRegisters) || ((m & 4) && ins.c >= kRegisters))
        return fault();

    auto R = [&](std::uint8_t r) { return ld<std::uint64_t>(regs + 8 * r); };
    auto W = [&](std::uint8_t r, std::uint64_t v) { st<std::uint64_t>(regs + 8 * r, v); };
    auto set_zn = [&](std::uint64_t v) {
        flags = static_cast<std::uint8_t>(flags & ~(flag::Z | flag::N | flag::C | flag::V));
        if (v == 0) flags |= flag::Z;
        if (static_cast<std::int64_t>(v) < 0) flags |= flag::N;
    };
    auto addr_ok = [&](std::uint64_t addr, std::size_t width) { return addr <= memory_bytes_ && width <= memory_bytes_ - addr; };
    const std::uint64_t imm = static_cast<std::uint64_t>(static_cast<std::int64_t>(ins.imm));

    switch (ins.op) {
        case Op::Nop: break;
        case Op::Halt:
            st<std::uint8_t>(base + layout_.halted, static_cast<std::uint8_t>(HaltReason::Halt));
            next = pc0;
            break;
        case Op::Movi: W(ins.a, imm); break;
        case Op::Mov: W(ins.a, R(ins.b)); break;
        case Op::Add: case Op::Sub: case Op::Cmp: {
            const std::uint64_t x = R(ins.op == Op::Cmp ? ins.a : ins.b), y = R(ins.op == Op::Cmp ? ins.b : ins.c);
            const bool sub = ins.op != Op::Add;
            const std::uint64_t r = sub ? x - y : x + y;
            set_zn(r);
            if (sub ? (x < y) : (r < x)) flags |= flag::C;
            const bool sx = static_cast<std::int64_t>(x) < 0, sy = static_cast<std::int64_t>(y) < 0,
                       sr = static_cast<std::int64_t>(r) < 0;
            if (sub ? (sx != sy && sr != sx) : (sx == sy && sr != sx)) flags |= flag::V;
            if (ins.op != Op::Cmp) W(ins.a, r);
            break;
        }
        case Op::Mul: { const std::uint64_t r = R(ins.b) * R(ins.c); set_zn(r); W(ins.a, r); break; }
        case Op::Div: case Op::Mod: {
            const auto x = static_cast<std::int64_t>(R(ins.b)), y = static_cast<std::int64_t>(R(ins.c));
            if (y == 0) return fault();
            std::int64_t r;
            if (x == std::numeric_limits<std::int64_t>::min() && y == -1) r = ins.op == Op::Div ? x : 0;
            else r = ins.op == Op::Div ? x / y : x % y;
            set_zn(static_cast<std::uint64_t>(r));
            W(ins.a, static_cast<std::uint64_t>(r));
            break;
        }
        case Op::And: { const std::uint64_t r = R(ins.b) & R(ins.c); set_zn(r); W(ins.a, r); break; }
        case Op::Or:  { const std::uint64_t r = R(ins.b) | R(ins.c); set_zn(r); W(ins.a, r); break; }
        case Op::Xor: { const std::uint64_t r = R(ins.b) ^ R(ins.c); set_zn(r); W(ins.a, r); break; }
        case Op::Shl: { const std::uint64_t r = R(ins.b) << (R(ins.c) & 63); set_zn(r); W(ins.a, r); break; }
        case Op::Shr: { const std::uint64_t r = R(ins.b) >> (R(ins.c) & 63); set_zn(r); W(ins.a, r); break; }
        case Op::Addi: { const std::uint64_t r = R(ins.b) + imm; set_zn(r); W(ins.a, r); break; }
        case Op::Neg: { const std::uint64_t r = 0 - R(ins.b); set_zn(r); W(ins.a, r); break; }
        case Op::Not: { const std::uint64_t r = ~R(ins.b); set_zn(r); W(ins.a, r); break; }
        case Op::Jmp: next = static_cast<std::uint32_t>(ins.imm); break;
        case Op::Jz: if (flags & flag::Z) next = static_cast<std::uint32_t>(ins.imm); break;
        case Op::Jnz: if (!(flags & flag::Z)) next = static_cast<std::uint32_t>(ins.imm); break;
        case Op::Jn: if (flags & flag::N) next = static_cast<std::uint32_t>(ins.imm); break;
        case Op::Ld: case Op::St: case Op::Ldb: case Op::Stb: {
            const std::uint64_t addr = R(ins.b) + imm;
            const std::size_t width = (ins.op == Op::Ld || ins.op == Op::St) ? 8 : 1;
            if (!addr_ok(addr, width)) return fault();
            std::byte* p = mem + addr;
            if (ins.op == Op::Ld) W(ins.a, load_le64(p));
            else if (ins.op == Op::St) store_le64(p, R(ins.a));
            else if (ins.op == Op::Ldb) W(ins.a, std::to_integer<std::uint64_t>(*p));
            else *p = std::byte{static_cast<unsigned char>(R(ins.a) & 0xff)};
            break;
        }
        case Op::Push: case Op::Call: {
            const std::uint32_t sp = ld<std::uint32_t>(base + layout_.sp);
            if (sp < 8 || sp > memory_bytes_) return fault();
            store_le64(mem + sp - 8, ins.op == Op::Push ? R(ins.a) : static_cast<std::uint64_t>(pc0 + 1));
            st<std::uint32_t>(base + layout_.sp, sp - 8);
            if (ins.op == Op::Call) next = static_cast<std::uint32_t>(ins.imm);
            break;
        }
        case Op::Pop: case Op::Ret: {
            const std::uint32_t sp = ld<std::uint32_t>(base + layout_.sp);
            if (sp > memory_bytes_ - 8) return fault();
            const std::uint64_t v = load_le64(mem + sp);
            st<std::uint32_t>(base + layout_.sp, sp + 8);
            if (ins.op == Op::Pop) W(ins.a, v);
            else next = static_cast<std::uint32_t>(v);
            break;
        }
        case Op::Count_: return fault();
    }
    st<std::uint8_t>(base + layout_.flags, flags);
    st<std::uint32_t>(base + layout_.pc, next);
}

void Machine::step(const value::Value& state, const Program& program) const {
    require_state(state, state_type_);
    std::byte* base = state.data();
    if (ld<std::uint8_t>(base + layout_.halted) != 0) return;
    const std::uint32_t pc0 = ld<std::uint32_t>(base + layout_.pc);
    if (pc0 >= program.size()) {
        st<std::uint8_t>(base + layout_.halted, static_cast<std::uint8_t>(HaltReason::EndOfProgram));
        return;
    }
    execute(state, program[pc0]);
}

RunResult Machine::run(const value::Value& state, const Program& program, std::uint64_t max_steps) const {
    RunResult r;
    while (r.steps < max_steps && halted(state) == HaltReason::Running) {
        const std::uint32_t pc0 = pc(state);
        step(state, program);
        if (pc0 < program.size()) ++r.steps;
    }
    r.reason = halted(state);
    return r;
}

std::int64_t Machine::reg(const value::Value& s, std::size_t r) const {
    if (r >= kRegisters) throw std::out_of_range("Machine::reg: register index");
    return ld<std::int64_t>(s.data() + layout_.regs + 8 * r);
}
void Machine::set_reg(const value::Value& s, std::size_t r, std::int64_t v) const {
    if (r >= kRegisters) throw std::out_of_range("Machine::set_reg: register index");
    st<std::int64_t>(s.data() + layout_.regs + 8 * r, v);
}
std::uint32_t Machine::pc(const value::Value& s) const { return ld<std::uint32_t>(s.data() + layout_.pc); }
void Machine::set_pc(const value::Value& s, std::uint32_t v) const { st<std::uint32_t>(s.data() + layout_.pc, v); }
std::uint32_t Machine::sp(const value::Value& s) const { return ld<std::uint32_t>(s.data() + layout_.sp); }
std::uint8_t Machine::flags(const value::Value& s) const { return ld<std::uint8_t>(s.data() + layout_.flags); }
HaltReason Machine::halted(const value::Value& s) const {
    const auto h = ld<std::uint8_t>(s.data() + layout_.halted);
    return h <= 3 ? static_cast<HaltReason>(h) : HaltReason::Fault;
}
std::uint8_t Machine::mem(const value::Value& s, std::size_t addr) const {
    if (addr >= memory_bytes_) throw std::out_of_range("Machine::mem: address");
    return std::to_integer<std::uint8_t>(s.data()[layout_.mem + addr]);
}
void Machine::set_mem(const value::Value& s, std::size_t addr, std::uint8_t v) const {
    if (addr >= memory_bytes_) throw std::out_of_range("Machine::set_mem: address");
    s.data()[layout_.mem + addr] = std::byte{v};
}

value::Value Machine::encode(const Instruction& ins) const {
    value::Value v(instr_type_, "instr");
    v.reference("op").set<std::uint8_t>(static_cast<std::uint8_t>(ins.op));
    v.reference("a").set<std::uint8_t>(ins.a);
    v.reference("b").set<std::uint8_t>(ins.b);
    v.reference("c").set<std::uint8_t>(ins.c);
    v.reference("imm").set<std::int32_t>(ins.imm);
    return v;
}

Instruction Machine::decode(const value::Value& v) const {
    if (!v.type()->layout_equals(*instr_type_)) throw std::invalid_argument("Machine::decode: not an instruction");
    Instruction ins;
    ins.op = static_cast<Op>(v.reference("op").get<std::uint8_t>());
    ins.a = v.reference("a").get<std::uint8_t>();
    ins.b = v.reference("b").get<std::uint8_t>();
    ins.c = v.reference("c").get<std::uint8_t>();
    ins.imm = v.reference("imm").get<std::int32_t>();
    return ins;
}

value::Value Machine::encode(const Program& p) const {
    value::Value v(instr_type_->reshaped({p.size()}), "program");
    for (std::size_t i = 0; i < p.size(); ++i) v[i].copy_from(encode(p[i]));
    return v;
}

Program Machine::decode_program(const value::Value& v) const {
    if (v.type()->rank() != 1) throw std::invalid_argument("Machine::decode_program: expected Instr[n]");
    Program p;
    for (std::size_t i = 0; i < v.type()->shape()[0]; ++i) p.push_back(decode(v[i]));
    return p;
}

}  // namespace combinatorial::automaton
