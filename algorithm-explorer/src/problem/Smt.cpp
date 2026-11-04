#include "combinatorial/problem/Smt.hpp"

#include <array>
#include <cctype>
#include <charconv>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <limits>
#include <optional>
#include <random>
#include <sstream>
#include <stdexcept>

namespace combinatorial::problem {

namespace {

using value::Prim;

struct Sorts {
    const value::TypePtr& type;
    std::map<std::string, Prim, std::less<>> cache;

    Prim prim_of(const std::string& path) {
        auto it = cache.find(path);
        if (it != cache.end()) return it->second;
        value::FieldIndex fi = value::locate(type, path);
        const value::ValueType& t = *fi.value_type;
        if (!t.is_primitive() || !t.is_scalar() || !value::prim_is_numeric(t.prim()))
            throw std::invalid_argument("SMT lowering: '" + path + "' is not a numeric scalar leaf");
        cache.emplace(path, t.prim());
        return t.prim();
    }
    bool is_real(const std::string& path) { return value::prim_is_float(prim_of(path)); }
};

std::string int_literal(double v) {
    if (!std::isfinite(v)) throw std::invalid_argument("SMT lowering: non-finite constant");
    std::array<char, 512> buf{};
    auto r = std::to_chars(buf.data(), buf.data() + buf.size(), std::fabs(std::trunc(v)), std::chars_format::fixed, 0);
    std::string s(buf.data(), r.ptr);
    return v <= -1.0 ? "(- " + s + ")" : s;
}

std::string real_literal(double v) {
    if (!std::isfinite(v)) throw std::invalid_argument("SMT lowering: non-finite constant");
    std::array<char, 512> buf{};
    auto r = std::to_chars(buf.data(), buf.data() + buf.size(), std::fabs(v), std::chars_format::fixed);
    std::string s(buf.data(), r.ptr);
    if (s.find('.') == std::string::npos) s += ".0";
    return v < 0 ? "(- " + s + ")" : s;
}

bool is_integral_constant(double v) { return std::isfinite(v) && std::floor(v) == v && std::fabs(v) < 9007199254740992.0; }

struct Term {
    std::string text;
    bool real;
    std::optional<double> constant = std::nullopt;
};

std::string coerce(const Term& t, bool to_real) {
    if (!to_real || t.real) return t.text;
    if (t.constant) return real_literal(*t.constant);
    return "(to_real " + t.text + ")";
}

Term lower(const Expr& e, Sorts& sorts) {
    using Op = Expr::Op;
    switch (e.op()) {
        case Op::Const:
            return is_integral_constant(e.constant_value())
                       ? Term{int_literal(e.constant_value()), false, e.constant_value()}
                       : Term{real_literal(e.constant_value()), true, e.constant_value()};
        case Op::Var: return {smt_symbol(e.path()), sorts.is_real(e.path())};
        case Op::Neg: {
            Term a = lower(e.args()[0], sorts);
            return {"(- " + a.text + ")", a.real};
        }
        case Op::Abs: {
            Term a = lower(e.args()[0], sorts);
            const std::string zero = a.real ? "0.0" : "0";
            return {"(ite (>= " + a.text + " " + zero + ") " + a.text + " (- " + a.text + "))", a.real};
        }
        default: {
            Term a = lower(e.args()[0], sorts), b = lower(e.args()[1], sorts);
            const bool real = a.real || b.real;
            const std::string x = coerce(a, real), y = coerce(b, real);
            switch (e.op()) {
                case Op::Add: return {"(+ " + x + " " + y + ")", real};
                case Op::Sub: return {"(- " + x + " " + y + ")", real};
                case Op::Mul: return {"(* " + x + " " + y + ")", real};
                case Op::Min: return {"(ite (<= " + x + " " + y + ") " + x + " " + y + ")", real};
                case Op::Max: return {"(ite (>= " + x + " " + y + ") " + x + " " + y + ")", real};
                default: break;
            }
        }
    }
    throw std::logic_error("SMT lowering: unhandled expression");
}

std::string lower(const Constraint& c, Sorts& sorts) {
    using Op = Constraint::Op;
    switch (c.op()) {
        case Op::True: return "true";
        case Op::False: return "false";
        case Op::Not: return "(not " + lower(c.args()[0], sorts) + ")";
        case Op::And:
        case Op::Or: {
            std::string s = c.op() == Op::And ? "(and" : "(or";
            for (const auto& a : c.args()) s += " " + lower(a, sorts);
            return s + ")";
        }
        default: {
            Term a = lower(c.lhs(), sorts), b = lower(c.rhs(), sorts);
            const bool real = a.real || b.real;
            const std::string x = coerce(a, real), y = coerce(b, real);
            switch (c.op()) {
                case Op::Eq: return "(= " + x + " " + y + ")";
                case Op::Ne: return "(not (= " + x + " " + y + "))";
                case Op::Lt: return "(< " + x + " " + y + ")";
                case Op::Le: return "(<= " + x + " " + y + ")";
                case Op::Gt: return "(> " + x + " " + y + ")";
                case Op::Ge: return "(>= " + x + " " + y + ")";
                default: break;
            }
        }
    }
    throw std::logic_error("SMT lowering: unhandled constraint");
}

std::pair<std::string, std::string> int_range(Prim p) {
    switch (p) {
        case Prim::Bool: return {"0", "1"};
        case Prim::U8: return {"0", "255"};
        case Prim::I8: return {"(- 128)", "127"};
        case Prim::U16: return {"0", "65535"};
        case Prim::I16: return {"(- 32768)", "32767"};
        case Prim::U32: return {"0", "4294967295"};
        case Prim::I32: return {"(- 2147483648)", "2147483647"};
        case Prim::U64: return {"0", "18446744073709551615"};
        case Prim::I64: return {"(- 9223372036854775808)", "9223372036854775807"};
        default: return {"", ""};
    }
}

// ---- minimal s-expression reader for models ------------------------------

struct SExpr {
    std::string atom;
    std::vector<SExpr> list;
    bool is_list = false;
};

class Reader {
public:
    explicit Reader(std::string_view s) : s_(s) {}
    bool done() {
        skip();
        return pos_ >= s_.size();
    }
    SExpr read() {
        skip();
        if (pos_ >= s_.size()) throw std::invalid_argument("SMT model: unexpected end of input");
        if (s_[pos_] == '(') {
            ++pos_;
            SExpr e;
            e.is_list = true;
            for (;;) {
                skip();
                if (pos_ >= s_.size()) throw std::invalid_argument("SMT model: unbalanced parentheses");
                if (s_[pos_] == ')') {
                    ++pos_;
                    return e;
                }
                e.list.push_back(read());
            }
        }
        if (s_[pos_] == ')') throw std::invalid_argument("SMT model: unexpected ')'");
        SExpr e;
        if (s_[pos_] == '|') {
            const std::size_t end = s_.find('|', pos_ + 1);
            if (end == std::string_view::npos) throw std::invalid_argument("SMT model: unterminated |symbol|");
            e.atom = std::string(s_.substr(pos_ + 1, end - pos_ - 1));
            pos_ = end + 1;
            return e;
        }
        if (s_[pos_] == '"') {
            const std::size_t end = s_.find('"', pos_ + 1);
            if (end == std::string_view::npos) throw std::invalid_argument("SMT model: unterminated string");
            e.atom = std::string(s_.substr(pos_, end - pos_ + 1));
            pos_ = end + 1;
            return e;
        }
        const std::size_t start = pos_;
        while (pos_ < s_.size() && !std::isspace(static_cast<unsigned char>(s_[pos_])) && s_[pos_] != '(' &&
               s_[pos_] != ')')
            ++pos_;
        e.atom = std::string(s_.substr(start, pos_ - start));
        return e;
    }

private:
    void skip() {
        while (pos_ < s_.size()) {
            if (std::isspace(static_cast<unsigned char>(s_[pos_]))) {
                ++pos_;
            } else if (s_[pos_] == ';') {
                while (pos_ < s_.size() && s_[pos_] != '\n') ++pos_;
            } else {
                break;
            }
        }
    }
    std::string_view s_;
    std::size_t pos_ = 0;
};

double eval_term(const SExpr& e) {
    if (!e.is_list) {
        double v = 0;
        auto r = std::from_chars(e.atom.data(), e.atom.data() + e.atom.size(), v);
        if (r.ec != std::errc{} || r.ptr != e.atom.data() + e.atom.size())
            throw std::invalid_argument("SMT model: cannot read numeral '" + e.atom + "'");
        return v;
    }
    if (e.list.empty() || e.list[0].is_list) throw std::invalid_argument("SMT model: malformed term");
    const std::string& f = e.list[0].atom;
    if (f == "-" && e.list.size() == 2) return -eval_term(e.list[1]);
    if (f == "-" && e.list.size() == 3) return eval_term(e.list[1]) - eval_term(e.list[2]);
    if (f == "/" && e.list.size() == 3) return eval_term(e.list[1]) / eval_term(e.list[2]);
    if (f == "+" ) {
        double s = 0;
        for (std::size_t i = 1; i < e.list.size(); ++i) s += eval_term(e.list[i]);
        return s;
    }
    if (f == "to_real" && e.list.size() == 2) return eval_term(e.list[1]);
    throw std::invalid_argument("SMT model: unsupported term head '" + f + "'");
}

void collect_defines(const SExpr& e, std::map<std::string, double>& out) {
    if (!e.is_list) return;
    if (e.list.size() == 5 && !e.list[0].is_list && e.list[0].atom == "define-fun" && e.list[2].is_list &&
        e.list[2].list.empty()) {
        const std::string& name = e.list[1].atom;
        out[name == "$" ? std::string() : name] = eval_term(e.list[4]);
        return;
    }
    for (const auto& c : e.list) collect_defines(c, out);
}

}  // namespace

std::string smt_symbol(std::string_view path) {
    if (path.find('|') != std::string_view::npos || path.find('\\') != std::string_view::npos)
        throw std::invalid_argument("SMT lowering: path contains '|' or '\\'");
    return "|" + (path.empty() ? std::string("$") : std::string(path)) + "|";
}

std::string to_smtlib(const value::TypePtr& type, const Constraint& c) {
    Sorts sorts{type, {}};
    return lower(c, sorts);
}

SmtScript lower_to_smtlib(const value::TypePtr& type, const std::vector<Invariant>& invariants,
                          const SmtOptions& options) {
    Sorts sorts{type, {}};
    SmtScript out;
    std::vector<std::pair<std::string, std::string>> asserts;  // (description, term)
    std::set<std::string> vars;
    for (const auto& inv : invariants) {
        auto c = inv.constraint();
        if (!c) {
            ++out.skipped_invariants;
            continue;
        }
        c->collect_variables(vars);
        asserts.emplace_back(inv.description(), lower(*c, sorts));
        ++out.lowered_invariants;
    }
    for (const auto& [path, iv] : options.bounds) vars.insert(path);

    std::ostringstream os;
    os << "; generated by combinatorial::problem::lower_to_smtlib\n";
    os << "; type: " << type->signature() << "\n";
    os << "(set-logic ALL)\n(set-option :produce-models true)\n";
    for (const auto& v : vars) {
        const Prim p = sorts.prim_of(v);
        os << "(declare-const " << smt_symbol(v) << (value::prim_is_float(p) ? " Real" : " Int") << ")\n";
        if (options.include_type_bounds && !value::prim_is_float(p)) {
            auto [lo, hi] = int_range(p);
            os << "(assert (and (<= " << lo << " " << smt_symbol(v) << ") (<= " << smt_symbol(v) << " " << hi << ")))\n";
        }
        out.variables.push_back(v);
    }
    for (const auto& [path, iv] : options.bounds) {
        const bool real = sorts.is_real(path);
        if (std::isfinite(iv.lo))
            os << "(assert (<= " << (real ? real_literal(iv.lo) : int_literal(std::ceil(iv.lo))) << " "
               << smt_symbol(path) << "))\n";
        if (std::isfinite(iv.hi))
            os << "(assert (<= " << smt_symbol(path) << " "
               << (real ? real_literal(iv.hi) : int_literal(std::floor(iv.hi))) << "))\n";
    }
    for (const auto& [desc, term] : asserts) {
        std::string d = desc;
        for (char& ch : d)
            if (ch == '\n') ch = ' ';
        os << "; " << d << "\n(assert " << term << ")\n";
    }
    os << "(check-sat)\n";
    if (options.produce_model) os << "(get-model)\n";
    out.text = os.str();
    return out;
}

std::map<std::string, double> parse_smt_model(std::string_view output) {
    std::map<std::string, double> out;
    Reader r(output);
    while (!r.done()) collect_defines(r.read(), out);
    return out;
}

value::Value apply_model(const value::TypePtr& type, const std::map<std::string, double>& model) {
    value::Value v(type);
    for (const auto& [path, x] : model) {
        value::FieldIndex fi = v.locate(path);
        if (!fi.value_type->is_primitive() || !fi.value_type->is_scalar()) continue;
        value::store_from_double(v.memory()->data() + fi.offset, fi.value_type->prim(), x);
    }
    return v;
}

SmtResult solve_smt(const value::TypePtr& type, const std::vector<Invariant>& invariants,
                    const std::string& solver_path, const SmtOptions& options) {
    SmtResult result;
    SmtScript script = lower_to_smtlib(type, invariants, options);
    namespace fs = std::filesystem;
    std::random_device rd;
    const fs::path file = fs::temp_directory_path() / ("combinatorial-" + std::to_string(rd()) + "-" + std::to_string(rd()) + ".smt2");
    {
        std::ofstream f(file);
        if (!f) return result;
        f << script.text;
    }
    const std::string cmd = "'" + solver_path + "' -smt2 '" + file.string() + "' 2>&1";
    FILE* pipe = ::popen(cmd.c_str(), "r");
    if (!pipe) {
        fs::remove(file);
        return result;
    }
    std::array<char, 4096> buf{};
    std::size_t n = 0;
    while ((n = std::fread(buf.data(), 1, buf.size(), pipe)) > 0) result.raw_output.append(buf.data(), n);
    const int rc = ::pclose(pipe);
    std::error_code ec;
    fs::remove(file, ec);

    std::istringstream is(result.raw_output);
    std::string first;
    is >> first;
    if (first == "sat") {
        result.status = SmtResult::Status::Sat;
        const std::size_t at = result.raw_output.find("sat") + 3;
        result.model = apply_model(type, parse_smt_model(std::string_view(result.raw_output).substr(at)));
    } else if (first == "unsat") {
        result.status = SmtResult::Status::Unsat;
    } else if (first == "unknown") {
        result.status = SmtResult::Status::Unknown;
    } else {
        result.status = rc == 0 ? SmtResult::Status::Unknown : SmtResult::Status::Unavailable;
    }
    return result;
}

}  // namespace combinatorial::problem
