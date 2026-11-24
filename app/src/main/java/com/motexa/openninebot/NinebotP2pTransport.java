package com.motexa.openninebot;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns only a verified target P2P group. An unrelated existing group is never removed. */
final class NinebotP2pTransport implements AutoCloseable {
    interface Listener {
        void event(String event, JSONObject fields);
        void ready(JSONObject facts);
        void received(byte[] bytes, boolean control);
        void failed(String reason);
    }
    private final Context context;
    private final Handler actor;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final ExecutorService receivers = Executors.newFixedThreadPool(2);
    private final BroadcastReceiver receiver;
    private volatile DatagramSocket media, control;
    private boolean registered, connecting, ready, ownedGroup, closed, channelClosed;
    private Runnable closedCallback;
    private final Runnable inspectRetry = this::inspectGroup;
    private final Runnable closeTimeout = this::closeChannel;
    private long generation, began, discoveryBegan = -1, discoveryGeneration, inspectionGeneration;
    private JSONObject lastInspection = new JSONObject();
    private long firstResultElapsedMs = -1;
    private int discoveryRequestCount, closingActions;
    private String targetName = "", targetAddress = "";

    NinebotP2pTransport(Context context, Handler actor, Listener listener) {
        this.context = context.getApplicationContext(); this.actor = actor; this.listener = listener;
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(this.context, actor.getLooper(), () -> {
            if (!closed) fail("p2p-system-channel-lost");
        });
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (closed) return;
                listener.event("ninebot_p2p_broadcast", NinebotTestProfile.json("action", intent.getAction()));
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(intent.getAction())) requestPeers();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) inspectGroup();
            }
        };
        if (manager != null && channel != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, null, actor, Context.RECEIVER_NOT_EXPORTED);
            else this.context.registerReceiver(receiver, filter, null, actor);
            registered = true;
        }
    }
    void discover() {
        requireService();
        requirePermissions();
        if (connecting || ready) throw new IllegalStateException("请先停止当前投屏，再重新发现仪表");
        discoveryBegan = SystemClock.elapsedRealtime(); discoveryRequestCount = 0; firstResultElapsedMs = -1;
        long token = ++discoveryGeneration;
        listener.event("ninebot_p2p_discovery_started", NinebotTestProfile.json("autoConnect", false,
                "filterPolicy", "complete_name_and_mac_only", "rssiAvailable", false,
                "rssiReason", "WifiP2pDevice_has_no_rssi"));
        try { manager.discoverPeers(channel, result("discover_peers", () -> actor.postDelayed(() -> {
            if (token == discoveryGeneration) requestPeers();
        }, 1000))); }
        catch (SecurityException revoked) { permissionLost(); }
    }
    private void requestPeers() {
        if (closed || connecting || ready || discoveryBegan < 0 || manager == null || channel == null) return;
        long token = discoveryGeneration;
        try { manager.requestPeers(channel, peers -> {
            if (closed || connecting || ready || token != discoveryGeneration) return;
            JSONArray rows = new JSONArray();
            int eligible = 0;
            for (WifiP2pDevice peer : peers.getDeviceList()) {
                boolean valid = isEligiblePeer(peer.deviceName, peer.deviceAddress);
                if (valid) eligible++;
                rows.put(NinebotTestProfile.json("name", peer.deviceName, "address", peer.deviceAddress,
                        "status", peer.status, "deviceType", peer.primaryDeviceType, "eligible", valid,
                        "filterReason", valid ? "" : "missing_complete_name_or_mac"));
            }
            int request = ++discoveryRequestCount;
            long resultElapsedMs = SystemClock.elapsedRealtime() - discoveryBegan;
            if (firstResultElapsedMs < 0) firstResultElapsedMs = resultElapsedMs;
            listener.event("ninebot_p2p_peers", NinebotTestProfile.json("peers", rows, "count", rows.length(),
                    "eligibleCount", eligible, "requestCount", request,
                    "elapsedMs", resultElapsedMs, "resultElapsedMs", resultElapsedMs,
                    "firstResultElapsedMs", firstResultElapsedMs,
                    "filterPolicy", "complete_name_and_mac_only", "rssiAvailable", false,
                    "rssiReason", "WifiP2pDevice_has_no_rssi"));
        }); } catch (SecurityException revoked) { permissionLost(); }
    }
    void connect(String name, String address) {
        requireService();
        requirePermissions();
        try {
        if (!isEligiblePeer(name, address))
            throw new IllegalArgumentException("请选择具有完整名称和 MAC 的九号 P2P 仪表");
        if (connecting || ready) throw new IllegalStateException("P2P 会话已在进行");
        targetName = name; targetAddress = address; connecting = true; began = SystemClock.elapsedRealtime();
        lastInspection = new JSONObject();
        long token = ++generation;
        manager.requestGroupInfo(channel, group -> {
            try {
            if (closed || token != generation || ready) return;
            if (group != null) {
                lastInspection = inspectionFacts(null, group, inspectionGeneration);
                if (!matches(group)) { fail("unrelated-p2p-group-preserved"); return; }
                inspectGroup();
                return;
            }
            WifiP2pConfig config = new WifiP2pConfig(); config.deviceAddress = targetAddress;
            config.wps.setup = WpsInfo.PBC; config.groupOwnerIntent = 0;
            ownedGroup = true;
            manager.connect(channel, config, result("connect", () -> {
                actor.removeCallbacks(inspectRetry); actor.postDelayed(inspectRetry, 500);
            }));
            } catch (SecurityException revoked) { permissionLost(); }
        });
        actor.postDelayed(() -> {
            if (!closed && token == generation && connecting) {
                fail("p2p-connection-timeout");
            }
        }, 30000);
        } catch (SecurityException revoked) { permissionLost(); }
    }
    private void inspectGroup() {
        if (closed || !connecting && !ready || manager == null || channel == null) return;
        actor.removeCallbacks(inspectRetry);
        long token = generation, query = ++inspectionGeneration;
        try { manager.requestConnectionInfo(channel, info -> {
            try {
            if (!currentInspection(token, query)) return;
            lastInspection = inspectionFacts(info, null, query);
            if (info == null || !info.groupFormed) {
                if (ready) fail("p2p-group-disconnected"); else retryInspection();
                return;
            }
            if (info.isGroupOwner) { fail("p2p-phone-is-group-owner"); return; }
            if (!(info.groupOwnerAddress instanceof Inet4Address)) {
                if (ready) fail("p2p-instrument-ipv4-unavailable"); else retryInspection();
                return;
            }
            manager.requestGroupInfo(channel, group -> {
                if (!currentInspection(token, query)) return;
                lastInspection = inspectionFacts(info, group, query);
                if (group == null) {
                    if (ready) fail("p2p-group-disconnected"); else retryInspection();
                    return;
                }
                if (!matches(group)) { fail("p2p-owner-or-name-mismatch"); return; }
                if (!ready) openSockets(group, info);
            });
            } catch (SecurityException revoked) { permissionLost(); }
        }); } catch (SecurityException revoked) { permissionLost(); }
    }
    private boolean currentInspection(long token, long query) {
        return !closed && token == generation && query == inspectionGeneration && (connecting || ready);
    }
    private void retryInspection() {
        if (!connecting || ready || closed) return;
        actor.removeCallbacks(inspectRetry); actor.postDelayed(inspectRetry, 500);
    }
    private JSONObject inspectionFacts(WifiP2pInfo info, WifiP2pGroup group, long query) {
        WifiP2pDevice owner = group == null ? null : group.getOwner();
        return NinebotTestProfile.json("connectionGeneration", generation, "queryGeneration", query,
                "elapsedMs", SystemClock.elapsedRealtime() - began,
                "infoAvailable", info != null, "groupFormed", info != null && info.groupFormed,
                "phoneIsGroupOwner", info != null && info.isGroupOwner,
                "ownerIp", info == null || info.groupOwnerAddress == null ? "" : info.groupOwnerAddress.getHostAddress(),
                "groupAvailable", group != null, "groupIsOwner", group != null && group.isGroupOwner(),
                "interface", group == null ? "" : group.getInterface(),
                "ownerName", owner == null ? "" : owner.deviceName,
                "ownerAddress", owner == null ? "" : owner.deviceAddress,
                "networkName", group == null ? "" : group.getNetworkName());
    }
    private void fail(String reason) {
        if (closed) return;
        listener.event("ninebot_p2p_failure", NinebotTestProfile.json("reason", reason,
                "connecting", connecting, "ready", ready, "connectionGeneration", generation,
                "queryGeneration", inspectionGeneration, "lastInspection", lastInspection));
        connecting = ready = false; generation++; inspectionGeneration++;
        actor.removeCallbacks(inspectRetry);
        listener.failed(reason);
    }
    private boolean matches(WifiP2pGroup group) {
        WifiP2pDevice owner = group.getOwner();
        return !group.isGroupOwner() && owner != null && targetAddress.equalsIgnoreCase(owner.deviceAddress)
                && (targetName.equals(owner.deviceName) || targetName.equals(group.getNetworkName()));
    }
    private void openSockets(WifiP2pGroup group, WifiP2pInfo info) {
        DatagramSocket nextMedia = null, nextControl = null;
        try {
            String iface = group.getInterface();
            InetAddress local = localAddress(iface);
            if (local == null) {
                lastInspection.put("localIpv4Ready", false);
                retryInspection(); return;
            }
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = null;
            if (connectivity != null) for (Network candidate : connectivity.getAllNetworks()) {
                LinkProperties properties = connectivity.getLinkProperties(candidate);
                if (properties != null && iface.equals(properties.getInterfaceName())) { network = candidate; break; }
            }
            nextMedia = socket(network, local, info.groupOwnerAddress, 5012);
            nextControl = socket(network, local, info.groupOwnerAddress, 5013);
            media = nextMedia; control = nextControl; ready = true; connecting = false;
            actor.removeCallbacks(inspectRetry);
            long token = generation;
            receive(media, false, token); receive(control, true, token);
            listener.ready(NinebotTestProfile.json("interface", iface, "local", local.getHostAddress(),
                    "owner", info.groupOwnerAddress.getHostAddress(), "ownerName", group.getOwner().deviceName,
                    "ownerAddress", group.getOwner().deviceAddress, "networkBound", network != null,
                    "elapsedMs", SystemClock.elapsedRealtime() - began,
                    "connectElapsedMs", SystemClock.elapsedRealtime() - began,
                    "inspection", lastInspection,
                    "firstResultElapsedMs", firstResultElapsedMs,
                    "discoveryToRouteMs", discoveryBegan < 0 ? -1 : SystemClock.elapsedRealtime() - discoveryBegan,
                    "frequencyMhz", Build.VERSION.SDK_INT >= 29 ? group.getFrequency() : JSONObject.NULL,
                    "filterPolicy", "complete_name_and_mac_only", "rssiAvailable", false,
                    "rssiReason", "WifiP2pDevice_has_no_rssi"));
        } catch (Exception error) {
            if (nextMedia != null) nextMedia.close(); if (nextControl != null) nextControl.close();
            fail("udp-open-" + error.getClass().getSimpleName());
        }
    }
    int send(byte[] bytes, boolean isControl) {
        DatagramSocket socket = isControl ? control : media;
        if (!ready || socket == null || bytes.length > 65507) return -1;
        try { socket.send(new DatagramPacket(bytes, bytes.length)); return 0; }
        catch (Exception error) { listener.event("ninebot_udp_send_failed", socketFailure(socket, isControl, error, generation)); return -2; }
    }
    private void receive(DatagramSocket socket, boolean isControl, long token) {
        receivers.execute(() -> {
            byte[] buffer = new byte[65535];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                    byte[] bytes = java.util.Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                    actor.post(() -> { if (!closed && token == generation && ready) listener.received(bytes, isControl); });
                } catch (SocketTimeoutException ignored) {}
                catch (Exception error) {
                    if (!socket.isClosed()) {
                        JSONObject facts = socketFailure(socket, isControl, error, token);
                        actor.post(() -> { if (!closed && token == generation) {
                            listener.event("ninebot_udp_receive_failed", facts);
                            fail("udp-receive-" + error.getClass().getSimpleName());
                        } });
                    }
                    return;
                }
            }
        });
    }
    private static JSONObject socketFailure(DatagramSocket socket, boolean isControl, Exception error, long token) {
        InetAddress local = socket.getLocalAddress(), remote = socket.getInetAddress();
        return NinebotTestProfile.json("control", isControl, "category", error.getClass().getSimpleName(),
                "error",socketErrorFields(error),
                "connectionGeneration", token,
                "localAddress", local == null ? "" : local.getHostAddress(),
                "localPort", socket.getLocalPort(),
                "remoteAddress", remote == null ? "" : remote.getHostAddress(),
                "remotePort", socket.getPort());
    }
    static JSONObject socketErrorFields(Exception error) {
        android.system.ErrnoException errno=null;Throwable cause=error;int depth=0;
        while(cause!=null&&depth++<8){if(cause instanceof android.system.ErrnoException){errno=(android.system.ErrnoException)cause;break;}cause=cause.getCause();}
        return NinebotTestProfile.json("category",error.getClass().getSimpleName(),"errno",errno==null?JSONObject.NULL:errno.errno,
            "errnoAvailable",errno!=null);
    }
    private DatagramSocket socket(Network network, InetAddress local, InetAddress owner, int port) throws Exception {
        DatagramSocket socket = new DatagramSocket(null);
        String operation="network_bind";
        try {
            if (network != null) network.bindSocket(socket);
            operation="local_bind";socket.bind(new InetSocketAddress(local, port));
            operation="peer_connect";socket.connect(owner, port);
            operation="socket_options";
            socket.setSoTimeout(1000); socket.setSendBufferSize(262144); return socket;
        } catch (Exception error) {
            listener.event("ninebot_udp_open_failed",NinebotTestProfile.json("operation",operation,
                "connectionGeneration",generation,"role",port==5013?"control":"media",
                "localAddress",local.getHostAddress(),"localPort",port,"remoteAddress",owner.getHostAddress(),
                "remotePort",port,"networkBound",network!=null,"error",socketErrorFields(error)));
            socket.close(); throw error;
        }
    }
    private static InetAddress localAddress(String iface) throws Exception {
        if (iface == null || iface.isEmpty()) return null;
        NetworkInterface network = NetworkInterface.getByName(iface);
        if (network == null) return null;
        Enumeration<InetAddress> addresses = network.getInetAddresses();
        for (InetAddress address : Collections.list(addresses)) if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address;
        return null;
    }
    private WifiP2pManager.ActionListener result(String operation, Runnable done) {
        long token = generation;
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                if (closed || token != generation) return;
                listener.event("ninebot_p2p_operation", NinebotTestProfile.json("operation", operation, "status", 0, "success", true));
                if (done != null) done.run();
            }
            @Override public void onFailure(int reason) {
                if (closed || token != generation) return;
                listener.event("ninebot_p2p_operation", NinebotTestProfile.json("operation", operation, "status", reason, "success", false));
                if (operation.equals("connect")) fail("p2p-connect-system-" + reason);
            }
        };
    }
    private void requireService() {
        if (closed || manager == null || channel == null) throw new IllegalStateException("Wi-Fi Direct 不可用");
    }
    private void requirePermissions() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("请先允许附近 Wi-Fi 设备权限");
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("请先允许精确位置信息权限");
    }
    private void permissionLost() { if (!closed) fail("p2p-permission-revoked"); }
    static boolean isEligiblePeer(String name, String address) {
        return peerRejectionReason(name, address, "", "").isEmpty();
    }
    static String peerRejectionReason(String name, String address, String expectedName, String expectedAddress) {
        if (name == null || name.trim().isEmpty() || address == null
                || !address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return "missing_complete_name_or_mac";
        if (expectedName != null && !expectedName.isEmpty() && !expectedName.equals(name)) return "configured_name_mismatch";
        if (expectedAddress != null && !expectedAddress.isEmpty() && !expectedAddress.equalsIgnoreCase(address)) return "configured_address_mismatch";
        return "";
    }
    boolean active() { return connecting || ready; }
    boolean ready() { return ready; }
    @Override public void close() { close(null); }
    void close(Runnable completed) {
        if (channelClosed) { if (completed != null) completed.run(); return; }
        if (completed != null) {
            Runnable previous = closedCallback;
            closedCallback = previous == null ? completed : () -> { previous.run(); completed.run(); };
        }
        if (closed) return; closed = true; generation++; inspectionGeneration++; connecting = false; ready = false;
        actor.removeCallbacks(inspectRetry);
        if (media != null) media.close(); if (control != null) control.close(); media = control = null;
        receivers.shutdownNow();
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
        if (manager != null && channel != null) {
            // Android callbacks complete before the controller may start another session.
            // A dead system channel must not leave the UI waiting forever.
            actor.postDelayed(closeTimeout, 5000);
            try {
                closingActions = 2;
                manager.stopPeerDiscovery(channel, closingResult(this::closingActionCompleted));
                manager.cancelConnect(channel, closingResult(this::closingActionCompleted));
            } catch (SecurityException revoked) { closeChannel(); }
        } else closeChannel();
    }
    private void closingActionCompleted() {
        if (--closingActions == 0) removeOwnedGroup();
    }
    private WifiP2pManager.ActionListener closingResult(Runnable next) {
        return new WifiP2pManager.ActionListener() {
            private boolean completed;
            private void finish() { if (!completed && !channelClosed) { completed = true; next.run(); } }
            @Override public void onSuccess() { finish(); }
            @Override public void onFailure(int reason) { finish(); }
        };
    }
    private void removeOwnedGroup() {
        if (channelClosed) return;
        try {
            if (ownedGroup) manager.requestGroupInfo(channel, group -> {
                if (channelClosed) return;
                try {
                if (group != null && matches(group)) manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { closeChannel(); }
                    @Override public void onFailure(int reason) { closeChannel(); }
                });
                else closeChannel();
                } catch (SecurityException revoked) { closeChannel(); }
            });
            else closeChannel();
        } catch (SecurityException revoked) { closeChannel(); }
    }
    private void closeChannel() {
        if (channelClosed) return; channelClosed = true;
        actor.removeCallbacks(closeTimeout);
        try { if (Build.VERSION.SDK_INT >= 27 && channel != null) channel.close(); }
        finally { Runnable callback = closedCallback; closedCallback = null; if (callback != null) callback.run(); }
    }
}
