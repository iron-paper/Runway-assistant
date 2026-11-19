cmake_minimum_required(VERSION 3.22.1)
project(runway LANGUAGES CXX)

add_library(runway SHARED runway_jni.cpp)

target_compile_features(runway PRIVATE cxx_std_17)

find_library(log-lib log)
target_link_libraries(runway ${log-lib})
