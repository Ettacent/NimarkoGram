## NimarkoGram: mvel2 references javax.script (not on Android). Don't warn.
-dontwarn javax.script.**
-dontwarn org.mvel2.jsr223.**
## NimarkoGram: keep MVEL — reflectively constructed expressions
-keep class org.mvel2.** { *; }
-dontwarn org.mvel2.**
## Keep ported extera classes (synthetic R8-style names with reflection callers).
-keep class app.nimarkogram.messenger.** { *; }
-keep class okhttp3.internal.url._UrlKt { *; }
## NimarkoGram: keep the Xposed bridge — invoked exclusively from Python via
## Chaquopy reflection, so R8/D8 will otherwise inline/elide it. Without this
## hooks silently no-op (no NimarkoBanner draw, no profile decoration, etc).
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class de.robv.android.xposed.** { *; }
## NimarkoGram: keep Pine ART hook entry points — JNI-reachable and reflection
## targets from Java/Python.
-keep class top.canyie.pine.** { *; }
-keepclassmembers class top.canyie.pine.** { *; }
## NimarkoGram: keep Chaquopy bridge surface used by python entry points.
-keep class com.chaquo.python.** { *; }

## v10.5 RESTORE BROAD KEEP: the v10.4 "surgical" approach (only 5 hand-picked
## classes from internal NG plugins) saved ~32 KB but **broke every third-party
## plugin** that imports the exteraGram-compat shim layer — the shim classes
## themselves had no Java caller, so R8 happily obfuscated/stripped their static
## methods. Symptom: plugins crash with
##   AttributeError: type object 'VibratorUtils' has no attribute 'vibrate'
## (in_app_notifications.plugin reported by user 2026-06-14) and similar for
## AppUtils.getGson, ExteraConfig.inAppVibration, ChatUtils.* — methods exist in
## source but the names/signatures got rewritten by minifier.
##
## The whole point of `com/exteragram/**` in this repo is to be a stable plugin-
## facing API surface. Stripping it defeats the purpose. The 32 KB regression is
## a fair price for plugin compatibility. Plugins shipped by 3rd-party devs
## still expect the legacy paths — we cannot ask every author to rewrite their
## imports overnight.
-keep class com.exteragram.** { *; }
-keepclassmembers class com.exteragram.** { *; }

## Material Components widgets are reached ONLY via Python jclass reflection by exteraGram
## plugins (no Java caller), so R8 strips them in release without this keep.
-keep class com.google.android.material.progressindicator.** { *; }


-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
# Plugin compatibility: Python plugins (Chaquopy) import and Pine-hook ARBITRARY org.telegram classes by
# their ORIGINAL names (e.g. `from org.telegram.tgnet.tl import TL_account`, hook `ChatActivityEnterView`).
# R8 can't see these reflection-style uses, so without a broad keep it strips/renames classes a plugin needs
# (nimarkoprivacy broke with "No module named 'org'" after R8 minified away org.telegram.tgnet.tl.TL_account
# once 12.9.0 dropped its last Java reference). Keep the whole tree un-shrunk and un-renamed so practically
# every plugin's imports/hooks resolve. (Subsumes the specific tgnet keeps above; kept for clarity.)
-keep class org.telegram.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.muxer.MediaMuxerCompat { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
-dontoptimize
-dontobfuscate

# Plugin reflection robustness: Chaquopy/Pine resolve nested classes (TLRPC$TL_message), read generic
# signatures and annotations at runtime. Preserve the attributes reflection needs so any plugin importing a
# nested TL type / reading annotations resolves correctly.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# NG: StringConcatFactory absent on Android <26; Kotlin 1.9 + Java 17 target
# emits invokeDynamic for string templates. Desugaring covers runtime; -dontwarn
# silences R8 missing-class check.
-dontwarn java.lang.invoke.StringConcatFactory

# NG: Jackson 2.x's Java7SupportImpl references java.beans.* classes that the
# Android runtime does not ship. The class is reflectively loaded behind a
# Java-version probe, so the references are dead on Android; silence R8.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# NG: jlatexmath-android — XML-driven font config and reflective class loading.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
