package com.motexa.openninebot;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import com.motexa.vehiclecast.CastEngine;
import com.motexa.vehiclecast.NinebotCredentialProvider;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-owned Android transport actor. Protocol decisions and secret state remain in CastEngine. */
public final class NinebotTestController implements AutoCloseable {
    public enum FrameMode { MIRROR, TEST_PATTERN }
    public interface Host {
        boolean authorized();
        void log(String event, JSONObject fields);
        void trace(String channel, byte[] bytes, JSONObject fields);
        void changed();
        void requestFrameSource(FrameMode mode, JSONObject config);
        void stopFrameSource(String reason);
        default void stopFrameSource(long sourceGeneration, String reason) { stopFrameSource(reason); }
    }
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID MEDIA_SERVICE = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    private static final UUID MEDIA_WRITE = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    private static final UUID MEDIA_NOTIFY = UUID.fromString("6e400004-0000-0000-006e-696e65626f74");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Host host;
    private final NinebotCredentialProvider store;
    private final NinebotCredentialProvider.AuthorizationFactory authorizationFactory;
    private final HandlerThread thread = new HandlerThread("NinebotCastActor");
    private final Handler actor;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();
    private final ArrayDeque<Tx> writes = new ArrayDeque<>();
    private final AtomicBoolean frameQueued = new AtomicBoolean();
    private volatile JSONObject publicSnapshot = NinebotTestProfile.json("stage", "idle", "detail", "未开始");
    private volatile boolean closed;
    private NinebotTestProfile profile, savedProfile;
    private String workflowId = "";
    private Candidate selected;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controlWrite, controlNotify, mediaWrite, mediaNotify;
    private BluetoothGattDescriptor subscribing;
    private CastEngine engine;
    private NinebotP2pTransport wifi;
    private boolean wifiClosing, discoverAfterWifiClose;
    private NinebotCredentialProvider.Authorization provider;
    private Tx pending;
    private JSONObject configuration;
    private JSONObject wifiDiscovery = new JSONObject();
    private final JSONObject fields = new JSONObject();
    private JSONArray wifiPeers = new JSONArray();
    private String stage = "idle", nativeStage = "idle", detail = "未开始", lastError = "", targetP2pName = "", targetP2pAddress = "";
    private FrameMode desiredSource;
    private volatile long generation, sourceGeneration;
    private long wifiGeneration;
    private long queuedBytes, droppedFrames, lastTickAt;
    private int mtu = 23;
    private boolean scanning, mtuPending, servicesReady, authenticated, prepared, prepareAutomatically, publishPending;
    private volatile boolean sourceRequested;

