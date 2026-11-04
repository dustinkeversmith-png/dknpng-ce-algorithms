// algexp - command-line driver for algorithm-explorer.
//
//   algexp run "<asm>" [--reg rN=V ...] [--steps N]
//   algexp synth --example IN[,IN...]=OUT ... [--len N] [--regs N] [--plain]
//   algexp quantum (--circuit "<gates>" --qubits N | ghz N | qft N) [--shots N] [--seed S]
//   algexp queens N [--all]
//   algexp smt "<constraint over x,y,z>"     (prints SMT-LIB2 for i32 x,y,z)
#include "alg_exp/automaton.hpp"
#include "alg_exp/experiments/Experiment.hpp"
#include "alg_exp/problem.hpp"
#include "alg_exp/quantum/Quantum.hpp"
#include "alg_exp/value.hpp"

#include <cstdio>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

using namespace alg_exp;

namespace {

int usage() {
    std::cerr << "usage:\n"
                 "  algexp run \"<asm>\" [--reg rN=V ...] [--steps N]\n"
                 "  algexp synth --example IN[,IN...]=OUT ... [--len N] [--regs N] [--plain]\n"
                 "  algexp quantum (--qubits N --circuit \"h q0; cx q0,q1\" | ghz N | qft N) [--shots N] [--seed S]\n"
                 "  algexp queens N [--all]\n"
                 "  algexp experiments [--quick] [--seed S] [--only NAME] [--list]\n";
    return 2;
}

int cmd_experiments(const std::vector<std::string>& args) {
    experiments::RunConfig cfg;
    std::string only;
    bool list = false;
    for (std::size_t i = 0; i < args.size(); ++i) {
        if (args[i] == "--quick") cfg.quick = true;
        else if (args[i] == "--seed" && i + 1 < args.size()) cfg.seed = std::stoull(args[++i]);
        else if (args[i] == "--only" && i + 1 < args.size()) only = args[++i];
        else if (args[i] == "--list") list = true;
        else return usage();
    }
    std::vector<std::pair<experiments::ProblemPtr, experiments::Outcome>> results;
    for (const auto& p : experiments::catalog()) {
        if (!only.empty() && p->name() != only) continue;
        if (list) {
            std::cout << std::left << std::setw(22) << p->name() << std::setw(13) << p->mode() << p->description() << "\n";
            continue;
        }
        std::cerr << "running " << p->name() << " ...\n";
        results.emplace_back(p, p->run(cfg));
    }
    if (list) return 0;
    if (results.empty()) {
        std::cerr << "no problem named '" << only << "'\n";
        return 2;
    }
    std::cout << experiments::format_report(results);
    std::size_t verified = 0;
    for (const auto& [p, o] : results) verified += o.verified;
    std::cout << verified << "/" << results.size() << " problems verified on held-out inputs\n";
    return verified == results.size() ? 0 : 1;
}

std::vector<std::int64_t> parse_ints(const std::string& s) {
    std::vector<std::int64_t> out;
    std::stringstream ss(s);
    std::string tok;
    while (std::getline(ss, tok, ',')) out.push_back(std::stoll(tok));
    return out;
}

void print_registers(const automaton::Machine& m, const value::Value& s) {
    for (std::size_t r = 0; r < automaton::kRegisters; ++r) std::cout << "  r" << r << " = " << m.reg(s, r) << "\n";
    const auto f = m.flags(s);
    std::cout << "  pc = " << m.pc(s) << "  sp = " << m.sp(s) << "  flags = " << ((f & automaton::flag::Z) ? 'Z' : '-')
              << ((f & automaton::flag::N) ? 'N' : '-') << ((f & automaton::flag::C) ? 'C' : '-')
              << ((f & automaton::flag::V) ? 'V' : '-') << ((f & automaton::flag::F) ? 'F' : '-') << "\n";
}

int cmd_run(const std::vector<std::string>& args) {
    if (args.empty()) return usage();
    automaton::Machine m(256);
    auto prog = automaton::assemble(args[0]);
    auto s = m.initial_state();
    std::uint64_t steps = 1'000'000;
    for (std::size_t i = 1; i < args.size(); ++i) {
        if (args[i] == "--reg" && i + 1 < args.size()) {
            const std::string kv = args[++i];
            const auto eq = kv.find('=');
            if (kv.size() < 4 || kv[0] != 'r' || eq == std::string::npos) return usage();
            m.set_reg(s, std::stoul(kv.substr(1, eq - 1)), std::stoll(kv.substr(eq + 1)));
        } else if (args[i] == "--steps" && i + 1 < args.size()) {
            steps = std::stoull(args[++i]);
        } else {
            return usage();
        }
    }
    const auto r = m.run(s, prog, steps);
    static const char* reasons[] = {"running (step budget)", "halt", "end of program", "fault"};
    std::cout << "program: " << automaton::to_string(prog) << "\n";
    std::cout << "executed " << r.steps << " steps, stopped: " << reasons[static_cast<int>(r.reason)] << "\n";
    print_registers(m, s);
    return r.reason == automaton::HaltReason::Fault ? 1 : 0;
}

int cmd_synth(const std::vector<std::string>& args) {
    automaton::Machine m(64);
    std::vector<std::pair<std::vector<std::int64_t>, std::int64_t>> raw;
    automaton::ProgramSearchOptions o;
    o.registers = 0;
    for (std::size_t i = 0; i < args.size(); ++i) {
        if (args[i] == "--example" && i + 1 < args.size()) {
            const std::string e = args[++i];
            const auto eq = e.rfind('=');
            if (eq == std::string::npos) return usage();
            raw.emplace_back(parse_ints(e.substr(0, eq)), std::stoll(e.substr(eq + 1)));
        } else if (args[i] == "--len" && i + 1 < args.size()) {
            o.max_length = std::stoul(args[++i]);
        } else if (args[i] == "--regs" && i + 1 < args.size()) {
            o.registers = std::stoul(args[++i]);
        } else if (args[i] == "--plain") {
            o.observational_equivalence = false;
        } else {
            return usage();
        }
    }
    if (raw.empty()) return usage();
    const std::size_t inputs = raw.front().first.size();
    for (const auto& [in, out] : raw)
        if (in.size() != inputs) {
            std::cerr << "all examples need the same number of inputs\n";
            return 2;
        }
    const std::size_t out_reg = inputs;  // inputs in r0..r(k-1), result in r(k)
    if (o.registers == 0) o.registers = std::min<std::size_t>(automaton::kRegisters, inputs + 2);
    if (out_reg >= o.registers) {
        std::cerr << "need --regs > number of inputs\n";
        return 2;
    }
    std::vector<automaton::Example> ex;
    for (const auto& [in, out] : raw) {
        auto s = m.initial_state();
        for (std::size_t r = 0; r < in.size(); ++r) m.set_reg(s, r, in[r]);
        ex.push_back({s, problem::Invariant(problem::var("regs[" + std::to_string(out_reg) + "]") == static_cast<double>(out))});
    }
    automaton::ProgramSynthesizer ps(m, ex, o);
    std::cout << "searching programs up to length " << o.max_length << " over " << ps.alphabet().size()
              << " instructions (" << (o.observational_equivalence ? "observational equivalence" : "plain enumeration")
              << "); result in r" << out_reg << "\n";
    auto p = ps.synthesize();
    const auto& st = ps.stats();
    std::cout << std::fixed << std::setprecision(3) << "explored " << st.states << " states in " << st.seconds << " s ("
              << std::setprecision(0) << st.states_per_second() << " states/s)\n";
    if (!p) {
        std::cout << (st.budget_exhausted ? "budget exhausted" : "no program exists within the length bound") << "\n";
        return 1;
    }
    std::cout << "program (" << p->size() << " instructions):\n";
    for (const auto& ins : *p) std::cout << "  " << ins.to_string() << "\n";
    std::cout << (ps.verify(*p) ? "verified on " : "FAILED verification on ") << ex.size() << " examples\n";
    return 0;
}

quantum::Circuit parse_circuit(std::size_t n, const std::string& text) {
    using K = quantum::Gate::Kind;
    static const std::map<std::string, K> kinds{{"h", K::H},       {"x", K::X},     {"y", K::Y},     {"z", K::Z},
                                                {"s", K::S},       {"t", K::T},     {"sdg", K::Sdg}, {"tdg", K::Tdg},
                                                {"p", K::Phase},   {"rx", K::Rx},   {"ry", K::Ry},   {"rz", K::Rz},
                                                {"cx", K::CNOT},   {"cnot", K::CNOT}, {"cz", K::CZ}, {"cp", K::CPhase},
                                                {"swap", K::Swap}, {"ccx", K::Toffoli}};
    quantum::Circuit c(n);
    std::stringstream ss(text);
    std::string stmt;
    while (std::getline(ss, stmt, ';')) {
        std::stringstream st(stmt);
        std::string name, operands;
        if (!(st >> name)) continue;
        std::getline(st, operands);
        double theta = 0;
        if (auto lp = name.find('('); lp != std::string::npos) {
            theta = std::stod(name.substr(lp + 1, name.find(')') - lp - 1));
            name = name.substr(0, lp);
        }
        auto it = kinds.find(name);
        if (it == kinds.end()) throw std::invalid_argument("unknown gate '" + name + "'");
        std::vector<std::size_t> qs;
        std::stringstream os(operands);
        std::string q;
        while (std::getline(os, q, ',')) {
            const auto p = q.find('q');
            if (p == std::string::npos) throw std::invalid_argument("expected qN operand in '" + stmt + "'");
            qs.push_back(std::stoul(q.substr(p + 1)));
        }
        c.add({it->second, qs, theta});
    }
    return c;
}

int cmd_quantum(const std::vector<std::string>& args) {
    std::size_t qubits = 0, shots = 0;
    std::uint64_t seed = 1;
    std::string circuit_text;
    std::optional<quantum::Circuit> circuit;
    for (std::size_t i = 0; i < args.size(); ++i) {
        if (args[i] == "--qubits" && i + 1 < args.size()) qubits = std::stoul(args[++i]);
        else if (args[i] == "--circuit" && i + 1 < args.size()) circuit_text = args[++i];
        else if (args[i] == "--shots" && i + 1 < args.size()) shots = std::stoul(args[++i]);
        else if (args[i] == "--seed" && i + 1 < args.size()) seed = std::stoull(args[++i]);
        else if ((args[i] == "ghz" || args[i] == "qft") && i + 1 < args.size()) {
            const std::size_t n = std::stoul(args[i + 1]);
            circuit = args[i] == "ghz" ? quantum::Circuit::ghz(n) : quantum::Circuit::qft(n);
            ++i;
        } else return usage();
    }
    if (!circuit) {
        if (qubits == 0 || circuit_text.empty()) return usage();
        circuit = parse_circuit(qubits, circuit_text);
    }
    const std::size_t n = circuit->qubits();
    auto psi = circuit->run();
    std::cout << n << " qubits, " << circuit->size() << " gates: " << circuit->to_string() << "\n";
    std::cout << "state (amplitudes with |a|^2 > 1e-9):\n";
    const auto amps = quantum::amplitudes(psi);
    std::cout << std::fixed << std::setprecision(4) << std::showpos;
    std::size_t shown = 0;
    for (std::size_t i = 0; i < amps.size() && shown < 64; ++i) {
        if (std::norm(amps[i]) <= 1e-9) continue;
        std::string bits;
        for (std::size_t q = n; q-- > 0;) bits += (i >> q & 1) ? '1' : '0';
        std::cout << "  |" << bits << "> " << amps[i].real() << " " << amps[i].imag() << "i   p=" << std::noshowpos
                  << std::norm(amps[i]) << std::showpos << "\n";
        ++shown;
    }
    std::cout << std::noshowpos;
    std::cout << "norm^2 = " << quantum::norm_squared(psi) << "\n";
    if (n >= 2) std::cout << "linear entropy (q0 | rest) = " << quantum::linear_entropy(psi, 1) << "\n";
    if (shots) {
        std::mt19937_64 rng(seed);
        std::map<std::size_t, std::size_t> counts;
        for (std::size_t s = 0; s < shots; ++s) ++counts[quantum::sample(psi, rng)];
        std::cout << "measurement counts (" << shots << " shots):\n";
        for (auto [i, c] : counts) {
            std::string bits;
            for (std::size_t q = n; q-- > 0;) bits += (i >> q & 1) ? '1' : '0';
            std::cout << "  " << bits << ": " << c << "\n";
        }
    }
    return 0;
}

int cmd_queens(const std::vector<std::string>& args) {
    if (args.empty()) return usage();
    const std::size_t n = std::stoul(args[0]);
    const bool all = args.size() > 1 && args[1] == "--all";
    if (n == 0 || n > 64) return usage();
    using namespace problem;
    value::TypeRegistry reg;
    std::vector<Invariant> invs;
    for (std::size_t i = 0; i < n; ++i)
        for (std::size_t j = i + 1; j < n; ++j) {
            Expr a = var("[" + std::to_string(i) + "]"), b = var("[" + std::to_string(j) + "]");
            const double d = static_cast<double>(j - i);
            invs.emplace_back((a != b) && (a - b != d) && (b - a != d));
        }
    auto space = ConstrainedSpace::over_all_leaves(std::to_string(n) + "-queens", reg.get("u8[" + std::to_string(n) + "]"),
                                                   Domain::range(0, static_cast<double>(n) - 1), invs);
    std::optional<value::Value> board;
    if (n <= 12) {
        auto s = space->synthesizer();
        const std::size_t count = s.count();
        std::cout << n << "-queens: " << count << " solutions (" << s.stats().nodes << " nodes, " << std::fixed
                  << std::setprecision(3) << s.stats().seconds << " s)\n";
        auto sols = space->enumerate(all ? count : 1);
        if (!sols.empty()) board = sols.front();
        if (all)
            for (const auto& sol : sols) std::cout << "  " << sol.to_string() << "\n";
    } else {
        auto s = space->synthesizer(SynthesisOptions{.seed = 7});
        board = s.min_conflicts(200000);
        std::cout << n << "-queens via min-conflicts: " << (board ? "solved" : "not solved") << " in " << s.stats().nodes
                  << " steps\n";
    }
    if (board && !all) {
        for (std::size_t r = 0; r < n; ++r) {
            const auto c = static_cast<std::size_t>(board->scalar(r));
            std::cout << "  ";
            for (std::size_t k = 0; k < n; ++k) std::cout << (k == c ? "Q " : ". ");
            std::cout << "\n";
        }
    }
    return board ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) return usage();
    const std::string cmd = argv[1];
    std::vector<std::string> args(argv + 2, argv + argc);
    try {
        if (cmd == "run") return cmd_run(args);
        if (cmd == "synth") return cmd_synth(args);
        if (cmd == "quantum") return cmd_quantum(args);
        if (cmd == "queens") return cmd_queens(args);
        if (cmd == "experiments") return cmd_experiments(args);
        if (cmd == "-h" || cmd == "--help") {
            usage();
            return 0;
        }
    } catch (const std::exception& e) {
        std::cerr << "error: " << e.what() << "\n";
        return 2;
    }
    return usage();
}
