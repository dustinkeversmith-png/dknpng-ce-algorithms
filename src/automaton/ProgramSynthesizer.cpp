#include "combinatorial/automaton/ProgramSynthesizer.hpp"

#include <chrono>
#include <cstring>
#include <stdexcept>
#include <unordered_set>

namespace combinatorial::automaton {

namespace {

bool commutative(Op op) { return op == Op::Add || op == Op::Mul || op == Op::And || op == Op::Or || op == Op::Xor; }

bool uses_memory(Op op) {
    switch (op) {
        case Op::Ld: case Op::St: case Op::Ldb: case Op::Stb: case Op::Push: case Op::Pop: case Op::Call: case Op::Ret:
            return true;
        default: return false;
    }
}

bool is_control(Op op) {
    switch (op) {
        case Op::Jmp: case Op::Jz: case Op::Jnz: case Op::Jn: case Op::Call: case Op::Ret: case Op::Halt: return true;
        default: return false;
    }
}

}  // namespace

ProgramSynthesizer::ProgramSynthesizer(const Machine& machine, std::vector<Example> examples, ProgramSearchOptions options)
    : machine_(machine), examples_(std::move(examples)), options_(std::move(options)) {
    if (examples_.empty()) throw std::invalid_argument("ProgramSynthesizer: no examples");
    for (const auto& e : examples_)
        if (!e.input.type()->layout_equals(*machine_.state_type()))
            throw std::invalid_argument("ProgramSynthesizer: example input is not a machine state");
    if (options_.registers == 0 || options_.registers > kRegisters)
        throw std::invalid_argument("ProgramSynthesizer: registers must be in [1, 8]");
    const auto R = static_cast<std::uint8_t>(options_.registers);
    for (Op op : options_.ops) {
        if (is_control(op)) throw std::invalid_argument("ProgramSynthesizer: control-flow opcodes are not searchable");
        Instruction ins{op, 0, 0, 0, 0};
        switch (op) {
            case Op::Nop: alphabet_.push_back(ins); break;
            case Op::Movi:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (auto imm : options_.immediates) alphabet_.push_back({op, a, 0, 0, imm});
                break;
            case Op::Mov: case Op::Neg: case Op::Not: case Op::Cmp:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b) {
                        if (op == Op::Mov && a == b) continue;  // no-op
                        if (op == Op::Cmp && a == b) continue;
                        alphabet_.push_back({op, a, b, 0, 0});
                    }
                break;
            case Op::Addi: case Op::Ld: case Op::St: case Op::Ldb: case Op::Stb:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b)
                        for (auto imm : options_.immediates) {
                            if (op == Op::Addi && imm == 0 && a == b) continue;
                            alphabet_.push_back({op, a, b, 0, imm});
                        }
                break;
            case Op::Push: case Op::Pop:
                for (std::uint8_t a = 0; a < R; ++a) alphabet_.push_back({op, a, 0, 0, 0});
                break;
            default:  // three-register ALU
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b)
                        for (std::uint8_t c = 0; c < R; ++c) {
                            if (commutative(op) && b > c) continue;
                            alphabet_.push_back({op, a, b, c, 0});
                        }
        }
    }
}

bool ProgramSynthesizer::goals_hold(const std::vector<value::Value>& states) const {
    for (std::size_t i = 0; i < examples_.size(); ++i)
        if (!examples_[i].goal.holds(states[i])) return false;
    return true;
}

bool ProgramSynthesizer::verify(const Program& program) const {
    for (const auto& e : examples_) {
        value::Value s = e.input.clone();
        machine_.run(s, program, program.size() + 1);
        if (!e.goal.holds(s)) return false;
    }
    return true;
}

std::optional<Program> ProgramSynthesizer::synthesize() {
    stats_ = ProgramSearchStats{};
    const auto start = std::chrono::steady_clock::now();
    auto r = options_.observational_equivalence ? search_oe() : search_dfs();
    stats_.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    return r;
}

