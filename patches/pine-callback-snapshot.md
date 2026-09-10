# Pine callback snapshot

Apply `pine-callback-snapshot.patch` after the existing `pine-nimarkogram.patch`
when rebuilding Pine. The bundled `TMessagesProj/libs/pine-core-16kb.jar`
includes this fix; only `top/canyie/pine/Pine.class` changed. Native libraries
and existing receiver/argument guards are unchanged.

Dispatch now checks and uses the same synchronized callback snapshot. Removing
the last callback cannot leave the dispatcher indexing an empty array after
an earlier nonempty check. Callbacks execute outside the registry lock.

Run the packaged-JAR regression on Linux with Node.js, JDK 17 and GCC:

```sh
node TMessagesProj/src/test/scripts/pine-callback-snapshot-regression.js
```

An optional argument pointing to the previous JAR enables the negative control:
the old dispatcher must reproduce `ArrayIndexOutOfBoundsException`.
The test stubs Android logging and the ART metadata synchronization JNI call;
it does not substitute the Java dispatcher. Device lifecycle/ART testing remains
separate from this host regression.
