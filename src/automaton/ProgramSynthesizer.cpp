#include "alg_exp/automaton/ProgramSynthesizer.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <stdexcept>

namespace alg_exp::automaton {

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

std::vector<Instruction> make_alphabet(const ProgramSearchOptions& options) {
    if (options.registers == 0 || options.registers > kRegisters)
        throw std::invalid_argument("ProgramSynthesizer: registers must be in [1, 8]");
    if (!options.alphabet.empty()) {
        for (const auto& ins : options.alphabet)
            if (is_control(ins.op))
                throw std::invalid_argument("ProgramSynthesizer: control-flow opcodes are not searchable");
        return options.alphabet;
    }
    std::vector<Instruction> out;
    const auto R = static_cast<std::uint8_t>(options.registers);
    for (Op op : options.ops) {
        if (is_control(op)) throw std::invalid_argument("ProgramSynthesizer: control-flow opcodes are not searchable");
        Instruction ins{op, 0, 0, 0, 0};
        switch (op) {
            case Op::Nop: out.push_back(ins); break;
            case Op::Movi:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (auto imm : options.immediates) out.push_back({op, a, 0, 0, imm});
                break;
            case Op::Mov: case Op::Neg: case Op::Not: case Op::Cmp:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b) {
                        if (op == Op::Mov && a == b) continue;  // no-op
                        if (op == Op::Cmp && a == b) continue;
                        out.push_back({op, a, b, 0, 0});
                    }
                break;
            case Op::Addi: case Op::Ld: case Op::St: case Op::Ldb: case Op::Stb:
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b)
                        for (auto imm : options.immediates) {
                            if (op == Op::Addi && imm == 0 && a == b) continue;
                            out.push_back({op, a, b, 0, imm});
                        }
                break;
            case Op::Push: case Op::Pop:
                for (std::uint8_t a = 0; a < R; ++a) out.push_back({op, a, 0, 0, 0});
                break;
            default:  // three-register ALU
                for (std::uint8_t a = 0; a < R; ++a)
                    for (std::uint8_t b = 0; b < R; ++b)
                        for (std::uint8_t c = 0; c < R; ++c) {
                            if (commutative(op) && b > c) continue;
                            out.push_back({op, a, b, c, 0});
                        }
        }
    }
    return out;
}

ProgramSynthesizer::ProgramSynthesizer(const Machine& machine, std::vector<Example> examples, ProgramSearchOptions options)
    : machine_(machine),
      examples_(std::move(examples)),
      options_(std::move(options)),
      goal_env_(machine.state_type()) {
    if (examples_.empty()) throw std::invalid_argument("ProgramSynthesizer: no examples");
    for (const auto& e : examples_) goal_constraints_.push_back(e.goal.constraint());
    for (const auto& e : examples_)
        if (!e.input.type()->layout_equals(*machine_.state_type()))
            throw std::invalid_argument("ProgramSynthesizer: example input is not a machine state");
    alphabet_ = make_alphabet(options_);
    // Registers the alphabet can touch and whether it touches memory decide
    // which state bytes the search must track (see search_oe).
    for (const auto& ins : alphabet_) {
        memory_ops_ |= uses_memory(ins.op);
        key_registers_ = std::max<std::size_t>({key_registers_, ins.a + 1u, ins.b + 1u, ins.c + 1u});
    }
    key_registers_ = std::min(key_registers_, kRegisters);
}

