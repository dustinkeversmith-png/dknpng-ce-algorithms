#pragma once
// Lowering of symbolic invariants to SMT-LIB2 and model round-tripping.
//
// Every constraint variable becomes a `declare-const` named by its quoted
// path (|regs[3]|). Integer and bool leaves map to `Int` with their machine
// range asserted, float leaves to `Real`. Mixed arithmetic is coerced with
// `to_real`. Opaque (non-symbolic) invariants cannot be lowered: they are
// skipped and counted, so a model may still need to be checked against them.

#include "alg_exp/problem/Invariant.hpp"
#include "alg_exp/value/Value.hpp"

#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace alg_exp::problem {

struct SmtOptions {
    bool include_type_bounds = true;           ///< assert the machine range of integer leaves
    std::map<std::string, Interval> bounds;    ///< extra per-variable bounds
    bool produce_model = true;                 ///< append (get-model)
};

struct SmtScript {
    std::string text;
    std::vector<std::string> variables;        ///< leaf paths declared
    std::size_t lowered_invariants = 0;
    std::size_t skipped_invariants = 0;        ///< opaque invariants not representable
};

/// Quoted SMT-LIB symbol for a leaf path.
std::string smt_symbol(std::string_view path);
/// SMT-LIB term for a single constraint (sorts resolved against `type`).
std::string to_smtlib(const value::TypePtr& type, const Constraint& c);
SmtScript lower_to_smtlib(const value::TypePtr& type, const std::vector<Invariant>& invariants,
                          const SmtOptions& options = {});

/// Parses `(define-fun |x| () Int 5)` style models into path -> value.
std::map<std::string, double> parse_smt_model(std::string_view solver_output);
/// Writes model values into a fresh zero-initialised Value of `type`.
value::Value apply_model(const value::TypePtr& type, const std::map<std::string, double>& model);

struct SmtResult {
    enum class Status { Sat, Unsat, Unknown, Unavailable } status = Status::Unavailable;
    std::optional<value::Value> model;
    std::string raw_output;
};

/// Runs an external SMT-LIB2 solver (`<solver_path> -smt2 <file>`, z3 compatible).
SmtResult solve_smt(const value::TypePtr& type, const std::vector<Invariant>& invariants,
                    const std::string& solver_path = "z3", const SmtOptions& options = {});

}  // namespace alg_exp::problem