std::optional<Program> ProgramSynthesizer::search_oe() {
    using Memory = value::Value::Memory;
    const std::size_t n = examples_.size();
    const std::size_t S = machine_.state_type()->byte_size();
    const Machine::Layout L = machine_.layout();
    bool memory_ops = false;
    for (Op op : options_.ops) memory_ops |= uses_memory(op);
    // Bytes that distinguish behaviour: sp/flags/halted/regs (+ memory when touched). pc is the depth.
    const std::size_t key_begin = L.sp;
    const std::size_t key_end = memory_ops ? S : L.regs + 8 * kRegisters;

    struct Node {
        std::size_t parent;
        Instruction ins;
    };
    std::vector<Node> nodes{{SIZE_MAX, {}}};
    auto make_key = [&](const Memory& m) {
        std::string k;
        k.reserve(n * (key_end - key_begin));
        for (std::size_t i = 0; i < n; ++i)
            k.append(reinterpret_cast<const char*>(m.data() + i * S + key_begin), key_end - key_begin);
        return k;
    };
    auto views = [&](const std::shared_ptr<Memory>& m) {
        std::vector<value::Value> vs;
        vs.reserve(n);
        for (std::size_t i = 0; i < n; ++i) vs.emplace_back(machine_.state_type(), m, i * S);
        return vs;
    };
    auto program_of = [&](std::size_t idx) {
        Program p;
        for (; nodes[idx].parent != SIZE_MAX; idx = nodes[idx].parent) p.push_back(nodes[idx].ins);
        return Program(p.rbegin(), p.rend());
    };

    auto root = std::make_shared<Memory>(n * S);
    for (std::size_t i = 0; i < n; ++i) std::memcpy(root->data() + i * S, examples_[i].input.data(), S);
    if (goals_hold(views(root))) return Program{};

    std::unordered_set<std::string> seen{make_key(*root)};
    std::vector<std::pair<std::size_t, std::shared_ptr<Memory>>> frontier{{0, root}}, next;
    for (std::size_t depth = 1; depth <= options_.max_length && !frontier.empty(); ++depth) {
        stats_.depth = depth;
        next.clear();
        for (auto& [idx, mem] : frontier) {
            for (const Instruction& ins : alphabet_) {
                if (stats_.states >= options_.max_states) {
                    stats_.budget_exhausted = true;
                    return std::nullopt;
                }
                ++stats_.states;
                auto child = std::make_shared<Memory>(*mem);
                auto vs = views(child);
                bool faulted = false;
                for (auto& v : vs) {
                    machine_.execute(v, ins);
                    faulted |= machine_.halted(v) != HaltReason::Running;
                }
                if (faulted) continue;
                if (!seen.insert(make_key(*child)).second) continue;
                ++stats_.distinct;
                nodes.push_back({idx, ins});
                if (goals_hold(vs)) return program_of(nodes.size() - 1);
                if (depth < options_.max_length) next.emplace_back(nodes.size() - 1, std::move(child));
            }
        }
        frontier.swap(next);
    }
    return std::nullopt;
}

std::optional<Program> ProgramSynthesizer::search_dfs() {
    const std::size_t n = examples_.size();
    // stack[d][i] = state of example i after d instructions
    std::vector<std::vector<value::Value>> stack(options_.max_length + 1);
    for (const auto& e : examples_) stack[0].push_back(e.input.clone());
    for (std::size_t d = 1; d <= options_.max_length; ++d)
        for (std::size_t i = 0; i < n; ++i) stack[d].push_back(examples_[i].input.clone());
    if (goals_hold(stack[0])) return Program{};

    Program prog;
    bool out_of_budget = false;
    auto rec = [&](auto& self, std::size_t d, std::size_t limit) -> bool {
        for (const Instruction& ins : alphabet_) {
            if (stats_.states >= options_.max_states) {
                out_of_budget = true;
                return false;
            }
            ++stats_.states;
            bool faulted = false;
            for (std::size_t i = 0; i < n; ++i) {
                stack[d + 1][i].copy_from(stack[d][i]);
                machine_.execute(stack[d + 1][i], ins);
                faulted |= machine_.halted(stack[d + 1][i]) != HaltReason::Running;
            }
            if (faulted) continue;
            prog.push_back(ins);
            if (d + 1 == limit) {
                if (goals_hold(stack[d + 1])) return true;
            } else if (self(self, d + 1, limit)) {
                return true;
            }
            prog.pop_back();
            if (out_of_budget) return false;
        }
        return false;
    };
    for (std::size_t limit = 1; limit <= options_.max_length; ++limit) {
        stats_.depth = limit;
        if (rec(rec, 0, limit)) return prog;
        if (out_of_budget) {
            stats_.budget_exhausted = true;
            return std::nullopt;
        }
    }
    return std::nullopt;
}

}  // namespace combinatorial::automaton
