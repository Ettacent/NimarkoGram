# LG-off frosted blur: real HWUI regression

This standalone, offline-built test APK compiles the **current production**
`DownscaleScrollableNoiseSuppressor`, capture/hash interfaces, color effects and
rectangle merger. Android graphics classes are real. Only unrelated application
configuration, density, hash accumulation and annotations are stubbed. No client
APK, Telegram account, network dependency, Gradle download or client logging is
needed. No generated APK, key, PNG or log belongs in the repository.

## Run

Start an Android API 31+ emulator with graphics acceleration, then:

```sh
ANDROID_SDK_ROOT="$HOME/android-sdk" \
  bash tests/emulator/frosted-blur/run.sh emulator-5580
```

Requires an installed JDK, `zip`, `tar`, SDK platform 36 and build-tools 35.0.0.
Override `COMPILE_API`, `BUILD_TOOLS_VERSION`, and `TEST_TIMEOUT_SECONDS` as needed.
No AVD is created automatically. The runner installs/replaces **only**
`dev.nimarko.blurregression`, force-stops that test package and leaves it installed.
It never clears global logcat or changes emulator settings. A debug signing key
is generated locally/offline in `FROSTED_TEST_KEY_DIR` (default
`${XDG_CACHE_HOME:-/tmp}/ng-frosted-regression-key`) and reused. It is public-purpose
test material, not a release signing identity. Build/results use a fresh temporary
directory printed by the runner; source hashes and device properties accompany
the report. A concurrent production edit during compilation fails the run.

## What must pass

- A forced **single-pass negative control**, using the same production capture
  and phase graph at density 3, must exceed RGB delta 3. If not, the run fails as
  non-discriminating rather than blessing an untested rendering backend.
- Production LG-off rendering must keep every frame-to-frame RGB channel delta
  **<= 3** in a kernel-guarded central ROI.
- Densities **2.625, 3, 4**; crop sizes **1088x704 and 1088x624**.
- Transparent colored bands, the same bands plus thin white lines, and bands
  over an opaque gray background. These are procedural, deterministic inputs.
- Positive and negative 1px scrolls repeat modulus-8 crossings. Fractional
  +0.5/-0.75/+0.5 sequences cross both signed zero and modulus boundaries and
  reverse direction. Both simple and non-simple frosted graphs are exercised
  (the full matrix uses the default simple graph; alternate graph uses density 3).
- Every readback waits for presentation; failures/timeouts are fatal. The runner
  exits nonzero for assertion failures, exceptions, control failure or timeout.

`report.txt` includes every comparison, actual phase, wrap markers, ROI, maximum
and mean deltas. Full-size frames 7/8/9 for the density-3 positive sequence and the
first failing frame of each case are retained. The guard is at least 224px and
otherwise `ceil(3 * sigma_full + 16)`: this test deliberately isolates interior
resampling from phase-induced crop transparency. It does **not** certify crop
edges, exact equality to one large Gaussian, LG-on behavior or a physical GPU.

## Why this detects the regression

At density 3, 40dp blur on the explicit 1/8 grid has sigma 8.72275. AOSP Skia
downsamples above sigma 4; the internal grid is not covered by our modulus-8
phase correction. On the original Android 36 emulator reproduction, frame 8
changed the central region by mean 2.346/max 7, versus ordinary mean ~0.22/max 1.
Five successive sigma 3.90093 passes (Android radius 5.89059) reduced frame 8 to
mean 0.224/max 2 in the original measurement ROI. Five Gaussian variances sum to
the intended variance. Saturation is applied once, not once per pass.

Native references (not a claim about every OEM implementation):

- [Android 12/API 31 threshold](https://android.googlesource.com/platform/external/skia/+/refs/heads/android12-release/src/core/SkGpuBlurUtils.h)
- [Android 12 downsampling and integer size rounding](https://android.googlesource.com/platform/external/skia/+/refs/heads/android12-release/src/core/SkGpuBlurUtils.cpp)
- [Android 16 shader threshold](https://android.googlesource.com/platform/external/skia/+/refs/heads/android16-release/src/core/SkBlurEngine.h)
- [Android 16 rescaling](https://android.googlesource.com/platform/external/skia/+/refs/heads/android16-release/src/core/SkImageFilterTypes.cpp)

## Density, borders and performance

Use effective sigma **after** the existing radius/downscale conversion, with
`N = max(1, ceil(max(sigmaX,sigmaY)^2 / 16))`, and divide both sigmas by sqrt(N).
For this 1/8, 40dp case, densities 2.625/3/4 require 4/5/9 passes. Do not silently
cap N and increase each pass above sigma 4: that reintroduces the resampling path.
Extremely high overridden density needs an explicitly tested policy, not a
weaker blur or changed scale hidden in this correction. A tiny below-4 safety
margin may avoid floating-point threshold crossings; check the converted-back
sigma if testing threshold-exact inputs.

Finite kernels and repeated CLAMP are not pixel-identical to one ideal Gaussian,
especially near crop boundaries. Compare against an oversized reference before
claiming complete-kernel/edge correctness. Do not repeatedly truncate intermediate
passes to the visible output ROI. This suite preserves the production capture
rectangles and excludes edges from its continuity assertion.

`renderAndReadbackMeanMs` includes synchronization and bitmap readback; it is a
diagnostic, **not** an app frame-time benchmark. More passes mean more GPU work.
Measure actual UI frame time, allocation and thermal behavior separately on
physical devices; emulator software graphics timings are not production targets.

## Initial production-helper run (Android 36 emulator)

The default/simple matrix passed all **3,168 comparisons**, with maximum channel
delta 2; the forced single-pass control reached 7. The additional non-simple
1088x624 case failed the <=3 assertion at fractional reversal frames 43, 54 and
64 (maximum 4). The 1088x704 non-simple case passed with maximum 3. Thus the full
suite correctly exited **1**, with 3 failures among 3,360 positive comparisons
and 320 wrap events. Do not describe this as an unqualified suite pass or relax
the threshold silently. At inspection time no production constructor call used
`simple=false`; the affected ChatActivity uses the passing default graph.

The original run artifacts were retained at
`/tmp/ng-frosted-regression.4iCiOs/`, including source hashes, report, failing PNG
and device properties. This temporary path is a local investigation artifact,
not a prerequisite for rerunning the checked-in harness.
