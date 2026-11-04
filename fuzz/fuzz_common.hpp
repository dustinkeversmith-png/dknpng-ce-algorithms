#pragma once
// Harness contract: library code may reject input only with the documented
// exception types; anything else (or a sanitizer report) is a bug.
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <string_view>

#define FUZZ_GUARD(...)                                                                              \
    try {                                                                                            \
        __VA_ARGS__;                                                                                    \
    } catch (const std::invalid_argument&) {                                                         \
    } catch (const std::out_of_range&) {                                                             \
    } catch (const std::length_error&) {                                                             \
    } catch (const std::domain_error&) {                                                             \
    } catch (const std::exception& e) {                                                              \
        std::fprintf(stderr, "unexpected exception: %s\n", e.what());                                \
        std::abort();                                                                                \
    }

#define FUZZ_CHECK(cond)                                                                             \
    do {                                                                                             \
        if (!(cond)) {                                                                               \
            std::fprintf(stderr, "%s:%d: property failed: %s\n", __FILE__, __LINE__, #cond);         \
            std::abort();                                                                            \
        }                                                                                            \
    } while (0)
