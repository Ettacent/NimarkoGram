package org.telegram.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.tgnet.SerializedData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.nimarkogram.messenger.wsbypass.ProxyApplier;

import static org.junit.Assert.*;




@RunWith(AndroidJUnit4.class)
public class ProxyMigrationTest {
    private SharedPreferences prefs;
    private Class<?> snapshotClass;
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        prefs = context.getSharedPreferences("proxy-migration-test", Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().commit());
        snapshotClass = Class.forName(ProxyApplier.class.getName() + "$ProxySnapshot");
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private Object readSnapshot() throws Exception {
        return method(ProxyApplier.class, "readSnapshot", SharedPreferences.class).invoke(null, prefs);
    }

    private Object field(Object snapshot, String name) throws Exception {
        Field field = snapshotClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(snapshot);
    }

    private void saveSnapshot(ProxySettings settings, boolean enabled, boolean calls) throws Exception {
        Constructor<?> constructor = snapshotClass.getDeclaredConstructor(boolean.class, ProxySettings.class, boolean.class);
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance(enabled, settings, calls);
        SharedPreferences.Editor editor = prefs.edit();
        method(ProxyApplier.class, "writeSnapshot", SharedPreferences.Editor.class, snapshotClass)
                .invoke(null, editor, snapshot);
        assertTrue(editor.commit());
    }

    private static ProxySettings proxy(ProxySettings.Type type) {
        return ProxySettings.builder().setType(type).setAddress("relay.example.org")
                .setPort(443).setUser("alice").setPassword("password").setSecret(SECRET).build();
    }

    @Test
    public void snapshotsRoundTripAllModesIncludingDisabledManualWeb() throws Exception {
        ArrayList<ProxySettings> modes = new ArrayList<>();
        modes.add(ProxySettings.EMPTY);
        for (ProxySettings.Type type : ProxySettings.Type.values()) modes.add(proxy(type));
        for (ProxySettings settings : modes) {
            for (boolean enabled : new boolean[]{false, true}) {
                saveSnapshot(settings, enabled, true);

                Object restored = readSnapshot();
                assertNotNull(restored);
                assertEquals(settings, field(restored, "settings"));
                assertEquals(enabled, field(restored, "enabled"));
                assertEquals(true, field(restored, "callsEnabled"));
                ProxySettings saved = (ProxySettings) field(restored, "settings");
                SharedPreferences.Editor editor = prefs.edit();
                saved.toSharedPreferences(editor);
                assertTrue(editor.commit());
                assertEquals(settings, ProxySettings.fromSharedPreferences(prefs));
                if (settings.getType() == ProxySettings.Type.WEB) {
                    assertEquals(0, saved.getPort());
                    assertTrue(saved.isValid());
                    assertEquals(settings.getLink(), saved.getLink());
                }
            }
        }
    }

    @Test
    public void legacyAndV2ListEntriesMigrateAndV3PreservesWeb() throws Exception {
        Method read = method(SharedConfig.ProxyInfo.class, "fromSerializedData",
                int.class, InputSerializedData.class);
        Method write = method(SharedConfig.ProxyInfo.class, "toSerializedData", OutputSerializedData.class);
        for (int version : new int[]{0, 2, 3}) {
            for (ProxySettings.Type type : ProxySettings.Type.values()) {
                if (version < 3 && type == ProxySettings.Type.WEB) continue;
                ProxySettings settings = proxy(type);
                SerializedData payload = new SerializedData();
                payload.writeString(settings.getAddress());
                payload.writeInt32(settings.getPort());
                payload.writeString(settings.getUser());
                payload.writeString(settings.getPassword());
                payload.writeString(settings.getSecret());
                if (version >= 2) {
                    payload.writeInt64(123);
                    payload.writeInt64(456);
                }
                if (version >= 3) payload.writeInt32(ProxySettings.typeToInt(type));
                SerializedData input = new SerializedData(payload.toByteArray());
                SharedConfig.ProxyInfo info = (SharedConfig.ProxyInfo) read.invoke(null, version, input);
                assertEquals(settings, info.settings);
                assertEquals(version >= 2 ? 123L : 0L, info.ping);
                assertEquals(version >= 2 ? 456L : 0L, info.availableCheckTime);

                SerializedData upgraded = new SerializedData();
                write.invoke(info, upgraded);
                SerializedData reloadedInput = new SerializedData(upgraded.toByteArray());
                SharedConfig.ProxyInfo reloaded = (SharedConfig.ProxyInfo) read.invoke(null, 3, reloadedInput);
                assertEquals(settings, reloaded.settings);
                assertEquals(info.ping, reloaded.ping);
                payload.cleanup();
                input.cleanup();
                upgraded.cleanup();
                reloadedInput.cleanup();
            }
        }
    }

    @Test
    public void legacySnapshotsInferTypeWithoutUsingLiveType() throws Exception {
        for (String secret : new String[]{"", SECRET}) {
            assertTrue(prefs.edit().clear().putBoolean("tgws_proxy_snap_present", true)
                    .putBoolean("tgws_proxy_snap_enabled", true)
                    .putString("tgws_proxy_snap_host", "old.example.org")
                    .putInt("tgws_proxy_snap_port", 1080)
                    .putString("tgws_proxy_snap_secret", secret)
                    .putString("tgws_proxy_snap_user", "alice")
                    .putString("tgws_proxy_snap_pass", "password")
                    .putInt("proxy_type", 2).commit());
            ProxySettings settings = (ProxySettings) field(readSnapshot(), "settings");
            assertEquals(secret.isEmpty() ? ProxySettings.Type.SOCKS5 : ProxySettings.Type.MTPROTO,
                    settings.getType());
            assertEquals(secret, settings.getSecret());
            assertEquals("old.example.org", settings.getAddress());
            if (secret.isEmpty()) assertEquals("alice", settings.getUser());
        }
    }

