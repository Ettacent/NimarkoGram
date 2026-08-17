# ARM64 Python wheels

These wheels preserve the Python 3.11 and ARMv7 compatibility of the
application while making every ARM64 ELF load segment compatible with Android
devices using 16 KB memory pages.

They were rebuilt from the matching Chaquopy package recipes with Android NDK
27.3.13750724 and `-Wl,-z,max-page-size=16384`. Their wheel build numbers are
higher than the public Chaquopy builds, so pip selects them only for ARM64;
the official ARMv7 wheels remain in use for `armeabi-v7a`.

All bundled `.so` files were verified with `readelf -lW`: every `PT_LOAD`
segment has `p_align` equal to `0x4000`.
