# Each target exposes LLVMFuzzerTestOneInput. Without a libFuzzer runtime the
# standalone driver feeds seeded random + dictionary-mutated inputs, so the
# harnesses run anywhere (and under ASan/UBSan with -DALG_EXP_SANITIZE=ON).
set(ALG_EXP_FUZZ_TARGETS fuzz_path fuzz_type_expr fuzz_assembler fuzz_smt_model fuzz_machine fuzz_values)
foreach(t ${ALG_EXP_FUZZ_TARGETS})
  add_executable(${t} ${t}.cpp driver.cpp)
  target_link_libraries(${t} PRIVATE algorithm-explorer algorithm-explorer-warnings)
  if(ALG_EXP_BUILD_TESTS)
    add_test(NAME ${t} COMMAND ${t} --runs=20000 --seed=1)
  endif()
endforeach()
