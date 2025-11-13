cmake_minimum_required(VERSION 3.18.1)
project("native-streamreceiver")

# Compiler-Einstellungen für Performance
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Performance Flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-rtti -fno-exceptions")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a -mtune=cortex-a75")

# Native Quellcode
add_library(
        native-lib
        SHARED
        native-lib.cpp
)

# Header-Dateien
include_directories(src/main/cpp/include)

# Bibliotheken finden

find_library(
        log-lib
        log
)

find_library(
        android-lib
        android
)

# Verlinken
target_link_libraries(
        native-lib
        ${log-lib}
        ${android-lib}
)

