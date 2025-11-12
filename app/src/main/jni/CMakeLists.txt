cmake_minimum_required(VERSION 3.18.1)
project("native-lib")

# Native Quellcode
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# Header-Dateien
include_directories(src/main/jni/include)

# Bibliotheken finden
find_library(
    log-lib
    log
)

# Verlinken
target_link_libraries(
    native-lib
    ${log-lib}
)