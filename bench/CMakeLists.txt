find_package(benchmark REQUIRED)
add_executable(algorithm-explorer-bench bench_main.cpp)
target_link_libraries(algorithm-explorer-bench PRIVATE algorithm-explorer algorithm-explorer-warnings benchmark::benchmark benchmark::benchmark_main)
