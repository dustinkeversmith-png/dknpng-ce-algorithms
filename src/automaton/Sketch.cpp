#include "alg_exp/automaton/Sketch.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstring>
#include <sstream>
#include <stdexcept>

namespace alg_exp::automaton {

namespace {

bool is_register_token(std::string_view t) {
    if (t.size() < 2 || (t[0] != 'r' && t[0] != 'R')) return false;
    for (std::size_t i = 1; i < t.size(); ++i)
        if (!std::isdigit(static_cast<unsigned char>(t[i]))) return false;
    return true;
}

bool is_label_token(std::string_view t) {
    if (t.empty() || !(std::isalpha(static_cast<unsigned char>(t[0])) || t[0] == '_')) return false;
    for (char ch : t)
        if (!(std::isalnum(static_cast<unsigned char>(ch)) || ch == '_')) return false;
    return !is_register_token(t);
}

std::vector<std::string> tokens(const std::string& s) {
    std::istringstream is(s);
    std::vector<std::string> out;
    std::string t;
    while (is >> t) out.push_back(t);
    return out;
}

enum : unsigned { kReadA = 1, kReadB = 2, kReadC = 4 };

/// Register written (or -1), registers read, and side effects of an instruction.
struct Effects {
    int writes = -1;
    unsigned reads = 0;
    bool sets_flags = false;
    bool side_effect = false;  // memory/stack/control effects beyond the written register
};

Effects effects(const Instruction& i) {
    Effects e;
    switch (i.op) {
        case Op::Movi: e.writes = i.a; break;
        case Op::Mov: e.writes = i.a; e.reads = kReadB; break;
        case Op::Add: case Op::Sub: case Op::Mul: case Op::Div: case Op::Mod: case Op::And: case Op::Or:
        case Op::Xor: case Op::Shl: case Op::Shr:
            e.writes = i.a; e.reads = kReadB | kReadC; e.sets_flags = true; break;
        case Op::Addi: case Op::Neg: case Op::Not: e.writes = i.a; e.reads = kReadB; e.sets_flags = true; break;
        case Op::Ld: case Op::Ldb: e.writes = i.a; e.reads = kReadB; break;
        case Op::Cmp: e.reads = kReadA | kReadB; e.sets_flags = true; break;
        case Op::St: case Op::Stb: e.reads = kReadA | kReadB; e.side_effect = true; break;
        case Op::Nop: break;
        default: e.side_effect = true; e.reads = kReadA | kReadB | kReadC; break;
    }
    return e;
}

bool reads_reg(const Instruction& i, unsigned mask, int r) {
    return ((mask & kReadA) && i.a == r) || ((mask & kReadB) && i.b == r) || ((mask & kReadC) && i.c == r);
}

}  // namespace

bool is_dead_write_pair(const Instruction& prev, const Instruction& next) {
    const Effects p = effects(prev), n = effects(next);
    if (p.writes < 0 || p.side_effect) return false;
    if (n.writes != p.writes || reads_reg(next, n.reads, p.writes)) return false;
    // prev's flags survive unless next overwrites them.
    if (p.sets_flags && !n.sets_flags) return false;
    return true;
}

// ----------------------------------------------------------------- Sketch ---

Sketch Sketch::parse(std::string_view text) {
    Sketch s;
    std::vector<std::string> pending_labels;
    std::size_t pos = 0;
    while (pos <= text.size()) {
        std::size_t end = text.find_first_of(";\n", pos);
        if (end == std::string_view::npos) end = text.size();
        std::string line(text.substr(pos, end - pos));
        pos = end + 1;
        if (auto hash = line.find('#'); hash != std::string::npos) line.resize(hash);
        for (char& ch : line)
            if (ch == ',') ch = ' ';
        // leading labels
        for (;;) {
            auto t = tokens(line);
            if (t.empty() || t[0].back() != ':') break;
            const std::string name = t[0].substr(0, t[0].size() - 1);
            if (!is_label_token(name)) throw std::invalid_argument("Sketch: bad label '" + t[0] + "'");
            pending_labels.push_back(name);
            line = line.substr(line.find(':') + 1);
        }
        auto t = tokens(line);
        if (t.empty()) continue;
        for (const auto& l : pending_labels)
            if (!s.labels_.emplace(l, s.items_.size()).second)
                throw std::invalid_argument("Sketch: duplicate label '" + l + "'");
        pending_labels.clear();
        Item item;
        if (t[0] == "??") {
            if (t.size() < 2 || t.size() > 3) throw std::invalid_argument("Sketch: hole syntax is '?? N [alphabet]'");
            item.is_hole = true;
            item.hole = s.holes_.size();
            s.holes_.push_back({std::stoul(t[1]), t.size() == 3 ? t[2] : std::string()});
        } else {
            if (t.size() >= 2 && is_label_token(t.back())) {
                item.label_ref = t.back();
                t.back() = "0";
            }
            std::string stmt;
            for (const auto& tok : t) stmt += tok + " ";
            Program one = assemble(stmt);
            if (one.size() != 1) throw std::invalid_argument("Sketch: cannot parse '" + line + "'");
            item.ins = one[0];
        }
        s.items_.push_back(std::move(item));
    }
    for (const auto& l : pending_labels)
        if (!s.labels_.emplace(l, s.items_.size()).second)
            throw std::invalid_argument("Sketch: duplicate label '" + l + "'");
    for (const auto& item : s.items_)
        if (!item.label_ref.empty() && !s.labels_.count(item.label_ref))
            throw std::invalid_argument("Sketch: unknown label '" + item.label_ref + "'");
    return s;
}

Program Sketch::instantiate(const std::vector<std::vector<Instruction>>& fills) const {
    if (fills.size() != holes_.size()) throw std::invalid_argument("Sketch::instantiate: one fill per hole required");
    // instruction index at which each item starts
    std::vector<std::int32_t> start(items_.size() + 1);
    std::int32_t at = 0;
    for (std::size_t i = 0; i < items_.size(); ++i) {
        start[i] = at;
        if (items_[i].is_hole) {
            const auto& f = fills[items_[i].hole];
            if (f.size() > holes_[items_[i].hole].max_len)
                throw std::invalid_argument("Sketch::instantiate: fill longer than its hole");
            at += static_cast<std::int32_t>(f.size());
        } else {
            ++at;
        }
    }
    start[items_.size()] = at;
    Program p;
    p.reserve(static_cast<std::size_t>(at));
    for (const auto& item : items_) {
        if (item.is_hole) {
            const auto& f = fills[item.hole];
            p.insert(p.end(), f.begin(), f.end());
        } else {
            Instruction ins = item.ins;
            if (!item.label_ref.empty()) ins.imm = start[labels_.at(item.label_ref)];
            p.push_back(ins);
        }
    }
    return p;
}

std::string Sketch::to_string() const {
    std::string out;
    for (std::size_t i = 0; i < items_.size(); ++i) {
        if (i) out += "; ";
        const auto& item = items_[i];
        if (item.is_hole) out += "?? " + std::to_string(holes_[item.hole].max_len);
        else if (!item.label_ref.empty()) out += std::string(op_name(item.ins.op)) + " " + item.label_ref;
        else out += item.ins.to_string();
    }
    return out;
}

// ------------------------------------------------------ SketchSynthesizer ---

SketchSynthesizer::SketchSynthesizer(const Machine& machine, Sketch sketch, std::vector<Example> examples,
                                     SketchOptions options)
    : machine_(machine), sketch_(std::move(sketch)), examples_(std::move(examples)), options_(std::move(options)) {
    if (examples_.empty()) throw std::invalid_argument("SketchSynthesizer: no examples");
    for (const auto& e : examples_)
        if (!e.input.type()->layout_equals(*machine_.state_type()))
            throw std::invalid_argument("SketchSynthesizer: example input is not a machine state");
    for (std::size_t h = 0; h < sketch_.holes(); ++h) {
        auto it = options_.alphabets.find(sketch_.hole_alphabet(h));
        if (it == options_.alphabets.end())
            throw std::invalid_argument("SketchSynthesizer: no alphabet named '" + sketch_.hole_alphabet(h) + "'");
        alphabets_.push_back(make_alphabet(it->second));
        if (alphabets_.back().empty() && sketch_.hole_max(h) > 0)
            throw std::invalid_argument("SketchSynthesizer: empty alphabet for hole " + std::to_string(h));
    }
    for (std::size_t i = 0; i < examples_.size(); ++i) order_.push_back(i);
}

bool SketchSynthesizer::passes(const Program& p) {
    for (std::size_t k = 0; k < order_.size(); ++k) {
        const Example& e = examples_[order_[k]];
        value::Value s = e.input.clone();
        ++stats_.example_runs;
        const RunResult r = machine_.run(s, p, options_.max_steps);
        const bool ok = (r.reason == HaltReason::Halt || r.reason == HaltReason::EndOfProgram) && e.goal.holds(s);
        if (!ok) {
            std::rotate(order_.begin(), order_.begin() + static_cast<std::ptrdiff_t>(k),
                        order_.begin() + static_cast<std::ptrdiff_t>(k) + 1);  // failure-first
            return false;
        }
    }
    return true;
}

bool SketchSynthesizer::verify(const Program& program) const {
    for (const auto& e : examples_) {
        value::Value s = e.input.clone();
        const RunResult r = machine_.run(s, program, options_.max_steps);
        if (r.reason == HaltReason::Fault || r.reason == HaltReason::Running || !e.goal.holds(s)) return false;
    }
    return true;
}

std::optional<Program> SketchSynthesizer::synthesize() {
    stats_ = SketchStats{};
    const auto t0 = std::chrono::steady_clock::now();
    auto finish = [&] { stats_.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    const std::size_t H = sketch_.holes();
    std::size_t total_max = 0;
    for (std::size_t h = 0; h < H; ++h) total_max += sketch_.hole_max(h);

    std::vector<std::size_t> len(H, 0);
    std::vector<std::vector<Instruction>> fills(H);
    std::optional<Program> found;
    bool stop = false;

    // Odometer over instruction choices for a fixed length assignment.
    auto enumerate_fixed = [&]() {
        std::vector<std::vector<std::size_t>> idx(H);
        for (std::size_t h = 0; h < H; ++h) {
            idx[h].assign(len[h], 0);
            fills[h].resize(len[h]);
        }
        for (;;) {
            bool dead = false;
            for (std::size_t h = 0; h < H && !dead; ++h) {
                for (std::size_t k = 0; k < len[h]; ++k) fills[h][k] = alphabets_[h][idx[h][k]];
                if (options_.prune_dead_writes)
                    for (std::size_t k = 0; k + 1 < len[h] && !dead; ++k)
                        dead = is_dead_write_pair(fills[h][k], fills[h][k + 1]);
            }
            if (dead) {
                ++stats_.pruned;
            } else {
                if (stats_.candidates >= options_.max_candidates) {
                    stats_.budget_exhausted = true;
                    stop = true;
                    return;
                }
                ++stats_.candidates;
                Program p = sketch_.instantiate(fills);
                if (passes(p)) {
                    found = std::move(p);
                    fills_ = fills;
                    stop = true;
                    return;
                }
            }
            // advance odometer (last position fastest)
            std::size_t h = H;
            bool carried = true;
            while (carried && h-- > 0) {
                for (std::size_t k = len[h]; k-- > 0;) {
                    if (++idx[h][k] < alphabets_[h].size()) {
                        carried = false;
                        break;
                    }
                    idx[h][k] = 0;
                }
            }
            if (carried) return;  // wrapped around: all combinations done
        }
    };

    // Distribute total length T over the holes.
    auto distribute = [&](auto& self, std::size_t h, std::size_t remaining) -> void {
        if (stop) return;
        if (h == H) {
            if (remaining == 0) enumerate_fixed();
            return;
        }
        for (std::size_t l = 0; l <= std::min(remaining, sketch_.hole_max(h)) && !stop; ++l) {
            len[h] = l;
            self(self, h + 1, remaining - l);
        }
    };
    for (std::size_t T = 0; T <= total_max && !stop; ++T) distribute(distribute, 0, T);
    finish();
    return found;
}

}  // namespace alg_exp::automaton
