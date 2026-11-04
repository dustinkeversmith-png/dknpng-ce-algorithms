// Interprets the input as a little op-code stream driving the value runtime
// and the quantum layer: views, copies, casts, operators and gates, checking
// invariants (aliasing, round-trips, norm preservation) along the way.
#include "combinatorial/quantum/Quantum.hpp"
#include "combinatorial/value.hpp"
#include "fuzz_common.hpp"

#include <cmath>
#include <cstring>

using namespace combinatorial;
using value::Value;

const std::vector<std::string>& fuzz_dictionary() {
    static const std::vector<std::string> d{"\x00", "\x01", "\x02", "\x03", "\x04", "\x05", "\x06", "\x07", "\xff", "\x10"};
    return d;
}

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    static value::TypeRegistry reg;
    static const char* exprs[] = {"u8[4,4]", "i32[3]", "f64[2,2,2]", "i64", "bool[5]", "f32[7]"};
    std::size_t pos = 0;
    auto next = [&]() -> std::uint8_t { return pos < size ? data[pos++] : 0; };
    FUZZ_GUARD({
        Value a(reg.get(exprs[next() % 6])), b(reg.get(exprs[next() % 6]));
        for (int step = 0; step < 32 && pos < size; ++step) {
            switch (next() % 8) {
                case 0: {  // raw insert
                    std::vector<std::byte> buf(next() % 40);
                    for (auto& x : buf) x = std::byte{next()};
                    a.insert(buf, next() % 64);
                    break;
                }
                case 1: {  // flat view write + read-back
                    if (a.type()->element_count() == 0) break;
                    Value e = a.flat(next() % a.type()->element_count());
                    const double v = static_cast<double>(static_cast<std::int8_t>(next()));
                    e.assign(v);
                    FUZZ_CHECK(e.aliases(a));
                    const double back = e.as<double>();
                    if (a.type()->prim() != value::Prim::Bool && a.type()->prim() != value::Prim::U8) FUZZ_CHECK(back == v);
                    break;
                }
                case 2: { Value c = a.clone(); FUZZ_CHECK(c == a && c.hash() == a.hash()); break; }
                case 3: b.copy_from(a); FUZZ_CHECK(b == a || !b.type()->equals(*a.type())); break;
                case 4: { static const char* ops[] = {"add", "sub", "mul", "div", "min", "lt", "neg", "sum"};
                          const char* op = ops[next() % 8];
                          if (std::strcmp(op, "neg") == 0 || std::strcmp(op, "sum") == 0) reg.apply(op, {a});
                          else reg.apply(op, {a, b});
                          break; }
                case 5: { const std::size_t i[] = {next() % 5u, next() % 5u}; a.reference_element(i); break; }
                case 6: (void)a.to_string(); break;
                default: std::swap(a, b); break;
            }
        }
        // quantum: random gate sequence preserves the norm
        const std::size_t n = 1 + next() % 4;
        Value psi = quantum::make_state(n);
        for (int g = 0; g < 16 && pos < size; ++g) {
            const std::size_t q0 = next() % n, q1 = next() % n, q2 = next() % n;
            switch (next() % 6) {
                case 0: quantum::hadamard(psi, q0); break;
                case 1: quantum::cnot(psi, q0, q1); break;
                case 2: quantum::apply_1q(psi, q0, quantum::gates::rx(next() / 16.0)); break;
                case 3: quantum::swap(psi, q0, q1); break;
                case 4: quantum::toffoli(psi, q0, q1, q2); break;
                default: quantum::cz(psi, q0, q1); break;
            }
        }
        FUZZ_CHECK(std::fabs(quantum::norm_squared(psi) - 1) < 1e-9);
    });
    return 0;
}
