# Isolated Android image-compositing reference

Run from this repository:

```sh
bash tests/emulator/image-compositing/run.sh emulator-5582
```

This builds/installs only `dev.nimarko.imagecompositing`, a tiny standalone test
APK using installed SDK tools. It does not compile, install, or import Telegram,
ImageReceiver, or AnimatedEmojiDrawable. It leaves the emulator running and
retains the test APK, source hashes, device properties, SurfaceFlinger dump,
report, and readback PNGs in the printed `/tmp/ng-image-compositing.*` directory.
Requires a booted API 31+ device; default compile SDK 36/build tools 35.0.0.

## Reference and oracle

The passing candidate uses a transparent isolated blend layer: old raster at
`1-t`, incoming raster at `t` in its own layer, incoming layer restored with
`PorterDuff.Mode.ADD`, then completed blend restored using ordinary SRC_OVER.
An outer layer applies parent opacity exactly once. Background is outside both
layers, never an ADD operand.

For each backend and geometry, independently render each source at full weight
onto transparent pixels. A CPU oracle interpolates those premultiplied RGBA
endpoints, multiplies by parent opacity, then composites over the background:

```text
M = parent * ((1-t) * old + t * new)    // all four premultiplied channels
out = M + background * (1 - M.alpha)
```

`Bitmap.getPixels` returns straight colors; the oracle/readbacks repremultiply
them before comparison. The fixed 3/255 maximum per-channel tolerance covers
8-bit raster/layer quantization and this round trip, including low-alpha pixels.
No edge ROI is excluded; every pixel, including transparent surroundings, is
checked. Reported edge pixels include partially transparent silhouette interiors.
The endpoint rasters are backend-specific: this validates blending consistency,
not equality of software and hardware shape rasterizers.

Coverage: four scenes (identical white, different opaque colors, shifted colored
transparent silhouettes with alpha ramp, 40%-visible white preview to opaque
white); integer and fractional translate/scale; rounded clip; nine weights
including 0/1/127/128/254/255; parent alpha 64/153/255; transparent, black, white,
and colored backgrounds. White-scene center sequences additionally reject a
downward temporal step greater than 2/255. Other frames are checked against the
oracle, not assumed to increase in luminance when source colors differ.

Software uses a real Android bitmap Canvas. HWUI asserts
`RecordingCanvas.isHardwareAccelerated()` and uses HardwareRenderer/RenderNode
to an RGBA ImageReader, followed by HardwareBuffer readback. It is not a JVM
graphics stub or an opaque screenshot. A deferred `SYNC_FRAME_DROPPED` request
is consumed before the next scene is recorded; other nonzero statuses fail.

Four negative controls must differ from the same oracle by more than 8/255:
weighted SRC_OVER (darkening), unfaded old underlay (excess alpha/color), ADD
without background isolation (brightening), and missing preview. They are tested
on every scene/backend and retained as PNGs. The top-level result fails if a
control is not detected or any layer-restore candidate exceeds 3/255.

A separate **direct bitmap ADD characterization** runs the same 1,728 cases.
Its failures are explicitly reported and are NOT covered by the reference PASS.
This matters: choosing ADD on bitmap Paint versus restoring a rasterized layer
can take different bitmap sampling paths under fractional transforms.

## Recorded API 36 emulator result (2026-09-21)

AVD `ng_image_transition_check`, serial `emulator-5582`, Android 16/API 36,
HWUI `skiagl`, OpenGL ES emulator translator backed by Google SwiftShader.
This executes Android's hardware Canvas pipeline, but not physical GPU hardware.

Layer-restore reference: 1,728 comparisons; 3,520 total readback frames including
direct characterization, endpoints and controls; zero failures. All 32 negative
controls detected. Software max error 2.3807/255 (alpha 1.7843), HWUI max
2.4816/255 (alpha 1.3137); full-image errors include rounded/transparent edges.

Direct bitmap ADD: software fails 87/864 comparisons, max 10.2353/255, including
a full-new fractional-scale endpoint differing by 10/255. HWUI passes 864/864
on this emulator, max 2.4816/255. The discrepancy is consistent with a different
software sampling path; this harness does not prove the internal Skia cause.
Do not treat the two implementation strategies as interchangeable on software
Canvas. Test the actual production integration separately.

Initial direct-only exploration: `/tmp/ng-image-compositing.eXzvEN` (also exposed
a deferred HWUI frame). Expanded successful run: `/tmp/ng-image-compositing.I6LOAN`.
Subsequent reruns print their own artifact directory; hashes bind each build to
its source. Temporary artifact paths are local and not durable CI storage.

## Limits / handoff to ImageReceiver owner

Regression discriminator: scene 0 transitions an opaque white raster to the
identical raster. There is no source-color or sharpness change to explain a
brightness dip. Weighting both with `1-t` / `t` and drawing them sequentially
with SRC_OVER produces alpha `1-t*(1-t)` rather than 1: approximately 0.75 at
midpoint, reproduced as a 64/255 discrepancy on both backends. The ADD reference
preserves the constant endpoint coverage within quantization. This is an
invariant test, not a proposal to hide the symptom using a longer fade. The
production owner must still show whether recent common-render edits introduced
that exact draw order/weighting, or a different alpha/reset regression, and run
the failing case against production before claiming root cause.

No production implementation is tested. This does not establish that the
reported photo flicker, emoji appearance, or badge animation is fixed. It does
not test request replacement, decoder timing, drawables with multiple passes,
ImageReceiver alpha clocks, animations, HDR/wide gamut, performance, or vendor
GPU behavior. Results apply to 8-bit sRGB synthetic rasters on this emulator.
No client APK was built. No direct inter-agent messaging tool was available;
this report is the integration handoff.

After the last raster run, the nested loop was flattened without changing its
iteration order/semantics, and shell output was expanded to include direct-ADD
characterization. Final Java source compiles with javac and `bash -n` passes.
No additional APK was built after the user's subsequent no-APK-build instruction.

API contracts: [PorterDuff ADD](https://developer.android.com/reference/android/graphics/PorterDuff.Mode#ADD)
defines saturated premultiplied-channel addition;
[HardwareRenderer](https://developer.android.com/reference/android/graphics/HardwareRenderer#SYNC_FRAME_DROPPED)
documents deferred frame delivery. The empirical results above come from this
harness, not those API descriptions.
