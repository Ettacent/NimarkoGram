# Host-side stability checks

Run from the repository root with Node.js and a JDK (`java` and `javac` on PATH):

```sh
node TMessagesProj/src/test/scripts/stability-regression.js
```

The runner executes 13 suites sequentially, with a two-minute limit per suite.
It does not build an APK, access a device or publish changes.

Coverage includes preview rotation and cleanup, round-video return, folder tab
selection and forwarding-picker isolation, rich-message viewport geometry,
composer timing, media glow, reactions, quote widths and profile entry.
Tests combine source assertions with Java harnesses that extract production
methods and substitute Android dependencies. Negative controls in selected
suites verify that representative regressions fail.

These are targeted logic checks, not Android UI integration tests. They cannot
verify actual FPS, GPU composition, video decoding, pixel appearance or every
lifecycle interaction. Those still require a device or suitable emulator.
