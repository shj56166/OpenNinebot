package com.motexa.openninebot;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import com.motexa.vehiclecast.CastEngine;
import com.motexa.vehiclecast.NinebotCredentialProvider;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = {"com.motexa.vehiclecast"}, shadows = {
        NinebotCastStartOrderTest.ProtocolPeer.class,
        NinebotP2pTransportTest.ControlledP2pManager.class,
        NinebotP2pTransportTest.ControlledChannel.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class NinebotCastStartOrderTest {
    private NinebotTestController controller;
    private Handler actor;
    private CastEngine engine;
    private NinebotP2pTransportTest.ControlledP2pManager manager;
    private final List<String> sources = new ArrayList<>();

    @Before public void setUp() throws Exception {
        ProtocolPeer.commands.clear();
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES);
        manager = Shadow.extract(context.getSystemService(Context.WIFI_P2P_SERVICE));
        JSONObject material = new JSONObject().put("schemaVersion", 1).put("authVersion", 2)
                .put("profileId", "encryption2_standard_nus").put("modelId", "14360")
                .put("transport", "udp").put("credentials", new JSONObject()
                        .put("passwordHex", "00112233445566778899aabbccddeeff"));
        NinebotCredentialProvider store = new NinebotCredentialProvider() {
            public JSONObject load(String id) { return material; }
            public void save(String id, JSONObject value) {}
            public void forget(String id) {}
        };
        NinebotTestController.Host host = new NinebotTestController.Host() {
            public boolean authorized() { return true; }
            public void log(String event, JSONObject fields) {}
            public void trace(String channel, byte[] bytes, JSONObject fields) {}
            public void changed() {}
            public void requestFrameSource(NinebotTestController.FrameMode mode, JSONObject config) { sources.add(mode.name()); }
            public void stopFrameSource(String reason) {}
        };
        controller = new NinebotTestController(context, host, store, value -> null);
        actor = ReflectionHelpers.getField(controller, "actor");
        idle();
        engine = new CastEngine(new JSONObject(), new JSONObject());
        ReflectionHelpers.setField(controller, "engine", engine);
        ReflectionHelpers.setField(controller, "authenticated", true);
        ReflectionHelpers.setField(controller, "prepared", true);
        ReflectionHelpers.setField(controller, "nativeStage", "authenticated");
        ReflectionHelpers.setField(controller, "stage", "authenticated");
        ReflectionHelpers.setField(controller, "configuration", new JSONObject().put("channel", 1)
                .put("codec", 1).put("width", 848).put("height", 480).put("fps", 20).put("bitrate", 2000000));
    }

    @After public void tearDown() {
        // Keep close() from quitting the worker before idle() can enqueue its drain barrier.
        shadowOf(actor.getLooper()).pause();
        controller.close(); idle();
        if (manager.stopResult != null) manager.stopResult.onSuccess();
        if (manager.cancelResult != null) manager.cancelResult.onSuccess();
        if (!manager.groups.isEmpty()) manager.groups.remove(0).onGroupInfoAvailable(null);
    }

    private void idle() { shadowOf(actor.getLooper()).idle(); }
    private void modeReply() {
        actor.post(() -> ReflectionHelpers.callInstanceMethod(controller, "actions",
                ClassParameter.from(JSONArray.class, engine.onControl(new byte[] {2}))));
        idle();
    }

    @Test public void opensOverBleBeforeDiscoveryAndStartsFramesOnlyAfterVerifiedRoute() {
        controller.discoverWifi(); idle();
        assertNull(ReflectionHelpers.getField(controller, "wifi"));
        controller.openCastMode(); idle();
        assertEquals(List.of("start_cast"), ProtocolPeer.commands);
        assertNull(ReflectionHelpers.getField(controller, "wifi"));
        assertTrue(sources.isEmpty());
        modeReply();
        assertTrue(sources.isEmpty());
        controller.discoverWifi(); idle();
        NinebotP2pTransport wifi = ReflectionHelpers.getField(controller, "wifi");
        assertNotNull(wifi);
        assertNull(manager.connectResult);
        ReflectionHelpers.setField(controller, "targetP2pName", "DIRECT-test");
        ReflectionHelpers.setField(controller, "targetP2pAddress", "d4:a0:fb:34:5a:44");
        controller.startCasting(NinebotTestController.FrameMode.TEST_PATTERN); idle();
        assertEquals(List.of("start_cast"), ProtocolPeer.commands);
        assertTrue(sources.isEmpty());
        manager.groups.remove(0).onGroupInfoAvailable(null);
        assertNotNull(manager.connectResult);
        // Supply the verified-route boundary; no real sockets or radio are used.
        ReflectionHelpers.setField(wifi, "ready", true);
        NinebotP2pTransport.Listener listener = ReflectionHelpers.getField(wifi, "listener");
        actor.post(() -> listener.ready(new JSONObject())); idle();
        assertEquals(List.of("start_cast", "start_wifi_media"), ProtocolPeer.commands);
        assertEquals(List.of("TEST_PATTERN"), sources);
    }

    @Test public void repeatedOpenDoesNotSendAnotherModeCommand() {
        controller.openCastMode(); idle(); modeReply();
        controller.openCastMode(); idle();
        assertEquals(List.of("start_cast"), ProtocolPeer.commands);
        assertTrue(sources.isEmpty());
    }

    @Test public void aStoppedStartCannotReviveFramesFromLateModeReply() {
        ReflectionHelpers.setField(controller, "targetP2pName", "DIRECT-test");
        ReflectionHelpers.setField(controller, "targetP2pAddress", "d4:a0:fb:34:5a:44");
        controller.startCasting(NinebotTestController.FrameMode.TEST_PATTERN); idle();
        controller.stopCasting(); idle(); modeReply();
        assertEquals(List.of("start_cast", "stop_cast"), ProtocolPeer.commands);
        assertTrue(sources.isEmpty());
        assertNull(ReflectionHelpers.getField(controller, "wifi"));
    }

    /** Models native state notifications; wire and crypto are tested by the production C++ suite. */
    @Implements(className = "com.motexa.vehiclecast.NativeCast", isInAndroidSdk = false)
    public static class ProtocolPeer {
        static final List<String> commands = new ArrayList<>();
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static long create(String options, String credentials) { return 1; }
        @Implementation protected static void destroy(long handle) {}
        @Implementation protected static String dispatch(long handle, String operation, String arguments,
                byte[] bytes, boolean flag, long value, long elapsed, long wall) throws Exception {
            String state;
            if (operation.equals("command")) {
                String command = new JSONObject(arguments).getString("name"); commands.add(command);
                state = command.equals("start_wifi_media") ? "casting_ready" : "wait_mode";
            } else if (operation.equals("control")) state = "cast_mode_ready";
            else return "[]";
            return new JSONArray().put(new JSONObject().put("type", "state").put("state", state)
                    .put("authenticated", true).put("prepared", true)).toString();
        }
    }
}
