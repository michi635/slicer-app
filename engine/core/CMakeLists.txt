# slicercore: the app's slicing engine, a small command-line program around OrcaSlicer's libslic3r.
# Added by Orca's top-level CMakeLists.txt when ORCA_MOBILE_CORE is ON (engine build: SLIC3R_GUI=OFF, NDK toolchain).
# The app runs it as a separate process, so a crash or an out-of-memory kill never takes the app down.
file(GLOB SLICERCORE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
add_executable(slicercore ${SLICERCORE_SOURCES})
target_include_directories(slicercore PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
# "nlohmann/json.hpp" and other bundled headers, as libslic3r includes them
target_include_directories(slicercore SYSTEM PRIVATE "${CMAKE_SOURCE_DIR}/deps_src" "${CMAKE_SOURCE_DIR}/src")
target_compile_features(slicercore PRIVATE cxx_std_17)
target_link_libraries(slicercore PRIVATE libslic3r cereal::cereal ${CMAKE_DL_LIBS})
if (ANDROID)
    target_link_libraries(slicercore PRIVATE log)
endif ()
target_link_options(slicercore PRIVATE "-Wl,--gc-sections" "-Wl,--error-limit=0")