    @Test
    public void absentAndClearedSnapshotsStayAbsent() throws Exception {
        assertNull(readSnapshot());
        saveSnapshot(proxy(ProxySettings.Type.WEB), true, false);
        SharedPreferences.Editor editor = prefs.edit();
        method(ProxyApplier.class, "writeSnapshot", SharedPreferences.Editor.class, snapshotClass)
                .invoke(null, editor, null);
        assertTrue(editor.commit());
        assertNull(readSnapshot());
        assertFalse(prefs.contains("tgws_proxy_snap_type"));
        assertFalse(prefs.contains("tgws_proxy_snap_secret"));
    }

    private ProxySettings local() throws Exception {
        return (ProxySettings) method(ProxyApplier.class, "localSettings", String.class, int.class, String.class)
                .invoke(null, "127.0.0.1", 10888, SECRET);
    }

    private boolean verified(boolean enabled, ProxySettings expected, SharedConfig.ProxyInfo current,
                             ArrayList<SharedConfig.ProxyInfo> list) throws Exception {
        return (Boolean) method(ProxyApplier.class, "isApplyVerified", boolean.class, ProxySettings.class,
                SharedPreferences.class, SharedConfig.ProxyInfo.class, ArrayList.class)
                .invoke(null, enabled, expected, prefs, current, list);
    }

    @Test
    public void bypassReplacesStaleTypesAndVerifiesTypedIdentity() throws Exception {
        ProxySettings local = local();
        assertEquals(ProxySettings.Type.MTPROTO, local.getType());
        SharedConfig.ProxyInfo current = new SharedConfig.ProxyInfo(local);
        ArrayList<SharedConfig.ProxyInfo> list = new ArrayList<>(Collections.singletonList(current));
        for (int staleType : new int[]{0, 2}) {
            SharedPreferences.Editor editor = prefs.edit();
            local.toSharedPreferences(editor);
            assertTrue(editor.putBoolean("proxy_enabled", true).putInt("proxy_type", staleType).commit());
            assertFalse(verified(true, local, current, list));
            editor = prefs.edit();
            local.toSharedPreferences(editor);
            assertTrue(editor.commit());
            assertTrue(verified(true, local, current, list));
            assertEquals(SECRET, ProxySettings.fromSharedPreferences(prefs).getSecret());
        }
        assertFalse(verified(true, local, current, new ArrayList<>()));
        assertFalse(verified(true, local, new SharedConfig.ProxyInfo(proxy(ProxySettings.Type.WEB)), list));
    }

    @Test
    public void restoredWebAndDisabledManualProxiesVerifyWithoutPositivePortGate() throws Exception {
        for (ProxySettings.Type type : ProxySettings.Type.values()) {
            ProxySettings settings = proxy(type);
            SharedConfig.ProxyInfo current = new SharedConfig.ProxyInfo(settings);
            ArrayList<SharedConfig.ProxyInfo> list = new ArrayList<>(Collections.singletonList(current));
            for (boolean enabled : new boolean[]{true, false}) {
                SharedPreferences.Editor editor = prefs.edit();
                settings.toSharedPreferences(editor);
                assertTrue(editor.putBoolean("proxy_enabled", enabled).commit());
                assertTrue(verified(enabled, settings, current, list));
            }
        }
        SharedPreferences.Editor editor = prefs.edit();
        ProxySettings.EMPTY.toSharedPreferences(editor);
        assertTrue(editor.putBoolean("proxy_enabled", false).commit());
        assertTrue(verified(false, ProxySettings.EMPTY, null, new ArrayList<>()));
    }

    @Test
    public void legacyOverloadsExistAndNormalizeNullsAndSecretType() throws Exception {
        assertNotNull(ConnectionsManager.class.getMethod("setProxySettings", boolean.class,
                String.class, int.class, String.class, String.class, String.class));
        assertNotNull(ConnectionsManager.class.getMethod("checkProxy", String.class, int.class,
                String.class, String.class, String.class, RequestTimeDelegate.class));
        Method legacy = method(ConnectionsManager.class, "legacyProxySettings", String.class, int.class,
                String.class, String.class, String.class);
        ProxySettings empty = (ProxySettings) legacy.invoke(null, null, 1080, null, null, null);
        assertFalse(empty.isValid());
        assertEquals(ProxySettings.Type.SOCKS5, empty.getType());
        assertEquals("", empty.getUser());
        ProxySettings mtproto = (ProxySettings) legacy.invoke(null, "127.0.0.1", 10888, null, null, SECRET);
        assertEquals(local(), mtproto);
    }

    @Test
    public void webCheckFailureCompletesCallbacksAndAdvancesQueueWithoutNativeCheck() throws Exception {

        ProxySettings invalidWeb = ProxySettings.builder().setType(ProxySettings.Type.WEB)
                .setAddress("127.0.0.1").setSecret(SECRET).build();
        assertTrue(invalidWeb.isValid());
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger nativeChecks = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            WebProxyConnectionTester.getInstance().checkProxy(invalidWeb, time -> {
                if (time == -1) failures.incrementAndGet();
                completed.countDown();
            }, (settings, port, callback) -> nativeChecks.incrementAndGet());
        }
        assertTrue("WEB callback queue stalled", completed.await(5, TimeUnit.SECONDS));
        assertEquals(2, failures.get());
        assertEquals(0, nativeChecks.get());
    }
}