    public NinebotTestController(Context context, Host host) {
        this(context, host, new NinebotCredentialStore(context), defaultAuthorizationFactory(context));
    }
    public NinebotTestController(Context context, Host host, NinebotCredentialProvider store,
            NinebotCredentialProvider.AuthorizationFactory authorizationFactory) {
        this.context = context.getApplicationContext(); this.host = host; this.store = java.util.Objects.requireNonNull(store);
        this.authorizationFactory = java.util.Objects.requireNonNull(authorizationFactory);
        thread.start(); actor = new Handler(thread.getLooper());
        actor.post(() -> {
            try { JSONObject saved = store.load(NinebotCredentialProvider.ACTIVE_VEHICLE); savedProfile = profile = saved == null ? null : NinebotTestProfile.parse(saved.toString()); if (profile != null) { targetP2pName = profile.p2pName; targetP2pAddress = profile.p2pAddress; } }
            catch (RuntimeException error) { detail = error.getMessage(); host.log("ninebot_material_restore_failed", json("category", error.getClass().getSimpleName())); }
            publish(); actor.post(tick);
        });
    }
    private static NinebotCredentialProvider.AuthorizationFactory defaultAuthorizationFactory(Context context) {
        Context application = context.getApplicationContext();
        return material -> new AuthorizedNinebotProvider(application, material);
    }
    public JSONObject snapshot() {
        try { return new JSONObject(publicSnapshot.toString()); }
        catch (JSONException impossible) { return new JSONObject(); }
    }
    /** Token for the current frame source. Each restart in the same BLE session gets a new token. */
    public long generation() { return sourceGeneration; }
    public boolean acceptsFrameSource(long sessionGeneration) { return !closed && sourceRequested && sourceGeneration == sessionGeneration; }
    void configureTest(LabTarget target, boolean officialWifi) {
        if (!target.ninebot()) throw new IllegalArgumentException("请选择九号功能");
        boolean direct = target.wifi() && !officialWifi;
        String requested = target.id + (direct ? ":wifi" : ":bluetooth");
        run(() -> {
            if (workflowId.equals(requested)) return;
            disconnectInternal("test_workflow_changed");
            workflowId = requested;
            wifiDiscovery = new JSONObject();
            profile = direct ? NinebotTestProfile.wifiPreset(target == LabTarget.NINEBOT_SEVEN) : savedProfile;
            selected = null; wifiPeers = new JSONArray(); fieldsClear();
            targetP2pName = profile == null ? "" : profile.p2pName;
            targetP2pAddress = profile == null ? "" : profile.p2pAddress;
            host.log("ninebot_test_workflow", json("workflowId", workflowId, "vehicleReadsEnabled", !target.casting,
                    "wireProfile", direct ? "pro" : "official", "requiresBluetoothMaterial", !direct));
            report(direct ? "wifi_ready" : "idle", direct ? "仪表开启投屏后，发现并选择 Wi-Fi 仪表；无需车辆蓝牙资料" : "请按当前连接步骤连接车辆");
        });
    }
    public void importProfile(String text) {
        NinebotTestProfile parsed = NinebotTestProfile.parse(text);
        if (parsed.authVersion == 0) throw new IllegalArgumentException("Wi-Fi 直连无需导入车辆资料，请选择 Wi-Fi 直连入口");
        run(() -> {
            requireIdle(); saveProfile(parsed); profile = parsed; selected = null; configuration = null;
            wifiPeers = new JSONArray(); wifiDiscovery = new JSONObject();
            targetP2pName = profile.p2pName; targetP2pAddress = profile.p2pAddress;
            host.log("ninebot_material_imported", profile.summary()); report("material_saved", "九号资料已加密保存");
        });
    }
    public void clearProfile() {
        run(() -> { requireIdle(); store.forget(NinebotCredentialProvider.ACTIVE_VEHICLE); savedProfile = profile = null; selected = null; configuration = null;
            wifiPeers = new JSONArray(); wifiDiscovery = new JSONObject();
            targetP2pName = targetP2pAddress = ""; host.log("ninebot_material_cleared", new JSONObject()); report("idle", "九号资料已清除"); });
    }
    /** User-triggered material refresh only; this does not claim a vehicle handshake succeeded. */
    public void refreshAuthorizedMaterials() {
        run(() -> {
            requireIdle();
            if (profile == null || profile.authVersion != 3) throw new IllegalStateException("请先导入带授权接口的 Gen3 材料");
            NinebotTestProfile requested = profile;
            NinebotCredentialProvider.Authorization active = authorizationFactory.open(requested.credentials().optJSONObject("v3"));
            provider = active;
            String requestId = java.util.UUID.randomUUID().toString();
            host.log("ninebot_provider_request", json("operation", "refresh", "requestId", requestId));
            report("material_refreshing", "正在从已配置的授权服务刷新材料");
            active.execute("refresh", requestId, new JSONObject(), new NinebotCredentialProvider.Callback() {
                @Override public void completed(JSONObject result) {
                    actor.post(() -> {
                        if (closed || provider != active || profile != requested) return;
                        provider = null; active.close();
                        if (!host.authorized()) { report("authorization_expired", "连接服务不可用，未保存返回材料"); return; }
                        try {
                            NinebotTestProfile updated = requested.withAuthorizedRefresh(result);
                            saveProfile(updated); profile = updated;
                            host.log("ninebot_provider_result", json("operation", "refresh", "requestId", requestId, "saved", true));
                            report("material_saved", "授权材料已校验并加密保存；车辆连接尚未验证");
                        } catch (RuntimeException invalid) {
                            host.log("ninebot_provider_result", json("operation", "refresh", "requestId", requestId, "saved", false, "category", invalid.getClass().getSimpleName()));
                            report("material_refresh_failed", "返回材料校验或保存失败，原材料保留");
                        }
                    });
                }
                @Override public void failed(String code) {
                    actor.post(() -> {
                        if (closed || provider != active || profile != requested) return;
                        provider = null; active.close();
                        host.log("ninebot_provider_result", json("operation", "refresh", "requestId", requestId, "saved", false, "code", code));
                        report("material_refresh_failed", code);
                    });
                }
            });
        });
    }
    public void scan() { LabRuntime.get(context).requireStart();
        run(() -> {
            requireIdle(); requireBluetoothPermissions();
            try {
            stopScan(); candidates.clear(); selected = null;
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("请开启手机蓝牙");
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new IllegalStateException("蓝牙扫描器不可用");
            ScanSettings.Builder settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
            if (Build.VERSION.SDK_INT >= 26) settings.setLegacy(false);
            scanning = true; scanner.startScan(null, settings.build(), scanCallback);
            report("scanning", "扫描九号原始广播，15 秒后停止");
            long token = generation; actor.postDelayed(() -> { if (generation == token && scanning) { stopScan(); report("scan_complete", "请选择蓝牙目标"); } }, 15000);
            } catch (SecurityException revoked) { permissionLost(); }
        });
    }
    public void selectDevice(String address) {
        run(() -> {
            requireIdle(); Candidate candidate = candidates.get(address);
            if (candidate == null) throw new IllegalArgumentException("扫描结果已失效，请重新扫描");
            selected = candidate; stopScan();
            host.log("ninebot_ble_selected", candidate.json()); report("device_selected", "已选择 " + candidate.name);
        });
    }
    public void openCastMode() { LabRuntime.get(context).requireStart();
        run(() -> {
            requireEngine();
            if (profile == null || profile.authVersion == 0 || configuration == null
                    || !profile.transport.equals(transport(configuration.optInt("channel", -1)))
                    || profile.transport.equals("ble")) throw new IllegalStateException("请先连接车辆，并读取匹配的 Wi-Fi 投屏配置");
            if (nativeStage.equals("cast_mode_ready") || nativeStage.equals("casting_ready")) return;
            actions(engine.command("start_cast", new JSONObject()));
        });
    }
    public void discoverWifi() { LabRuntime.get(context).requireStart(); run(() -> {
        if (profile != null && profile.authVersion != 0 && !nativeStage.equals("cast_mode_ready") && !nativeStage.equals("casting_ready"))
            throw new IllegalStateException("请先通过蓝牙开启仪表投屏，并等待仪表应答");
        if (!ensureWifi()) { discoverAfterWifiClose = true; report(stage, "等待上一 P2P 会话释放后发现仪表"); return; }
        wifi.discover(); report(stage, "正在发现 Wi-Fi Direct 仪表");
    }); }
    public void selectWifi(String name, String address) {
        run(() -> {
            if (wifi != null && wifi.active()) throw new IllegalStateException("请先断开当前投屏");
            boolean exists = false;
            for (int i = 0; i < wifiPeers.length(); i++) {
                JSONObject row = wifiPeers.optJSONObject(i);
                if (row != null && name.equals(row.optString("name")) && address.equalsIgnoreCase(row.optString("address"))) exists = true;
            }
            if (!exists) throw new IllegalArgumentException("P2P 目标不在当前发现列表");
            String expectedName = profile == null ? "" : profile.p2pName;
            String expectedAddress = profile == null ? "" : profile.p2pAddress;
            String rejection = NinebotP2pTransport.peerRejectionReason(name, address, expectedName, expectedAddress);
            if (!rejection.isEmpty()) {
                host.log("ninebot_p2p_selection_rejected", json("name", name, "address", address, "reason", rejection));
                throw new IllegalArgumentException("选择的车辆 Wi-Fi 缺少完整名称/MAC，或与资料目标不一致");
            }
            targetP2pName = name; targetP2pAddress = address;
            host.log("ninebot_p2p_selected", json("name", name, "address", address,
                    "filterPolicy", expectedName.isEmpty() && expectedAddress.isEmpty() ? "complete_name_and_mac_only" : "complete_name_mac_and_configured_target",
                    "configuredTarget", !expectedName.isEmpty() || !expectedAddress.isEmpty(), "autoConnect", false)); publish();
        });
    }
    public void connectAndPrepare() { LabRuntime.get(context).requireStart(); run(() -> { prepareAutomatically = true; connectInternal(); }); }
    public void connectForRead() { LabRuntime.get(context).requireStart(); run(() -> { prepareAutomatically = false; connectInternal(); }); }
    public void readCastConfiguration() { run(() -> {
        requireEngine();
        if (nativeStage.equals("cast_mode_ready") || nativeStage.equals("casting_ready") || nativeStage.equals("wait_mode"))
            throw new IllegalStateException("请先停止仪表投屏，再重新读取配置");
        configuration = null; actions(engine.command("read_cast_config", new JSONObject()));
    }); }
    public void startCasting(FrameMode source) { LabRuntime.get(context).requireStart();
        run(() -> {
            if (workflowId.equals(LabTarget.NINEBOT_BLUETOOTH.id + ":bluetooth")) throw new IllegalStateException("车辆信息页面不启动投屏，请先切换到投屏功能");
            if (profile == null) throw new IllegalStateException("请先导入九号专用 JSON 资料");
            if (sourceRequested || desiredSource != null) throw new IllegalStateException("请先停止当前投屏");
            desiredSource = source; prepareAutomatically = true;
            try {
                if (engine == null) connectInternal();
                else if (authenticated || profile.authVersion == 0) {
                    if (configuration == null && authenticated) actions(engine.command("read_cast_config", new JSONObject()));
                    else prepareMedia();
                } else throw new IllegalStateException("请等待本次认证完成");
            } catch (RuntimeException | LinkageError error) { desiredSource = null; throw error; }
        });
    }
    public void readVehicle(boolean continuous, boolean bms, int bmsTarget) { LabRuntime.get(context).requireStart();
        run(() -> { if (!workflowId.equals(LabTarget.NINEBOT_BLUETOOTH.id + ":bluetooth")) throw new IllegalStateException("请切换到九号蓝牙与车辆信息页面");
            requireEngine(); actions(engine.command("read_vehicle", json("continuous", continuous, "bms", bms,
                "bmsTarget", bmsTarget == 0x22 ? 0x22 : 0x07))); });
    }
    public void stopReading() { run(() -> { requireEngine(); actions(engine.command("stop_read", new JSONObject())); }); }
    public void stopCasting() {
        run(() -> {
            desiredSource = null; invalidateFrameSource("user_stop");
            if (engine != null) actions(engine.command("stop_cast", new JSONObject()));
            else report("stopped", "投屏已停止");
        });
    }
    public void disconnect() { postTransport(() -> { disconnectInternal("user_disconnect"); report("idle", "已断开九号会话"); }); }
    public void onFrameSourceFailure(String reason) { onFrameSourceFailure(sourceGeneration, reason); }
    public void onFrameSourceFailure(long sessionGeneration, String reason) {
        actor.post(() -> { if (acceptsFrameSource(sessionGeneration)) fail("frame-source-" + safe(reason));
            else host.log("ninebot_stale_source_failure", json("sourceGeneration", sessionGeneration, "currentSourceGeneration", sourceGeneration, "sessionGeneration", generation)); });
    }
    public void acceptEncodedFrame(byte[] bytes, boolean keyframe, long presentationTimeUs) {
        acceptEncodedFrame(bytes, keyframe, presentationTimeUs, sourceGeneration);
    }
    public void acceptEncodedFrame(byte[] bytes, boolean keyframe, long presentationTimeUs, long sessionGeneration) {
        acceptEncodedFrame(bytes, keyframe, presentationTimeUs, sessionGeneration, null, SystemClock.elapsedRealtime());
    }
    void acceptEncodedFrame(byte[] bytes, boolean keyframe, long presentationTimeUs, long sessionGeneration,
            CastMetrics metrics, long readyAtMs) {
        if (!acceptsFrameSource(sessionGeneration) || bytes == null || bytes.length == 0) return;
        if (bytes.length > 4 * 1024 * 1024 || !frameQueued.compareAndSet(false, true)) {
            if (metrics != null) metrics.recordDrop();
            actor.post(() -> { droppedFrames++; host.log("ninebot_host_frame_dropped", json("reason", "actor_backpressure",
                    "bytes", bytes.length, "dropped", droppedFrames)); }); return;
        }
        long queuedAt = SystemClock.elapsedRealtime();
        byte[] copied = bytes.clone();
        actor.post(() -> {
            frameQueued.set(false);
            if (acceptsFrameSource(sessionGeneration) && engine != null && host.authorized()) {
                long started = SystemClock.elapsedRealtime();
                long queueWaitMs = Math.max(0,started-queuedAt);
                if(metrics!=null)metrics.recordActorQueueWait(queueWaitMs);
                try {
                    JSONArray output = engine.submitFrame(copied, keyframe, presentationTimeUs);
                    actions(output);
                    if (metrics != null && acceptsFrameSource(sessionGeneration) && output != null) {
                        for (int i = 0; i < output.length(); i++) {
                            JSONObject action = output.optJSONObject(i);
                            if (action != null && "frame_submitted".equals(action.optString("type"))) {
                                long now = SystemClock.elapsedRealtime();
                                metrics.recordSent(copied.length, now - readyAtMs, now - started, keyframe, now);
                                break;
                            }
                        }
                    }
                }
                    catch (RuntimeException | LinkageError error) { fail("frame-submit-" + error.getClass().getSimpleName()); }
                finally {
                    long submitMs=SystemClock.elapsedRealtime()-started;
                    if(queueWaitMs>100||submitMs>100)host.log("ninebot_frame_submit_slow",json("sourceGeneration",sessionGeneration,
                        "ptsUs",presentationTimeUs,"bytes",copied.length,"keyframe",keyframe,"actorQueueWaitMs",queueWaitMs,
                        "protocolAndLocalSendMs",submitMs));
                }
            }
        });
    }
    public void recordPermissionResult(String[] permissions, int[] results) {
        JSONArray values = new JSONArray();
        for (int i = 0; i < permissions.length; i++) values.put(json("permission", permissions[i], "granted",
                i < results.length && results[i] == PackageManager.PERMISSION_GRANTED));
        host.log("ninebot_permission_result", json("permissions", values));
    }
    private void connectInternal() {
        try {
        if (profile == null) throw new IllegalStateException("请先导入九号专用 JSON 资料");
        if (provider != null) throw new IllegalStateException("请等待授权材料刷新完成，或先断开取消");
        if (gatt != null || engine != null) throw new IllegalStateException("当前九号会话已存在，请先断开");
        stopScan(); configuration = null; authenticated = prepared = false; fieldsClear();
        if (profile.authVersion == 0) {
            LabConnectionService.startForNinebot(context);
            generation++;
            engine = new CastEngine(profile.options(profile.deviceName, 0, mtu), profile.credentials());
            actions(engine.start()); return;
        }
        requireBluetoothPermissions();
        if (selected == null) throw new IllegalStateException("请先扫描并选择九号蓝牙目标");
        if (!profile.bluetoothAddress.isEmpty() && !profile.bluetoothAddress.equalsIgnoreCase(selected.address))
            throw new IllegalStateException("选定蓝牙地址与导入资料不同");
        if (selected.rawName.isEmpty()) throw new IllegalStateException("认证需要当前原始广播名称，不能使用缓存名称");
        if (!profile.deviceName.isEmpty() && !profile.deviceName.equals(selected.rawName))
            throw new IllegalStateException("原始广播名称与资料不一致");
        LabConnectionService.startForNinebot(context);
        long token = ++generation; servicesReady = false; mtu = 23;
        report("connecting", "连接并读取 GATT 拓扑");
        gatt = selected.device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IllegalStateException("connectGatt 返回空会话");
        actor.postDelayed(() -> { if (token == generation && !servicesReady && gatt != null) fail("gatt-connect-or-discovery-timeout"); }, 15000);
        } catch (SecurityException revoked) { permissionLost(); }
    }
    private void beginAuthentication() {
        try {
        if (profile == null || selected == null) { fail("profile-or-scan-missing"); return; }
        if (!selected.ad.valid || selected.ad.version != profile.authVersion) { fail("broadcast-auth-version-unverified-or-mismatch"); return; }
        if (controlWrite == null || controlNotify == null) { fail("unique-nus-control-required-no-media-fallback"); return; }
        if ((controlNotify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                || (controlWrite.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            fail("nus-control-properties-unsupported"); return;
        }
        subscribing = controlNotify.getDescriptor(CCCD);
        if (subscribing == null || !gatt.setCharacteristicNotification(controlNotify, true)) { fail("nus-notification-setup-rejected"); return; }
        int status;
        if (Build.VERSION.SDK_INT >= 33) status = gatt.writeDescriptor(subscribing, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        else { subscribing.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); status = gatt.writeDescriptor(subscribing) ? 0 : -1; }
        host.log("ninebot_cccd_submit", json("status", status, "channel", "control"));
        if (status != 0) { fail("nus-cccd-submit-" + status); return; }
        report("subscribing", "等待 NUS 通知订阅完成");
        long token = generation; BluetoothGattDescriptor expected = subscribing;
        actor.postDelayed(() -> { if (token == generation && subscribing == expected) fail("nus-cccd-timeout"); }, 6000);
        } catch (SecurityException revoked) { permissionLost(); }
    }
    private void startEngine() {
        try {
            JSONObject options = profile.options(selected.rawName, selected.ad.version, mtu);
            options.put("controlWriteType", (controlWrite.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? 1 : 2);
            engine = new CastEngine(options, profile.credentials()); actions(engine.start());
        } catch (Exception | LinkageError error) { fail("native-start-" + error.getClass().getSimpleName()); }
    }
    private void prepareMedia() {
        if (desiredSource == null || sourceRequested || engine == null || profile == null) return;
        String transport = configuration == null ? profile.transport : transport(configuration.optInt("channel", -1));
        if (!transport.equals("ble") && profile.authVersion != 0
                && !nativeStage.equals("cast_mode_ready") && !nativeStage.equals("casting_ready")) {
            if (!nativeStage.equals("wait_mode")) actions(engine.command("start_cast", new JSONObject()));
            return;
        }
        if (transport.equals("ble")) {
            if (!nativeStage.equals("casting_ready")) {
                if (!nativeStage.equals("wait_mode")) actions(engine.command("start_cast", new JSONObject()));
                return;
            }
        } else {
            if (!ensureWifi()) { report("p2p_releasing", "等待上一 P2P 会话释放后连接"); return; }
            if (!wifi.ready()) {
                if (targetP2pName.isEmpty() || targetP2pAddress.isEmpty()) throw new IllegalStateException("请发现并选择九号 Wi-Fi Direct 仪表");
                if (!wifi.active()) { report("p2p_connecting", "核对 P2P 仪表组主和本地路由"); wifi.connect(targetP2pName, targetP2pAddress); }
                return;
            }
            if (!nativeStage.equals("casting_ready")) {
                actions(engine.command(profile.authVersion == 0 ? "start_cast" : "start_wifi_media", new JSONObject()));
                return;
            }
        }
        sourceGeneration++; sourceRequested = true;
        host.requestFrameSource(desiredSource, frameConfig()); publish();
    }
    private boolean ensureWifi() {
        if (wifi != null) return true;
        if (wifiClosing) return false;
        long token = ++wifiGeneration;
        wifi = new NinebotP2pTransport(context, actor, new NinebotP2pTransport.Listener() {
            @Override public void event(String event, JSONObject data) {
                if (token != wifiGeneration) return;
                if (event.equals("ninebot_p2p_discovery_started")) {
                    wifiPeers = new JSONArray(); wifiDiscovery = copy(data);
                } else if (event.equals("ninebot_p2p_peers")) {
                    data = filterWifiDiscovery(data); wifiDiscovery = copy(data);
                    wifiPeers = data.optJSONArray("peers");
                }
                host.log(event, data);
                if (event.equals("ninebot_p2p_discovery_started") || event.equals("ninebot_p2p_peers")) publish();
            }
            @Override public void ready(JSONObject facts) {
                if (token != wifiGeneration || closed) return;
                host.log("ninebot_p2p_route_verified", facts);
                if (engine != null && desiredSource != null) transport(NinebotTestController.this::prepareMedia);
                else publish();
            }
            @Override public void received(byte[] bytes, boolean control) {
                if (token != wifiGeneration || closed) return;
                host.trace(control ? "udp_control_rx" : "udp_media_rx", bytes, json("generation", generation));
                if (engine != null) transport(() -> actions(engine.onDatagram(bytes, control)));
            }
            @Override public void failed(String reason) { if (token == wifiGeneration && !closed) fail(reason); }
        });
        return true;
    }
    private JSONObject filterWifiDiscovery(JSONObject data) {
        JSONObject filtered = copy(data);
        JSONArray peers = filtered.optJSONArray("peers");
        if (peers == null) peers = new JSONArray();
        String expectedName = profile == null ? "" : profile.p2pName;
        String expectedAddress = profile == null ? "" : profile.p2pAddress;
        int eligible = 0;
        for (int i = 0; i < peers.length(); i++) {
            JSONObject row = peers.optJSONObject(i); if (row == null) continue;
            String reason = NinebotP2pTransport.peerRejectionReason(row.optString("name"), row.optString("address"), expectedName, expectedAddress);
            try { row.put("eligible", reason.isEmpty()); row.put("filterReason", reason); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            if (reason.isEmpty()) eligible++;
        }
        try {
            filtered.put("peers", peers); filtered.put("count", peers.length()); filtered.put("eligibleCount", eligible);
            filtered.put("filterPolicy", expectedName.isEmpty() && expectedAddress.isEmpty() ? "complete_name_and_mac_only" : "complete_name_mac_and_configured_target");
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return filtered;
    }
    private void actions(JSONArray output) {
        if (output == null || output.length() == 0) return;
        long token = generation; CastEngine owner = engine;
        boolean changed = false;
        for (int i = 0; i < output.length(); i++) {
            if (token != generation || owner != engine) return;
            JSONObject action = output.optJSONObject(i);
            if (action == null || closed) continue;
            String type = action.optString("type");
            if (type.equals("tx")) { send(action); continue; }
            if (type.equals("auth_provider")) { requestAuthorizedProvider(action); continue; }
            if (type.equals("gen3_key_state")) { persistGen3KeyState(action); continue; }
            if (type.equals("credentials_update")) { persistGen3Credentials(action); continue; }
            if (action.optBoolean("sensitive")) host.log("ninebot_native_" + type, json("sensitive", true, "state", action.optString("state"), "code", action.optString("code")));
            else host.log("ninebot_native_" + type, action);
            if (type.equals("state")) {
                changed = true;
                lastError = "";
                nativeStage = stage = action.optString("state", "unknown"); detail = action.optString("detail", "");
                authenticated = action.optBoolean("authenticated", authenticated);
                prepared = action.optBoolean("prepared", prepared);
                if (stage.equals("failed")) { fail(detail); return; }
                if (stage.equals("authenticated") && prepareAutomatically && configuration == null) {
                    prepareAutomatically = false;
                    postForSession(token, owner, () -> { if (authenticated && stage.equals("authenticated") && configuration == null) actions(engine.command("read_cast_config", new JSONObject())); });
                } else if (stage.equals("external_mode_ready") && desiredSource != null) postForSession(token, owner, this::prepareMedia);
                else if ((stage.equals("cast_mode_ready") || stage.equals("casting_ready")) && desiredSource != null)
                    postForSession(token, owner, this::prepareMedia);
                else if (stage.equals("stopped")) { desiredSource = null; invalidateFrameSource("native_stopped"); }
            } else if (type.equals("config")) {
                changed = true;
                configuration = action.optJSONObject("config");
                if (configuration != null && desiredSource != null) postForSession(token, owner, this::prepareMedia);
            } else if (type.equals("telemetry")) {
                changed = true;
                String key = action.optString("field", "raw");
                if (key.equals("raw")) key += "_" + action.optInt("target") + "_" + action.optInt("register");
                try { fields.put(key, new JSONObject(action.toString()).put("receivedElapsedMs", SystemClock.elapsedRealtime())); }
                catch (JSONException ignored) {}
            } else if (type.equals("error")) {
                changed = true;
                detail = lastError = action.optString("code", "native-error");
                if (action.optBoolean("fatal")) { fail(detail); return; }
                if (!sourceRequested) desiredSource = null;
            }
        }
        if (changed) publish();
    }
    private void requestAuthorizedProvider(JSONObject action) {
        if (engine == null || profile == null || profile.authVersion != 3) { fail("authorized_provider_session_invalid"); return; }
        String operation = action.optString("operation"), requestId = action.optString("requestId");
        if ((!operation.equals("challenge") && !operation.equals("response") && !operation.equals("verify"))
                || !(action.opt("request") instanceof JSONObject) || provider != null) { fail("authorized_provider_action_invalid"); return; }
        CastEngine owner = engine; long token = generation;
        try {
            NinebotCredentialProvider.Authorization active = authorizationFactory.open(profile.credentials().optJSONObject("v3"));
            provider = active;
            host.log("ninebot_provider_request", json("operation", operation, "requestId", requestId, "generation", token));
            active.execute(operation, requestId, copy(action.optJSONObject("request")), new NinebotCredentialProvider.Callback() {
                @Override public void completed(JSONObject result) {
                    postTransport(() -> {
                        if (provider != active || owner != engine || token != generation) return;
                        provider = null; active.close();
                        if (!host.authorized()) { fail("authorization_expired_during_provider"); return; }
                        host.log("ninebot_provider_result", json("operation", operation, "requestId", requestId, "success", true, "generation", token));
                        actions(owner.command("auth_provider_result", json("operation", operation, "requestId", requestId, "success", true, "result", result)));
                        publish();
                    });
                }
                @Override public void failed(String code) {
                    postTransport(() -> {
                        if (provider != active || owner != engine || token != generation) return;
                        provider = null; active.close();
                        host.log("ninebot_provider_result", json("operation", operation, "requestId", requestId, "success", false, "code", code, "generation", token));
                        actions(owner.command("auth_provider_result", json("operation", operation, "requestId", requestId, "success", false, "code", code)));
                        publish();
                    });
                }
            });
            publish();
        } catch (RuntimeException error) {
            if (provider != null) { provider.close(); provider = null; }
            host.log("ninebot_provider_unavailable", json("operation", operation, "requestId", requestId, "category", error.getClass().getSimpleName()));
            actions(owner.command("auth_provider_result", json("operation", operation, "requestId", requestId, "success", false, "code", "authorized_provider_unavailable")));
        }
    }
    private void persistGen3KeyState(JSONObject action) {
        host.log("ninebot_gen3_key_state", json("keyState", action.optString("keyState"), "encryptedProof", action.optBoolean("encryptedProof"),
                "promoteNext", action.optBoolean("promoteNext"), "removeNext", action.optBoolean("removeNext"),
                "backgroundRefreshSuggested", action.optBoolean("backgroundRefreshSuggested"), "generation", generation));
        if (profile == null || profile.authVersion != 3 || !action.optBoolean("encryptedProof")) return;
        if (!action.optBoolean("promoteNext") && !action.optBoolean("removeNext")) return;
        try {
            NinebotTestProfile updated = profile.withGen3KeyState(action);
            saveProfile(updated); profile = updated;
            host.log("ninebot_gen3_material_saved", json("generation", generation, "encryptedProof", true));
        } catch (RuntimeException error) {
            host.log("ninebot_gen3_material_save_failed", json("generation", generation, "category", error.getClass().getSimpleName()));
            fail("gen3-material-persistence-failed-original-retained");
        }
    }
    private void persistGen3Credentials(JSONObject action) {
        if (profile == null || profile.authVersion != 3 || !action.optBoolean("encryptedProof")) { fail("gen3-credentials-update-proof-required"); return; }
        try {
            NinebotTestProfile updated = profile.withGen3Credentials(action);
            saveProfile(updated); profile = updated;
            host.log("ninebot_gen3_material_saved", json("generation", generation, "encryptedProof", true, "fullAuthentication", true));
        } catch (RuntimeException error) {
            host.log("ninebot_gen3_material_save_failed", json("generation", generation, "category", error.getClass().getSimpleName()));
            fail("gen3-material-persistence-failed-original-retained");
        }
    }
    private JSONObject frameConfig() {
        JSONObject config = configuration == null ? json("width", profile.width, "height", profile.height,
                "fps", profile.fps, "bitrate", profile.bitrate, "codec", 1,
                "channel", profile.transport.equals("kcp") ? 2 : 1) : copy(configuration);
        int codec = config.optInt("codec", -1);
        try { config.put("codecName", codec == 0 ? "mpeg2" : codec == 1 ? "h264" : "unsupported");
            config.put("frameMode", desiredSource == null ? "" : desiredSource.name());
            config.put("generation", sourceGeneration); config.put("sessionGeneration", generation); }
        catch (JSONException ignored) {}
        return config;
    }
    private void send(JSONObject action) {
        if (engine == null) return;
        long id = action.optLong("id", -1); String channel = action.optString("channel");
        byte[] bytes;
        try { bytes = unhex(action.optString("hex")); }
        catch (RuntimeException invalid) { fail("native-tx-invalid-hex"); return; }
        if (id < 0 || bytes.length == 0) { fail("native-tx-invalid-action"); return; }
        if (channel.equals("udp_media") || channel.equals("udp_control")) {
            int status = wifi == null ? -1 : wifi.send(bytes, channel.equals("udp_control"));
            host.trace(channel + "_tx", bytes, json("actionId", id, "localStatus", status, "generation", generation));
            if (engine != null) actions(engine.transportResult(id, status)); return;
        }
        BluetoothGattCharacteristic characteristic = channel.equals("control") ? controlWrite : channel.equals("ble_media") ? mediaWrite : null;
        if (gatt == null || characteristic == null || bytes.length > mtu - 3 || writes.size() >= 512 || queuedBytes + bytes.length > 2 * 1024 * 1024) {
            host.log("ninebot_tx_rejected", json("channel", channel, "actionId", id, "bytes", bytes.length, "mtu", mtu));
            if (engine != null) actions(engine.transportResult(id, -1)); return;
        }
        int writeType = action.optInt("writeType", 1) == 2 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int required = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ? BluetoothGattCharacteristic.PROPERTY_WRITE : BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        if ((characteristic.getProperties() & required) == 0) {
            if (engine != null) actions(engine.transportResult(id, -2)); return;
        }
        writes.addLast(new Tx(id, channel, characteristic, bytes, writeType, action.optBoolean("sensitive") || !authenticated)); queuedBytes += bytes.length; pump();
    }
    private void pump() {
        try {
        if (gatt == null || pending != null || writes.isEmpty()) return;
        Tx tx = writes.removeFirst(); queuedBytes -= tx.bytes.length; pending = tx; tx.began = SystemClock.elapsedRealtime();
        int status;
        if (Build.VERSION.SDK_INT >= 33) status = gatt.writeCharacteristic(tx.characteristic, tx.bytes, tx.writeType);
        else { tx.characteristic.setWriteType(tx.writeType); tx.characteristic.setValue(tx.bytes); status = gatt.writeCharacteristic(tx.characteristic) ? 0 : -1; }
        JSONObject facts = json("actionId", tx.id, "generation", generation, "bytes", tx.bytes.length,
                "writeType", tx.writeType, "submitStatus", status, "queueLength", writes.size(), "sensitive", tx.sensitive);
        if (tx.sensitive) { try { facts.put("sha256", digest(tx.bytes)); } catch (JSONException ignored) {} host.log("ninebot_auth_tx", facts); }
        else host.trace(tx.channel + "_tx", tx.bytes, facts);
        if (status != 0) { completeWrite(tx, status); return; }
        long token = generation;
        actor.postDelayed(() -> transport(() -> { if (token == generation && pending == tx) { host.log("ninebot_gatt_write_timeout", json("actionId", tx.id)); completeWrite(tx, -3); } }), 6000);
        } catch (SecurityException revoked) { permissionLost(); }
    }
    private void completeWrite(Tx tx, int status) {
        if (pending != tx) return; pending = null;
        host.log("ninebot_gatt_local_completion", json("actionId", tx.id, "channel", tx.channel, "status", status,
                "elapsedMs", SystemClock.elapsedRealtime() - tx.began, "displayConfirmed", false));
        if (engine != null) actions(engine.transportResult(tx.id, status));
        if (status != 0) { fail("gatt-write-" + status); return; }
        postTransport(this::pump);
    }
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int status, int state) {
            postTransport(() -> {
                try {
                if (link != gatt) return;
                host.log("ninebot_gatt_connection", json("status", status, "state", state));
                if (status != 0 || state == BluetoothProfile.STATE_DISCONNECTED) { fail("gatt-disconnected-" + status); return; }
                if (state == BluetoothProfile.STATE_CONNECTED) { report("gatt_connected", "链路已连接，等待服务发现和车辆认证"); if (!link.discoverServices()) fail("gatt-discovery-submit-rejected"); }
                } catch (SecurityException revoked) { permissionLost(); }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt link, int status) {
            postTransport(() -> {
                try {
                if (link != gatt) return;
                if (status != 0) { fail("gatt-discovery-status-" + status); return; }
                servicesReady = true;
                for (BluetoothGattService service : link.getServices()) for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    JSONArray descriptors = new JSONArray(); for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) descriptors.put(descriptor.getUuid().toString());
                    host.log("ninebot_gatt_topology", json("service", service.getUuid().toString(), "characteristic", characteristic.getUuid().toString(),
                            "properties", characteristic.getProperties(), "instanceId", characteristic.getInstanceId(), "descriptors", descriptors));
                }
                BluetoothGattService nus = uniqueService(link, NUS_SERVICE), media = uniqueService(link, MEDIA_SERVICE);
                controlWrite = uniqueCharacteristic(nus, NUS_WRITE); controlNotify = uniqueCharacteristic(nus, NUS_NOTIFY);
                mediaWrite = uniqueCharacteristic(media, MEDIA_WRITE); mediaNotify = uniqueCharacteristic(media, MEDIA_NOTIFY);
                report("services_discovered", "NUS 控制=" + (controlWrite != null && controlNotify != null) + "，独立媒体=" + (mediaWrite != null));
                mtuPending = link.requestMtu(247);
                if (!mtuPending) { host.log("ninebot_mtu_request_rejected", json("attMtu", mtu)); beginAuthentication(); }
                else { long token = generation; actor.postDelayed(() -> { if (token == generation && mtuPending) fail("mtu-callback-timeout"); }, 6000); }
                } catch (SecurityException revoked) { permissionLost(); }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt link, int value, int status) {
            postTransport(() -> {
                if (link != gatt) return; boolean waiting = mtuPending; mtuPending = false;
                if (status == 0 && value >= 23 && value <= 517) mtu = value;
                host.log("ninebot_att_mtu", json("requested", 247, "callbackMtu", value, "effectiveAttMtu", mtu, "status", status));
                if (engine != null) actions(engine.configure(json("attMtu", mtu)));
                else if (waiting) beginAuthentication();
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt link, BluetoothGattDescriptor descriptor, int status) {
            postTransport(() -> {
                if (link != gatt || descriptor != subscribing) return; subscribing = null;
                host.log("ninebot_cccd_callback", json("status", status, "characteristic", descriptor.getCharacteristic().getUuid().toString()));
                if (status != 0) { fail("nus-cccd-status-" + status); return; }
                long token = generation;
                long delay = profile != null && (profile.profileId.endsWith("immediate") || profile.profileId.equals("encryption2_mmax90_capture_nus")) ? 0 : 300;
                actor.postDelayed(() -> transport(() -> { if (token == generation && gatt != null && engine == null) startEngine(); }), delay);
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] bytes) { received(link, characteristic, bytes == null ? new byte[0] : bytes.clone()); }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                try { byte[] value = characteristic.getValue(); received(link, characteristic, value == null ? new byte[0] : value.clone()); }
                catch (RuntimeException | LinkageError error) {
                    postTransport(() -> { host.log("ninebot_gatt_callback_exception", json("callback", "characteristic_changed", "category", error.getClass().getSimpleName())); fail("gatt-callback-characteristic-changed"); });
                }
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt link, BluetoothGattCharacteristic characteristic, int status) {
            postTransport(() -> { if (link == gatt && pending != null && pending.characteristic == characteristic) completeWrite(pending, status); });
        }
        @Override public void onServiceChanged(BluetoothGatt link) { postTransport(() -> { if (link == gatt) fail("gatt-services-changed-reconnect-required"); }); }
    };
    private void received(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        postTransport(() -> {
            if (link != gatt) return;
            String channel = characteristic == controlNotify ? "control_rx" : characteristic == mediaNotify ? "ble_control_rx" : "unknown_rx";
            JSONObject facts = json("uuid", characteristic.getUuid().toString(), "generation", generation, "bytes", bytes.length, "sensitive", !authenticated);
            if (!authenticated) { try { facts.put("sha256", digest(bytes)); } catch (JSONException ignored) {} host.log("ninebot_auth_rx", facts); }
            else host.trace(channel, bytes, facts);
            if (characteristic == controlNotify && engine != null) actions(engine.onControl(bytes));
        });
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { postTransport(() -> scanned(result, type)); }
        @Override public void onBatchScanResults(java.util.List<ScanResult> results) { postTransport(() -> { for (ScanResult result : results) scanned(result, -1); }); }
        @Override public void onScanFailed(int code) { postTransport(() -> { stopScan(); report("scan_failed", "系统扫描错误 " + code); }); }
    };
    private void scanned(ScanResult result, int callbackType) {
        try {
        if (!scanning || result == null || result.getDevice() == null) return;
        ScanRecord record = result.getScanRecord(); byte[] bytes = record == null ? new byte[0] : record.getBytes();
        String rawName = record == null || record.getDeviceName() == null ? "" : record.getDeviceName();
        String cached = result.getDevice().getName(); String name = rawName.isEmpty() ? cached == null ? "" : cached : rawName;
        Ad ad = Ad.parse(bytes);
        host.trace("ble_advertisement", bytes, json("address", result.getDevice().getAddress(), "rawName", rawName,
                "cachedName", cached == null ? "" : cached, "rssi", result.getRssi(), "timestampNanos", result.getTimestampNanos(),
                "callbackType", callbackType, "adValid", ad.valid, "authVersion", ad.version, "adReason", ad.reason));
        if (name.isEmpty() && !ad.recognized) return;
        if (candidates.size() >= 256 && !candidates.containsKey(result.getDevice().getAddress())) return;
        Candidate candidate = new Candidate(result.getDevice(), name, rawName, result.getRssi(), ad);
        candidates.put(candidate.address, candidate); publish();
        } catch (SecurityException revoked) { permissionLost(); }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            transport(() -> {
            if (!host.authorized() && (engine != null || gatt != null || scanning || wifi != null || provider != null)) {
                disconnectInternal("authorization_expired"); report("authorization_expired", "连接服务不可用，九号会话已释放");
            }
            if (engine != null) {
                long now = SystemClock.elapsedRealtime();
                if (lastTickAt != 0 && now - lastTickAt > 100) host.log("ninebot_actor_tick_late", json("gapMs", now - lastTickAt));
                lastTickAt = now; actions(engine.tick());
            }
            });
            actor.postDelayed(this, engine == null ? 250 : 10);
        }
    };
    private void run(Runnable work) { if (!closed) actor.post(() -> { if (!closed) safely(() -> { if (!host.authorized()) throw new IllegalStateException("连接服务不可用"); work.run(); }); }); }
    private void postTransport(Runnable work) { if (!closed) actor.post(() -> transport(work)); }
    private void postForSession(long token, CastEngine owner, Runnable work) { postTransport(() -> { if (token == generation && owner == engine && owner != null) work.run(); }); }
    private void transport(Runnable work) {
        if (closed) return;
        try { work.run(); }
        catch (RuntimeException | LinkageError error) {
            host.log("ninebot_transport_exception", json("category", error.getClass().getSimpleName(), "generation", generation));
            fail("transport-" + error.getClass().getSimpleName());
        }
    }
    private void safely(Runnable work) {
        try { work.run(); }
        catch (RuntimeException | LinkageError error) {
            host.log("ninebot_request_rejected", json("category", error.getClass().getSimpleName(), "message", safe(error.getMessage())));
            report(stage, safe(error.getMessage())); lastError = safe(error.getMessage()); }
    }
    private void requireIdle() { if (gatt != null || engine != null || sourceRequested || provider != null) throw new IllegalStateException("请先断开当前九号会话或等待材料刷新完成"); }
    private void requireEngine() { if (engine == null) throw new IllegalStateException("请先连接并完成车辆认证"); }
    private void requireBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31 && (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) throw new IllegalStateException("请先允许附近蓝牙设备权限");
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("请先允许精确位置信息权限");
    }
    private void stopScan() {
        if (scanner != null && scanning) { try { scanner.stopScan(scanCallback); } catch (SecurityException revoked) { host.log("ninebot_permission_revoked", json("operation", "stop_scan")); } catch (RuntimeException ignored) {} }
        scanning = false; scanner = null;
    }
    private void disconnectInternal(String reason) {
        generation++; wifiGeneration++; stopScan(); desiredSource = null; prepareAutomatically = false; discoverAfterWifiClose = false;
        nativeStage = "idle";
        invalidateFrameSource(reason); authenticated = prepared = false; mtuPending = false; servicesReady = false; subscribing = null;
        writes.clear(); queuedBytes = 0; pending = null; configuration = null; lastTickAt = 0;
        if (provider != null) { provider.close(); provider = null; }
        if (engine != null) { try { engine.close(); } catch (RuntimeException | LinkageError error) { releaseError("engine", error); } finally { engine = null; } }
        BluetoothGatt old = gatt; gatt = null;
        controlWrite = controlNotify = mediaWrite = mediaNotify = null;
        if (old != null) {
            try { old.disconnect(); } catch (SecurityException revoked) { releaseError("gatt_disconnect_permission", revoked); } catch (RuntimeException error) { releaseError("gatt_disconnect", error); }
            try { old.close(); } catch (SecurityException revoked) { releaseError("gatt_close_permission", revoked); } catch (RuntimeException error) { releaseError("gatt_close", error); }
        }
        if (wifi != null) {
            NinebotP2pTransport oldWifi = wifi; wifi = null; wifiClosing = true;
            try { oldWifi.close(() -> {
                wifiClosing = false;
                if (closed) { finishClose(); return; }
                if (discoverAfterWifiClose) { discoverAfterWifiClose = false; postTransport(() -> { if (ensureWifi()) wifi.discover(); }); }
                else if (desiredSource != null) postTransport(this::prepareMedia);
                publish();
            }); }
            catch (RuntimeException error) { wifiClosing = false; releaseError("wifi_close", error); }
        }
        host.log("ninebot_session_released", json("reason", reason, "generation", generation));
    }
    private void invalidateFrameSource(String reason) { long token = sourceGeneration; sourceRequested = false; sourceGeneration++; host.stopFrameSource(token, reason); }
    private void permissionLost() { fail("bluetooth-permission-revoked"); }
    private void releaseError(String operation, Throwable error) { host.log("ninebot_release_error", json("operation", operation, "category", error.getClass().getSimpleName())); }
    private void fail(String reason) { String value = safe(reason); disconnectInternal(value); report("failed", value); }
    private void report(String key, String message) { stage = key; detail = message; lastError = ""; host.log("ninebot_stage", json("stage", key, "detail", message, "generation", generation)); publish(); }
    private void publish() {
        if (publishPending || closed) return;
        publishPending = true;
        actor.postDelayed(() -> { publishPending = false; if (!closed) publishNow(); }, 150);
    }
    private void publishNow() {
        JSONArray devices = new JSONArray(); for (Candidate value : candidates.values()) devices.put(value.json());
        publicSnapshot = json("stage", stage, "nativeStage", nativeStage, "detail", detail, "lastError", lastError, "workflowId", workflowId, "profile", profile == null ? JSONObject.NULL : profile.summary(),
                "generation", generation, "sourceGeneration", sourceGeneration, "providerBusy", provider != null, "p2pReleasing", wifiClosing,
                "devices", devices, "wifiPeers", wifiPeers, "wifiDiscovery", copy(wifiDiscovery),
                "selected", selected == null ? JSONObject.NULL : selected.json(),
                "p2pName", targetP2pName, "p2pAddress", targetP2pAddress, "scanning", scanning,
                "authenticated", authenticated, "prepared", prepared, "connected", gatt != null || engine != null,
                "accountBindingCheck", "not_performed", "connectionInfoUpload", "not_performed",
                "casting", sourceRequested, "attMtu", mtu, "configuration", configuration == null ? JSONObject.NULL : copy(configuration),
                "telemetry", copy(fields), "queueLength", writes.size() + (pending == null ? 0 : 1), "hostDroppedFrames", droppedFrames);
        host.changed();
    }
    private void fieldsClear() { java.util.Iterator<String> names = fields.keys(); while (names.hasNext()) { names.next(); names.remove(); } }
    private void saveProfile(NinebotTestProfile value) {
        try { store.save(NinebotCredentialProvider.ACTIVE_VEHICLE, new JSONObject(value.saved())); savedProfile = value; }
        catch (JSONException invalid) { throw new IllegalStateException("九号材料格式错误"); }
    }
    @Override public void close() { if (closed) return; closed = true; actor.post(() -> {
        disconnectInternal("controller_closed");
        if (!wifiClosing) finishClose();
    }); }
    private void finishClose() { actor.removeCallbacksAndMessages(null); thread.quitSafely(); }
    private static JSONObject json(Object... values) { return NinebotTestProfile.json(values); }
    private static String safe(String reason) { return reason == null || reason.isEmpty() ? "unknown" : reason; }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (JSONException ignored) { return new JSONObject(); } }
    private static String transport(int channel) { return channel == 0 ? "ble" : channel == 1 ? "udp" : channel == 2 ? "kcp" : "unsupported"; }
    private static byte[] unhex(String text) {
        if (text == null || (text.length() & 1) != 0 || text.length() > 131070) throw new IllegalArgumentException("hex-length");
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) { int a = Character.digit(text.charAt(i * 2), 16), b = Character.digit(text.charAt(i * 2 + 1), 16); if (a < 0 || b < 0) throw new IllegalArgumentException("hex-digit"); result[i] = (byte) (a * 16 + b); }
        return result;
    }
    private static String digest(byte[] bytes) {
        try { byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder(64); for (byte value : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString(); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable"); }
    }
    private static BluetoothGattService uniqueService(BluetoothGatt link, UUID id) {
        BluetoothGattService selected = null;
        for (BluetoothGattService service : link.getServices()) if (service.getUuid().equals(id)) { if (selected != null) return null; selected = service; }
        return selected;
    }
    private static BluetoothGattCharacteristic uniqueCharacteristic(BluetoothGattService service, UUID id) {
        if (service == null) return null; BluetoothGattCharacteristic selected = null;
        for (BluetoothGattCharacteristic value : service.getCharacteristics()) if (value.getUuid().equals(id)) { if (selected != null) return null; selected = value; }
        return selected;
    }
    private static final class Tx {
        final long id; final String channel; final BluetoothGattCharacteristic characteristic; final byte[] bytes; final int writeType; final boolean sensitive; long began;
        Tx(long id, String channel, BluetoothGattCharacteristic characteristic, byte[] bytes, int type, boolean sensitive) { this.id = id; this.channel = channel; this.characteristic = characteristic; this.bytes = bytes; writeType = type; this.sensitive = sensitive; }
    }
    private static final class Candidate {
        final BluetoothDevice device; final String name, rawName, address; final int rssi; final Ad ad;
        Candidate(BluetoothDevice device, String name, String rawName, int rssi, Ad ad) { this.device = device; this.name = name; this.rawName = rawName; address = device.getAddress(); this.rssi = rssi; this.ad = ad; }
        JSONObject json() { return NinebotTestProfile.json("name", name, "rawName", rawName, "address", address, "rssi", rssi, "adValid", ad.valid, "authVersion", ad.version, "adReason", ad.reason); }
    }
    private static final class Ad {
        final boolean recognized, valid; final int version; final String reason;
        Ad(boolean recognized, boolean valid, int version, String reason) { this.recognized = recognized; this.valid = valid; this.version = version; this.reason = reason; }
        static Ad parse(byte[] bytes) {
            boolean known = false; int version = -1;
            for (int offset = 0; offset < bytes.length;) {
                int length = bytes[offset++] & 255; if (length == 0) break;
                if (offset + length > bytes.length) return new Ad(known, false, -1, "truncated_ad");
                if ((bytes[offset] & 255) == 255 && length >= 3) {
                    int id = (bytes[offset + 1] & 255) << 8 | bytes[offset + 2] & 255;
                    if (id == 0x4e42 || id == 0x4e43 || id == 0x1f0f) {
                        known = true; int begin = offset + 3, end = offset + length;
                        if (end - begin < (id == 0x4e43 ? 4 : id == 0x4e42 ? 3 : 2)) return new Ad(true, false, -1, "short_manufacturer");
                        int sum = 0; for (int i = begin; i < end - 1; i++) sum += bytes[i] & 255;
                        if ((~sum & 255) != (bytes[end - 1] & 255)) return new Ad(true, false, -1, "checksum");
                        int found = bytes[begin + (id == 0x4e42 ? 1 : id == 0x4e43 ? 2 : 0)] & 255;
                        if (id == 0x1f0f) { found &= 15; for (int i = begin + 1; i < end - 1;) { int size = bytes[i++] & 31; if (i + size > end - 1) return new Ad(true, false, -1, "truncated_tlv"); i += size; } }
                        if (version >= 0 && version != found) return new Ad(true, false, -1, "conflicting_versions"); version = found;
                    }
                }
                offset += length;
            }
            return new Ad(known, known && version >= 0, version, known ? "validated" : "unknown_manufacturer");
        }
    }
}
