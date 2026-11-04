#!/usr/bin/env bash
# Line coverage of src/ using plain gcov (no gcovr/lcov needed).
#   bash scripts/coverage.sh             -> per-file table + total
#   bash scripts/coverage.sh --missed F  -> uncovered lines of src file F (e.g. Synthesizer)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
build="$root/build/cov"
cmake -S "$root" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_FLAGS="--coverage -O0" \
      -DCMAKE_EXE_LINKER_FLAGS="--coverage" -DCOMBINATORIAL_BUILD_BENCHMARKS=OFF >/dev/null
cmake --build "$build" >/dev/null
find "$build" -name '*.gcda' -delete
ctest --test-dir "$build" --output-on-failure >/dev/null
out="$build/cov_out"
rm -rf "$out"
mkdir -p "$out"
cd "$out"
mapfile -t gcdas < <(find "$build" -name '*.gcda' -path '*combinatorial.dir*')
if [[ "${1:-}" == "--missed" ]]; then
  for f in "${gcdas[@]}"; do gcov -o "$(dirname "$f")" "$f" >/dev/null 2>&1; done
  grep -h '#####' "$2.cpp.gcov" | sed -E 's/^ *#####: *//'
  exit 0
fi
for f in "${gcdas[@]}"; do gcov -n -o "$(dirname "$f")" "$f" 2>/dev/null; done |
  grep -A1 "File '$root/src" | grep -v '^--' | paste - - |
  sed -E "s|File '$root/src/||; s|'\s*Lines executed:| |; s|% of| |" |
  awk '{printf "%7s%%  %5d lines  %s\n", $2, $3, $1; tot+=$3; hit+=$2*$3/100}
       END {printf "  TOTAL  %.2f%% of %d lines\n", 100*hit/tot, tot}' | sort -n
