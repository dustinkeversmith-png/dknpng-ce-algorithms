# Each target exposes LLVMFuzzerTestOneInput. Without a libFuzzer runtime the
# standalone driver feeds seeded random + dictionary-mutated inputs, so the
# harnesses run anywhere (and under ASan/UBSan with -DCOMBINATORIAL_SANITIZE=ON).
set(COMBINATORIAL_FUZZ_TARGETS fuzz_path fuzz_type_expr fuzz_assembler fuzz_smt_model fuzz_machine fuzz_values)
foreach(t ${COMBINATORIAL_FUZZ_TARGETS})
  add_executable(${t} ${t}.cpp driver.cpp)
  target_link_libraries(${t} PRIVATE combinatorial combinatorial_warnings)
  if(COMBINATORIAL_BUILD_TESTS)
    add_test(NAME ${t} COMMAND ${t} --runs=20000 --seed=1)
  endif()
endforeach()
