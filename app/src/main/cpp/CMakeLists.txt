cmake_minimum_required(VERSION 3.22)
project(j2h)

# Prebuilt libheif: app/src/main/jniLibs/<abi>/libheif.so
# Headers:        app/src/main/cpp/include/libheif/*.h
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
    message(WARNING "j2h: libheif binary or headers missing — building stub j2h_heif. "
                    "App will fall back to HeifWriter at runtime. "
                    "Run the 'Build libheif' workflow and commit the artifact to enable.")

    # Build a stub library so System.loadLibrary("j2h_heif") still resolves but the
    # native methods throw UnsatisfiedLinkError (caught by LibheifEncoder).
    add_library(j2h_heif SHARED libheif_bridge_stub.cpp)
    target_link_libraries(j2h_heif PRIVATE log)
endif()
