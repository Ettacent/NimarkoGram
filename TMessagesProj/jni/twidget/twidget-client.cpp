/* Modifications Copyright (C) 2026 Ettacent */

#include "filters.h"
#include "twidget/gesture_policy.h"

#include <android/bitmap.h>
#include <jni.h>

#include <cstdint>
#include <optional>
#include <vector>

namespace {
jint verdictBits(const twidget::GestureVerdict &v) {
    return jint(int(v.owner) | int(v.gesture) << 2 | (v.cancel ? 1 : 0) << 5 | (v.action ? int(*v.action) + 1 : 0) << 6 |
                (v.event ? int(*v.event) + 1 : 0) << 9 | (v.edge ? 1 : 0) << 11);
}
jint verdictBits(const std::optional<twidget::GestureVerdict> &v) { return v ? verdictBits(*v) : -1; }
twidget::GestureArbiter *arbiterOf(jlong h) { return reinterpret_cast<twidget::GestureArbiter *>(h); }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterCreate(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new twidget::GestureArbiter());
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterDestroy(JNIEnv *, jclass,
                                                                                                    jlong h) {
    delete arbiterOf(h);
}
extern "C" JNIEXPORT jint JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterDown(
    JNIEnv *, jclass, jlong h, jdouble t, jdouble x, jdouble y, jboolean claimX, jboolean claimY, jboolean hold,
    jboolean mouse, jint button) {
    twidget::WidgetGestureClaim claim;
    claim.x = claimX;
    claim.y = claimY;
    claim.hold = hold;
    return verdictBits(arbiterOf(h)->down(t, x, y, claim, mouse ? twidget::PointerKind::Mouse : twidget::PointerKind::Touch, button));
}
extern "C" JNIEXPORT jint JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterMove(JNIEnv *, jclass,
                                                                                                 jlong h, jdouble t,
                                                                                                 jdouble x, jdouble y) {
    return verdictBits(arbiterOf(h)->move(t, x, y));
}
extern "C" JNIEXPORT jint JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterTick(JNIEnv *, jclass,
                                                                                                 jlong h, jdouble t) {
    return verdictBits(arbiterOf(h)->tick(t));
}
extern "C" JNIEXPORT jint JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterUp(JNIEnv *, jclass,
                                                                                               jlong h, jdouble t,
                                                                                               jdouble x, jdouble y) {
    return verdictBits(arbiterOf(h)->up(t, x, y));
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterCancel(JNIEnv *, jclass,
                                                                                                   jlong h) {
    arbiterOf(h)->cancel();
}
extern "C" JNIEXPORT jdouble JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_arbiterHoldDeadline(JNIEnv *,
                                                                                                           jclass,
                                                                                                           jlong h) {
    auto deadline = arbiterOf(h)->holdDeadline();
    return deadline ? *deadline : -1;
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetClientNative_filterPixels(
    JNIEnv *e, jclass, jobject bitmap, jintArray kinds, jfloatArray amounts, jfloatArray xs, jfloatArray ys,
    jintArray colors, jfloat scale) {
    if (!bitmap || !kinds || !amounts || !xs || !ys || !colors)
        return;
    jsize n = e->GetArrayLength(kinds);
    if (n <= 0 || e->GetArrayLength(amounts) != n || e->GetArrayLength(xs) != n || e->GetArrayLength(ys) != n ||
        e->GetArrayLength(colors) != n)
        return;
    std::vector<jint> kind(n), color(n);
    std::vector<jfloat> amount(n), x(n), y(n);
    e->GetIntArrayRegion(kinds, 0, n, kind.data());
    e->GetIntArrayRegion(colors, 0, n, color.data());
    e->GetFloatArrayRegion(amounts, 0, n, amount.data());
    e->GetFloatArrayRegion(xs, 0, n, x.data());
    e->GetFloatArrayRegion(ys, 0, n, y.data());
    twlite::Filters steps;
    for (jsize k = 0; k < n; k++) {
        if (kind[k] < 0 || kind[k] > int(twlite::Filter::DropShadow))
            continue;
        twlite::Filter step;
        step.kind = twlite::Filter::Kind(kind[k]);
        step.amount = amount[k];
        step.x = x[k];
        step.y = y[k];
        auto argb = uint32_t(color[k]);
        step.color = twlite::Color{uint8_t(argb >> 16), uint8_t(argb >> 8), uint8_t(argb), uint8_t(argb >> 24)};
        steps.push_back(step);
    }
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (steps.empty() || AndroidBitmap_getInfo(e, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return;
    if (AndroidBitmap_lockPixels(e, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return;
    twlite::filter_pixels(static_cast<uint8_t *>(pixels), int(info.width), int(info.height), info.stride, false,
                          steps, scale);
    AndroidBitmap_unlockPixels(e, bitmap);
}
