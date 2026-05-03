// Built only when the prebuilt libheif.so + headers haven't been committed yet.
// Loads cleanly so System.loadLibrary succeeds, but the JNI methods are absent —
// LibheifEncoder.isAvailable() is true but encode() will fail with
// UnsatisfiedLinkError, prompting fallback to HeifWriter at the call site.
//
// Replace this with libheif_bridge.cpp once the libheif binary is in place.

#include <jni.h>

extern "C" JNIEXPORT void JNICALL
Java_com_wjbyt_j2h_heif_LibheifEncoder_initStub(JNIEnv*, jclass) {
    // present only so the .so has at least one symbol
}
