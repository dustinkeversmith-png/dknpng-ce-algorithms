#include "alg_exp/automaton/TransitionSpace.hpp"

#include <cstring>

namespace alg_exp::automaton {

using problem::Invariant;
using problem::var;

TransitionSpace::TransitionSpace(const Machine& machine, std::vector<Op> ops) : machine_(machine), ops_(std::move(ops)) {
    if (ops_.empty())
        for (std::size_t i = 0; i < kOpCount; ++i) ops_.push_back(static_cast<Op>(i));
    type_ = value::ValueType::structure(
        "Transition", {{"before", machine.state_type()}, {"instr", machine.instruction_type()}, {"after", machine.state_type()}});

    structural_.emplace_back("valid opcode", var("instr.op") < static_cast<double>(kOpCount));
    for (const char* f : {"instr.a", "instr.b", "instr.c"})
        structural_.emplace_back(std::string(f) + " names a register", var(f) < static_cast<double>(kRegisters));

    const Machine::Layout L = machine.layout();
    struct Component {
        const char* name;
        std::size_t offset, size;
    };
    const Component comps[] = {{"pc", L.pc, 4},        {"sp", L.sp, 4},           {"flags", L.flags, 1},
                               {"halted", L.halted, 1}, {"regs", L.regs, 8 * kRegisters},
                               {"mem", L.mem, machine.memory_bytes()}};
    const std::size_t after_off = type_->field("after")->offset;
    for (const Component& c : comps) {
        const Machine* m = &machine_;
        auto expected = [m](const value::Value& t) {
            value::Value s = t.reference_member("before").clone();
            m->execute(s, m->decode(t.reference_member("instr")));
            return s;
        };
        const std::size_t off = c.offset, size = c.size;
        const std::string name = c.name;
        semantic_.emplace_back(
            "step preserves relation on " + name,
            [=](const value::Value& t) {
                value::Value e = expected(t);
                return std::memcmp(e.data() + off, t.data() + after_off + off, size) == 0;
            },
            [=](const value::Value& t) {
                value::Value e = expected(t);
                for (std::size_t i = 0; i < size; ++i)
                    if (e.data()[off + i] != t.data()[after_off + off + i])
                        return name + " differs at byte " + std::to_string(i) + " for '" +
                               m->decode(t.reference_member("instr")).to_string() + "'";
                return std::string();
            },
            std::vector<std::string>{"before", "instr", "after." + name});
    }
}

value::Value TransitionSpace::make(const value::Value& before, const Instruction& ins) const {
    value::Value t(type_, "transition");
    t.reference_member("before").copy_from(before);
    t.reference_member("instr").copy_from(machine_.encode(ins));
    value::Value after = before.clone();
    machine_.execute(after, ins);
    t.reference_member("after").copy_from(after);
    return t;
}

std::optional<value::Value> TransitionSpace::generate(problem::Rng& rng) const {
    value::Value s = machine_.initial_state();
    std::uniform_int_distribution<std::int64_t> small(-16, 16);
    for (std::size_t r = 0; r < kRegisters; ++r) machine_.set_reg(s, r, small(rng));
    std::uniform_int_distribution<int> byte(0, 255);
    for (std::size_t i = 0; i < machine_.memory_bytes(); ++i) machine_.set_mem(s, i, static_cast<std::uint8_t>(byte(rng)));
    Instruction ins;
    ins.op = ops_[std::uniform_int_distribution<std::size_t>(0, ops_.size() - 1)(rng)];
    std::uniform_int_distribution<int> reg(0, kRegisters - 1);
    ins.a = static_cast<std::uint8_t>(reg(rng));
    ins.b = static_cast<std::uint8_t>(reg(rng));
    ins.c = static_cast<std::uint8_t>(reg(rng));
    ins.imm = static_cast<std::int32_t>(small(rng));
    return make(s, ins);
}

std::optional<value::Value> TransitionSpace::project(const value::Value& v) const {
    if (!v.type()->layout_equals(*type_)) return std::nullopt;
    return make(v.reference_member("before"), machine_.decode(v.reference_member("instr")));
}

bool TransitionSpace::valid_trace(const std::vector<value::Value>& states, const Program& program) const {
    for (std::size_t i = 0; i + 1 < states.size(); ++i) {
        const std::uint32_t pc = machine_.pc(states[i]);
        if (machine_.halted(states[i]) != HaltReason::Running) return states[i] == states[i + 1];
        if (pc >= program.size()) {
            value::Value e = states[i].clone();
            machine_.step(e, program);
            if (!(e == states[i + 1])) return false;
            continue;
        }
        value::Value t(type_);
        t.reference_member("before").copy_from(states[i]);
        t.reference_member("instr").copy_from(machine_.encode(program[pc]));
        t.reference_member("after").copy_from(states[i + 1]);
        if (!contains(t)) return false;
    }
    return true;
}

}  // namespace alg_exp::automaton
