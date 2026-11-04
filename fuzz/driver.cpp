// Standalone fuzz driver: random inputs, mutations of dictionary seeds, and
// splices. Usage: fuzz_x [--runs=N] [--seed=S] [--max-len=L] [files...]
// Files given on the command line are replayed verbatim (crash reproduction).
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <random>
#include <string>
#include <vector>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size);
extern const std::vector<std::string>& fuzz_dictionary();

int main(int argc, char** argv) {
    std::uint64_t runs = 10000, seed = 0;
    std::size_t max_len = 256;
    std::vector<std::string> files;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a.rfind("--runs=", 0) == 0) runs = std::stoull(a.substr(7));
        else if (a.rfind("--seed=", 0) == 0) seed = std::stoull(a.substr(7));
        else if (a.rfind("--max-len=", 0) == 0) max_len = std::stoull(a.substr(10));
        else files.push_back(a);
    }
    for (const auto& f : files) {
        std::ifstream in(f, std::ios::binary);
        std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        LLVMFuzzerTestOneInput(reinterpret_cast<const std::uint8_t*>(data.data()), data.size());
    }
    if (!files.empty()) return 0;

    const auto& dict = fuzz_dictionary();
    std::mt19937_64 rng(seed);
    auto pick = [&](std::size_t n) { return std::uniform_int_distribution<std::size_t>(0, n - 1)(rng); };
    std::string buf;
    for (std::uint64_t r = 0; r < runs; ++r) {
        buf.clear();
        const std::size_t mode = pick(4);
        if (mode == 0 || dict.empty()) {  // pure random bytes
            const std::size_t n = pick(max_len + 1);
            for (std::size_t i = 0; i < n; ++i) buf.push_back(static_cast<char>(pick(256)));
        } else {  // concatenated tokens, then byte mutations
            const std::size_t tokens = 1 + pick(12);
            for (std::size_t i = 0; i < tokens && buf.size() < max_len; ++i) buf += dict[pick(dict.size())];
            const std::size_t muts = mode == 1 ? 0 : pick(4);
            for (std::size_t i = 0; i < muts && !buf.empty(); ++i) {
                switch (pick(3)) {
                    case 0: buf[pick(buf.size())] = static_cast<char>(pick(256)); break;
                    case 1: buf.erase(pick(buf.size()), 1); break;
                    default: buf.insert(pick(buf.size() + 1), 1, static_cast<char>(pick(128))); break;
                }
            }
        }
        LLVMFuzzerTestOneInput(reinterpret_cast<const std::uint8_t*>(buf.data()), buf.size());
    }
    std::printf("%llu runs ok\n", static_cast<unsigned long long>(runs));
    return 0;
}
