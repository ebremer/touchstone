# The C++ starter kit for touchstone-driver/1 (driver/PROTOCOL.md). Copy this directory into your
# client's repository, rename the project and the executable, add your client library, and build:
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
# then run build/lws-driver-adapter (build/lws-driver-adapter.exe on Windows).

cmake_minimum_required(VERSION 3.24...4.1)

project(lws-driver-adapter
    VERSION 0.0.0
    DESCRIPTION "LWS driver adapter (touchstone-driver/1)"
    LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# nlohmann/json (MIT), at a pinned release, fetched when you configure.
include(FetchContent)
FetchContent_Declare(nlohmann_json
    URL https://github.com/nlohmann/json/releases/download/v3.12.0/json.tar.xz
    URL_HASH SHA256=42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa)
FetchContent_MakeAvailable(nlohmann_json)

# The protocol. Copy it as it is.
add_library(lwsdriver STATIC src/adapter_runner.cpp)
target_include_directories(lwsdriver PUBLIC include)
target_link_libraries(lwsdriver PUBLIC nlohmann_json::nlohmann_json)

# Your adapter. Link your client library here too.
add_executable(lws-driver-adapter src/stub_adapter.cpp)
target_link_libraries(lws-driver-adapter PRIVATE lwsdriver)

foreach(target lwsdriver lws-driver-adapter)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4 /permissive- /utf-8)
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic)
    endif()
endforeach()

if(MINGW)
    # Runs without MinGW's DLLs on the PATH.
    target_link_options(lws-driver-adapter PRIVATE -static)
endif()