bool ProgramSynthesizer::goals_hold(const std::vector<value::Value>& states) const {
    for (std::size_t i = 0; i < examples_.size(); ++i) {
        if (goal_constraints_[i]) {
            goal_env_.rebind(states[i].data());
            if (!goal_constraints_[i]->eval(goal_env_)) return false;
        } else if (!examples_[i].goal.holds(states[i])) {
            return false;
        }
    }
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

namespace {

/// Word-at-a-time hash for fixed-length binary keys.
std::uint64_t hash_key(const char* p, std::size_t n) noexcept {
    std::uint64_t h = 0x9e3779b97f4a7c15ULL ^ n;
    std::size_t i = 0;
    for (; i + 8 <= n; i += 8) {
        std::uint64_t w;
        std::memcpy(&w, p + i, 8);
        h = (h ^ w) * 0xff51afd7ed558ccdULL;
        h ^= h >> 32;
    }
    std::uint64_t tail = 0;
    std::memcpy(&tail, p + i, n - i);
    h = (h ^ tail) * 0xc4ceb9fe1a85ec53ULL;
    return h ^ (h >> 29);
}

/// Set of fixed-length byte keys: keys live contiguously in one array, an
/// open-addressing (linear probe) table holds key indices, and each key's
/// hash is cached so probes rarely touch key bytes and growth never rehashes.
class FixedKeySet {
public:
    explicit FixedKeySet(std::size_t key_len) : k_(key_len), table_(1024, kEmpty) {}

    /// Inserts the key; returns false if it was already present.
    bool insert(const char* key) {
        if ((count_ + 1) * 2 > table_.size()) grow();
        const std::uint64_t h = hash_key(key, k_);
        const std::size_t mask = table_.size() - 1;
        for (std::size_t p = h & mask;; p = (p + 1) & mask) {
            const std::uint32_t slot = table_[p];
            if (slot == kEmpty) {
                table_[p] = static_cast<std::uint32_t>(count_);
                hashes_.push_back(h);
                keys_.insert(keys_.end(), key, key + k_);
                ++count_;
                return true;
            }
            if (hashes_[slot] == h && std::memcmp(keys_.data() + slot * k_, key, k_) == 0) return false;
        }
    }
    std::size_t size() const noexcept { return count_; }

private:
    static constexpr std::uint32_t kEmpty = 0xffffffffu;
    void grow() {
        std::vector<std::uint32_t> t(table_.size() * 2, kEmpty);
        const std::size_t mask = t.size() - 1;
        for (std::size_t i = 0; i < count_; ++i) {
            std::size_t p = hashes_[i] & mask;
            while (t[p] != kEmpty) p = (p + 1) & mask;
            t[p] = static_cast<std::uint32_t>(i);
        }
        table_.swap(t);
    }
    std::size_t k_;
    std::size_t count_ = 0;
    std::vector<std::uint32_t> table_;
    std::vector<std::uint64_t> hashes_;
    std::vector<char> keys_;
};

}  // namespace

std::optional<Program> ProgramSynthesizer::search_oe() {
    using Memory = value::Value::Memory;
    const std::size_t n = examples_.size();
    const std::size_t S = machine_.state_type()->byte_size();
    const std::size_t T = n * S;  // bytes per search node: one state per example
    const Machine::Layout L = machine_.layout();
    const bool memory_ops = memory_ops_;
    // Bytes that can distinguish two nodes. Without memory ops the alphabet
    // only writes flags and r0..r(R-1); everything else (other registers, sp,
    // halt byte of surviving nodes, memory) is constant per example, and pc is
    // the depth. With memory ops, everything from sp onward may change.
    const std::size_t R = key_registers_;
    struct Span {
        std::size_t offset, len;
    };
    std::vector<Span> spans;
    if (memory_ops) {
        spans.push_back({L.sp, S - L.sp});
    } else {
        spans.push_back({L.flags, 1});
        spans.push_back({L.regs, 8 * R});
    }
    std::size_t key_len = 0;
    for (const Span& s : spans) key_len += s.len;

    struct Node {
        std::size_t parent;
        Instruction ins;
    };
    std::vector<Node> nodes{{SIZE_MAX, {}}};
    auto program_of = [&](std::size_t idx) {
        Program p;
        for (; nodes[idx].parent != SIZE_MAX; idx = nodes[idx].parent) p.push_back(nodes[idx].ins);
        return Program(p.rbegin(), p.rend());
    };

    // One scratch buffer with persistent per-example views: every candidate is
    // executed here, so no allocation happens per candidate.
    auto scratch = std::make_shared<Memory>(T);
    std::vector<value::Value> views;
    views.reserve(n);
    for (std::size_t i = 0; i < n; ++i) views.emplace_back(machine_.state_type(), scratch, i * S);
    std::byte* const work = scratch->data();

    std::string key(n * key_len, '\0');
    auto make_key = [&] {
        char* out = key.data();
        for (std::size_t i = 0; i < n; ++i)
            for (const Span& s : spans) {
                std::memcpy(out, work + i * S + s.offset, s.len);
                out += s.len;
            }
    };

    // Only the state prefix [0, M) can change: pc, sp, flags, halted and the
    // writable registers (the whole state when memory ops are enabled). The
    // scratch buffer keeps each example's constant tail from its input, and
    // frontier nodes store just the n mutable prefixes.
    const std::size_t M = memory_ops ? S : L.regs + 8 * R;
    if (L.pc != 0 || L.sp >= M || L.flags >= M || L.halted >= M)
        throw std::logic_error("ProgramSynthesizer: unexpected state layout");
    const std::size_t NI = n * M;
    auto load = [&](const std::byte* img) {
        for (std::size_t i = 0; i < n; ++i) std::memcpy(work + i * S, img + i * M, M);
    };
    auto store = [&](std::vector<std::byte>& arena_out) {
        const std::size_t at = arena_out.size();
        arena_out.resize(at + NI);
        for (std::size_t i = 0; i < n; ++i) std::memcpy(arena_out.data() + at + i * M, work + i * S, M);
    };

    for (std::size_t i = 0; i < n; ++i) std::memcpy(work + i * S, examples_[i].input.data(), S);
    std::vector<std::byte> frontier, next;
    std::vector<std::size_t> frontier_ids{0}, next_ids;
    store(frontier);
    if (goals_hold(views)) return Program{};

    FixedKeySet seen(key.size());
    make_key();
    seen.insert(key.data());

    for (std::size_t depth = 1; depth <= options_.max_length && !frontier_ids.empty(); ++depth) {
        stats_.depth = depth;
        next.clear();
        next_ids.clear();
        const bool expand = depth < options_.max_length;
        for (std::size_t k = 0; k < frontier_ids.size(); ++k) {
            const std::byte* parent = frontier.data() + k * NI;
            for (const Instruction& ins : alphabet_) {
                if (stats_.states >= options_.max_states) {
                    stats_.budget_exhausted = true;
                    return std::nullopt;
                }
                ++stats_.states;
                load(parent);
                bool faulted = false;
                for (std::size_t i = 0; i < n; ++i) {
                    std::byte* st = work + i * S;
                    machine_.execute_raw(st, ins);
                    faulted |= std::to_integer<std::uint8_t>(st[L.halted]) != 0;
                }
                if (faulted) continue;
                make_key();
                if (!seen.insert(key.data())) continue;
                ++stats_.distinct;
                nodes.push_back({frontier_ids[k], ins});
                if (goals_hold(views)) return program_of(nodes.size() - 1);
                if (expand) {
                    store(next);
                    next_ids.push_back(nodes.size() - 1);
                }
            }
        }
        frontier.swap(next);
        frontier_ids.swap(next_ids);
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

}  // namespace alg_exp::automaton
