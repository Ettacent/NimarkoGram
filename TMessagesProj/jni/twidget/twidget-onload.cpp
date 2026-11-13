/* Modifications Copyright (C) 2026 Ettacent */

#include "twidget-bridge.h"

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return -1;
    return registerTwidgetNatives(env) ? JNI_VERSION_1_6 : -1;
}
