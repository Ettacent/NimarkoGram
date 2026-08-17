import pathlib
import struct
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]

def load_segment_alignments(path):
    data = path.read_bytes()
    if data[:4] != b'\x7fELF':
        raise AssertionError(f'{path} is not ELF')
    endian = '<' if data[5] == 1 else '>'
    elf_class = data[4]
    if elf_class == 2:
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        phentsize = struct.unpack_from(endian + 'H', data, 54)[0]
        phnum = struct.unpack_from(endian + 'H', data, 56)[0]
        align_offset, align_format = 48, 'Q'
    elif elf_class == 1:
        phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        phentsize = struct.unpack_from(endian + 'H', data, 42)[0]
        phnum = struct.unpack_from(endian + 'H', data, 44)[0]
        align_offset, align_format = 28, 'I'
    else:
        raise AssertionError(f'{path} has unsupported ELF class {elf_class}')

    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        program_type = struct.unpack_from(endian + 'I', data, offset)[0]
        if program_type == 1:  
            alignments.append(struct.unpack_from(
                endian + align_format, data, offset + align_offset)[0])
    return alignments

class Android16PluginRuntimeTest(unittest.TestCase):
    def test_runtime_is_fail_closed_and_exercises_real_hook_shapes(self):
        loader = (REPO / 'TMessagesProj/src/main/java/org/telegram/'
                  'messenger/ApplicationLoader.java').read_text()
        self.assertIn('NG_PINE_MAX_TESTED_SDK = 36', loader)
        self.assertIn('Build.FINGERPRINT', loader)
        self.assertIn('PackageManager.MATCH_APEX', loader)
        self.assertIn('PineRuntimeProbe', loader)
        self.assertIn('mixedProbe', loader)
        self.assertIn('getDeclaredConstructor(int.class)', loader)
        self.assertIn('PineConfig.disableHiddenApiPolicy = false', loader)
        self.assertIn(
            'PineConfig.disableHiddenApiPolicyForPlatformDomain = false',
            loader)
        self.assertIn('Pine.HookMode.REPLACEMENT', loader)
        self.assertIn('Pine.getHookMode()', loader)

    def test_no_forced_oem_art_jit_or_native_hidden_api_patch(self):
        java_root = REPO / 'TMessagesProj/src/main/java'
        sources = '\n'.join(
            path.read_text(errors='replace')
            for path in java_root.rglob('*.java'))
        self.assertNotIn('top.canyie.pine.Pine.compile(', sources)
        self.assertNotIn(
            'top.canyie.pine.Pine.disableHiddenApiPolicy(', sources)

    def test_current_hidden_api_bypass_is_used(self):
        gradle = (REPO / 'TMessagesProj/build.gradle').read_text()
        self.assertIn(
            'org.lsposed.hiddenapibypass:hiddenapibypass:6.1', gradle)

    def test_bundled_pine_load_segments_are_16kb_aligned(self):
        for abi in ('arm64-v8a', 'armeabi-v7a'):
            binary = REPO / 'TMessagesProj/jni' / abi / 'libpine.so'
            alignments = load_segment_alignments(binary)
            self.assertTrue(alignments, f'no PT_LOAD segments in {binary}')
            self.assertGreaterEqual(
                min(alignments), 16 * 1024,
                f'{binary} is not 16 KB page compatible: {alignments}')

    def test_built_arm64_native_payload_is_16kb_aligned(self):
        
        roots = (
            REPO / 'TMessagesProj/build/intermediates/merged_native_libs/'
                   'standalone/mergeStandaloneNativeLibs/out/lib/arm64-v8a',
            REPO / 'TMessagesProj/build/python/pip/standalone/arm64-v8a',
            REPO / 'TMessagesProj/build/python/assets/misc/standalone/'
                   'chaquopy/bootstrap-native/arm64-v8a',
        )
        binaries = sorted(
            binary
            for root in roots if root.is_dir()
            for binary in root.rglob('*.so')
        )
        if not binaries:
            self.skipTest('Gradle native/Python payload has not been generated')
        for binary in binaries:
            alignments = load_segment_alignments(binary)
            self.assertTrue(alignments, f'no PT_LOAD segments in {binary}')
            self.assertGreaterEqual(
                min(alignments), 16 * 1024,
                f'{binary} is not 16 KB page compatible: {alignments}')

if __name__ == '__main__':
    unittest.main()
