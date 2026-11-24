package com.motexa.openninebot;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import com.motexa.vehiclecast.NinebotCredentialProvider;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.shadows.ShadowWifiP2pManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows={NinebotP2pTransportTest.ControlledP2pManager.class, NinebotP2pTransportTest.ControlledChannel.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class NinebotP2pTransportTest {
    private static final String NAME = "DIRECT-test", MAC = "d4:a0:fb:34:5a:44";
    private Context context;
    private ControlledP2pManager manager;
    private NinebotP2pTransport transport;
    private final List<String> failures = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<JSONObject> facts = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES);
        manager = Shadow.extract(context.getSystemService(Context.WIFI_P2P_SERVICE));
        transport = new NinebotP2pTransport(context, new Handler(Looper.getMainLooper()), new NinebotP2pTransport.Listener() {
            @Override public void event(String name, JSONObject fields) { events.add(name); facts.add(fields); }
            @Override public void ready(JSONObject fields) { fail("No real network sockets should be opened in this test"); }
            @Override public void received(byte[] bytes, boolean control) { fail("Unexpected datagram"); }
            @Override public void failed(String reason) { failures.add(reason); }
        });
    }

    @After public void tearDown() {
        transport.close();
        advance(5000);
    }

    private void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void start() {
        transport.connect(NAME, MAC);
        manager.groups.remove(0).onGroupInfoAvailable(null);
        manager.connectResult.onSuccess();
        advance(500);
    }
    private WifiP2pInfo info(boolean formed, boolean owner, String ip) throws Exception {
        WifiP2pInfo value = new WifiP2pInfo();
        value.groupFormed = formed; value.isGroupOwner = owner;
        value.groupOwnerAddress = ip == null ? null : InetAddress.getByName(ip);
        return value;
    }
    private WifiP2pGroup group(String mac) {
        WifiP2pGroup value = new WifiP2pGroup();
        WifiP2pDevice owner = new WifiP2pDevice(); owner.deviceName = NAME; owner.deviceAddress = mac;
        ReflectionHelpers.setField(value, "mOwner", owner);
        ReflectionHelpers.setField(value, "mIsGroupOwner", false);
        shadowOf(value).setNetworkName(NAME);
        shadowOf(value).setInterface("missing-p2p-test-interface");
        return value;
    }
    private void broadcastConnectionChanged() {
        context.sendBroadcast(new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION));
        advance(0);
    }

    @Test public void waitsForConnectionGroupAndLocalIpv4WithinOriginalTimeout() throws Exception {
        start();
        manager.infos.remove(0).onConnectionInfoAvailable(null);
        advance(499); assertTrue(manager.infos.isEmpty()); assertTrue(failures.isEmpty());
        advance(1);
        manager.infos.remove(0).onConnectionInfoAvailable(info(false, false, null));
        advance(500);
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, false, null));
        advance(500);
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, false, "192.168.1.1"));
        manager.groups.remove(0).onGroupInfoAvailable(null);
        advance(500);
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, false, "192.168.1.1"));
        manager.groups.remove(0).onGroupInfoAvailable(group(MAC));
        assertTrue(failures.isEmpty()); assertTrue(transport.active());
        advance(500); assertEquals(1, manager.infos.size());
        advance(27500);
        assertEquals(List.of("p2p-connection-timeout"), failures);
        assertFalse(transport.active());
        JSONObject snapshot = facts.get(events.indexOf("ninebot_p2p_failure")).optJSONObject("lastInspection");
        assertEquals("missing-p2p-test-interface", snapshot.optString("interface"));
        assertFalse(snapshot.optBoolean("localIpv4Ready", true));
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, true, "192.168.1.1"));
        assertEquals(1, failures.size());
    }

    @Test public void ignoresOlderConnectionAndGroupCallbacksAfterANewerQuery() throws Exception {
        start();
        WifiP2pManager.ConnectionInfoListener oldInfo = manager.infos.remove(0);
        broadcastConnectionChanged();
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, false, "192.168.1.1"));
        WifiP2pManager.GroupInfoListener oldGroup = manager.groups.remove(0);
        broadcastConnectionChanged();
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, false, "192.168.1.1"));
        manager.groups.remove(0).onGroupInfoAvailable(group(MAC));
        oldInfo.onConnectionInfoAvailable(info(true, true, "192.168.1.1"));
        oldGroup.onGroupInfoAvailable(group("01:23:45:67:89:ab"));
        assertTrue(failures.isEmpty()); assertTrue(transport.active());
        advance(500); assertEquals(1, manager.infos.size());
    }

    @Test public void rejectsActualPhoneGroupOwner() throws Exception {
        start();
        manager.infos.remove(0).onConnectionInfoAvailable(info(true, true, "192.168.1.1"));
        assertEquals(List.of("p2p-phone-is-group-owner"), failures);
        assertFalse(transport.active());
        assertTrue(facts.get(events.indexOf("ninebot_p2p_operation")).optBoolean("success"));
    }

    @Test public void logsSystemErrorZeroAsFailureInsteadOfSuccess() {
        transport.connect(NAME, MAC);
        manager.groups.remove(0).onGroupInfoAvailable(null);
        manager.connectResult.onFailure(WifiP2pManager.ERROR);
        assertEquals(List.of("p2p-connect-system-0"), failures);
        JSONObject operation = facts.get(events.indexOf("ninebot_p2p_operation"));
        assertEquals(0, operation.optInt("status")); assertFalse(operation.optBoolean("success", true));
    }

    @Test public void closeWaitsForCancellationAndRemovalBeforeAllowingAnotherSession() {
        start();
        int[] closed = {0};
        transport.close(() -> closed[0]++);
        assertEquals(0, closed[0]); assertNotNull(manager.cancelResult);
        manager.stopResult.onSuccess(); assertEquals(0, closed[0]);
        manager.cancelResult.onSuccess(); assertEquals(0, closed[0]);
        manager.groups.remove(0).onGroupInfoAvailable(group(MAC));
        assertNotNull(manager.removeResult); assertEquals(0, closed[0]);
        manager.removeResult.onSuccess(); assertEquals(1, closed[0]);
        advance(5000); assertEquals(1, closed[0]);
    }

    @Test public void closeIgnoresLateCallbacksAfterDeadline() {
        start();
        int[] closed = {0}; transport.close(() -> closed[0]++);
        manager.stopResult.onSuccess(); manager.cancelResult.onSuccess();
        WifiP2pManager.GroupInfoListener late = manager.groups.remove(0);
        advance(5000); assertEquals(1, closed[0]);
        late.onGroupInfoAvailable(group(MAC));
        assertNull(manager.removeResult); assertEquals(1, closed[0]);
    }

    @Test public void repeatedCloseKeepsCompletionAndCleanupFailuresStillFinish() {
        start();
        int[] closed = {0}; transport.close(() -> closed[0]++); transport.close();
        manager.cancelResult.onFailure(WifiP2pManager.ERROR);
        manager.cancelResult.onSuccess(); // A duplicate callback must not finish both operations.
        assertTrue(manager.groups.isEmpty()); assertEquals(0, closed[0]);
        manager.stopResult.onFailure(WifiP2pManager.ERROR);
        manager.groups.remove(0).onGroupInfoAvailable(group("01:23:45:67:89:ab"));
        assertNull(manager.removeResult); assertEquals(1, closed[0]);
    }

    @Test public void anUnrelatedExistingGroupIsNeverRemovedByOwnershipLogic() {
        transport.connect(NAME, MAC);
        manager.groups.remove(0).onGroupInfoAvailable(group("01:23:45:67:89:ab"));
        assertEquals(List.of("unrelated-p2p-group-preserved"), failures);
        transport.close(); manager.stopResult.onSuccess(); manager.cancelResult.onSuccess();
        assertTrue(manager.groups.isEmpty()); assertNull(manager.removeResult);
    }

    @Test public void lossAfterReadyFailsInsteadOfWaitingForANewConnection() throws Exception {
        start(); ReflectionHelpers.setField(transport, "connecting", false); ReflectionHelpers.setField(transport, "ready", true);
        manager.infos.remove(0).onConnectionInfoAvailable(info(false, false, null));
        assertEquals(List.of("p2p-group-disconnected"), failures);
        advance(500); assertTrue(manager.infos.isEmpty());
    }

    @Test public void controllerShutdownWaitsForAnAlreadyPendingP2pRelease() {
        NinebotTestController.Host host = new NinebotTestController.Host() {
            @Override public boolean authorized() { return false; }
            @Override public void log(String event, JSONObject fields) {}
            @Override public void trace(String channel, byte[] bytes, JSONObject fields) {}
            @Override public void changed() {}
            @Override public void requestFrameSource(NinebotTestController.FrameMode mode, JSONObject config) {}
            @Override public void stopFrameSource(String reason) {}
        };
        NinebotCredentialProvider store = new NinebotCredentialProvider() {
            @Override public JSONObject load(String id) { return null; }
            @Override public void save(String id, JSONObject material) {}
            @Override public void forget(String id) {}
        };
        NinebotTestController controller = new NinebotTestController(context, host, store, material -> null);
        Handler actor = ReflectionHelpers.getField(controller, "actor");
        actor.post(() -> {
            ReflectionHelpers.callInstanceMethod(controller, "ensureWifi");
            ReflectionHelpers.callInstanceMethod(controller, "disconnectInternal", ClassParameter.from(String.class, "test_release"));
        });
        shadowOf(actor.getLooper()).idle();
        controller.close(); shadowOf(actor.getLooper()).idle();
        assertTrue(actor.post(() -> {}));
        manager.stopResult.onSuccess(); manager.cancelResult.onSuccess();
        assertFalse(actor.post(() -> {}));
    }

    @Implements(WifiP2pManager.class)
    public static class ControlledP2pManager extends ShadowWifiP2pManager {
        final List<WifiP2pManager.GroupInfoListener> groups = new ArrayList<>();
        final List<WifiP2pManager.ConnectionInfoListener> infos = new ArrayList<>();
        WifiP2pManager.ActionListener connectResult, stopResult, cancelResult, removeResult;
        @Implementation @Override protected void requestGroupInfo(WifiP2pManager.Channel channel, WifiP2pManager.GroupInfoListener listener) { groups.add(listener); }
        @Implementation protected void requestConnectionInfo(WifiP2pManager.Channel channel, WifiP2pManager.ConnectionInfoListener listener) { infos.add(listener); }
        @Implementation protected void connect(WifiP2pManager.Channel channel, WifiP2pConfig config, WifiP2pManager.ActionListener listener) { connectResult = listener; }
        @Implementation protected void stopPeerDiscovery(WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) { stopResult = listener; }
        @Implementation protected void cancelConnect(WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) { cancelResult = listener; }
        @Implementation @Override protected void removeGroup(WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) { removeResult = listener; }
    }

    // Robolectric initializes a channel without the Android AsyncChannel service.
    @Implements(WifiP2pManager.Channel.class)
    public static class ControlledChannel {
        @Implementation protected void close() {}
    }
}
