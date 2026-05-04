cmake_minimum_required(VERSION 3.22)
project(j2h)

# ---- libheif bridge (only built when prebuilt .so + headers are committed) ----
set(LIBHEIF_PREBUILT "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libheif.so")
set(LIBHEIF_HEADERS  "${CMAKE_SOURCE_DIR}/include/libheif/heif.h")

if(EXISTS "${LIBHEIF_PREBUILT}" AND EXISTS "${LIBHEIF_HEADERS}")
    message(STATUS "j2h: building with libheif at ${LIBHEIF_PREBUILT}")
    add_library(libheif SHARED IMPORTED)
    set_target_properties(libheif PROPERTIES IMPORTED_LOCATION "${LIBHEIF_PREBUILT}")
    add_library(j2h_heif SHARED libheif_bridge.cpp)
    target_include_directories(j2h_heif PRIVATE "${CMAKE_SOURCE_DIR}/include")
    target_link_libraries(j2h_heif PRIVATE libheif jnigraphics log android)
else()
    message(STATUS "j2h: libheif binary missing, building stub")
    add_library(j2h_heif SHARED libheif_bridge_stub.cpp)
    target_link_libraries(j2h_heif PRIVATE log)
endif()

# ---- 10-bit color conversion (RGBA_F16 -> P010) ----
# Always built — no external dependencies, uses NDK's Android Bitmap API only.
add_library(j2h_10bit SHARED rgb16_to_p010.cpp)
target_link_libraries(j2h_10bit PRIVATE jnigraphics log)
