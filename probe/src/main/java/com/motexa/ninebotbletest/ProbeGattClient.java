package com.motexa.ninebotbletest;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import org.openninebot.reference.app.NinebotEncryption2Codec;
import org.openninebot.reference.app.NinebotVehicleProfile;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** One actor, one GATT operation at a time. Local write completion never means display success. */
@SuppressLint("MissingPermission")
final class ProbeGattClient implements AutoCloseable {
    interface Listener { void status(String text); }
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID MEDIA_SERVICE = UUID.fromString("6e400001-0000-0000-006e-696e65626f74");
    private static final UUID MEDIA_WRITE = UUID.fromString("6e400002-0000-0000-006e-696e65626f74");
    private final Context context;
    private final ProbeJournal journal;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("NinebotProbeGatt");
    private final Handler actor;
    private final ArrayDeque<Write> writes = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controlWrite, controlNotify, mediaWrite;
    private BluetoothGattDescriptor notificationDescriptor;
    private NinebotProbeSession session;
    private NinebotAdvertisement advertisement;
    private String deviceName, profileId;
    private byte[] pendingPassword;
    private int mtu = 23;
    private long generation, stageToken, mediaToken;
    private boolean subscriptionPending, modeAcknowledged, playing, mtuPending, stopRequested, servicesDiscovered;
    private Write pending;
    private CastConfiguration configuration;
    private Mpeg2ProbeClip clip;
    private int clipIndex;
    private BleRtpPacketizer packetizer;

    ProbeGattClient(Context context, ProbeJournal journal, Listener listener) {
        this.context = context.getApplicationContext(); this.journal = journal; this.listener = listener;
        thread.start(); actor = new Handler(thread.getLooper());
    }

    void connect(BluetoothDevice device, String name, NinebotAdvertisement ad) {
        actor.post(() -> {
            disconnectInternal();
            advertisement = ad; deviceName = name; long link = ++generation;
            report("connecting", "连接并枚举服务");
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            actor.postDelayed(() -> { if (generation == link && !servicesDiscovered) fail("connection-or-discovery-timeout"); }, 15000);
        });
    }

    void authenticate(String selectedProfile, byte[] password) {
        byte[] secret = password == null ? null : password.clone();
        actor.post(() -> {
            boolean subscriptionStarted = false;
            try {
                if (gatt == null || controlWrite == null || controlNotify == null)
                    throw new IllegalStateException("nus-control-service-unavailable");
                if (mtuPending) throw new IllegalStateException("wait-for-mtu-callback");
                if ((controlNotify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                        || (controlWrite.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0)
                    throw new IllegalStateException("nus-properties-unsupported");
                if (advertisement == null || !advertisement.valid || advertisement.authVersion != 2)
                    throw new IllegalStateException("gen2-broadcast-not-verified");
                if (session != null || subscriptionPending) throw new IllegalStateException("reconnect-before-new-auth");
                if (deviceName == null || deviceName.isEmpty()) throw new IllegalStateException("exact-device-name-required");
                profileId = selectedProfile;
                if (NinebotVehicleProfile.resolve(profileId) == null) throw new IllegalStateException("profile-unsupported");
                pendingPassword = secret == null ? null : secret.clone();
                notificationDescriptor = controlNotify.getDescriptor(CCCD);
                if (notificationDescriptor == null || !gatt.setCharacteristicNotification(controlNotify, true))
                    throw new IllegalStateException("notify-enable-rejected");
                subscriptionPending = true;
                subscriptionStarted = true;
                int result;
                if (Build.VERSION.SDK_INT >= 33)
                    result = gatt.writeDescriptor(notificationDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                else {
                    notificationDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    result = gatt.writeDescriptor(notificationDescriptor) ? 0 : -1;
                }
                if (result != 0) throw new IllegalStateException("cccd-submit-" + result);
                report("subscribing", "等待 NUS 通知订阅回调");
                long link = generation;
                actor.postDelayed(() -> { if (generation == link && subscriptionPending) fail("cccd-timeout"); }, 6000);
            } catch (Exception error) {
                if (subscriptionStarted) fail(error.getMessage());
                else report("request_rejected", error.getMessage());
            }
            finally { if (secret != null) Arrays.fill(secret, (byte) 0); }
        });
    }

    void read(int target, int index, int length) { action(() -> session.read(target, index, length)); }
    void readCastConfig() { action(() -> { configuration = null; modeAcknowledged = false; session.readCastConfig(0x23); }); }
    void naviMode(int mode) { action(() -> {
        if (mode != 0 && (configuration == null || !configuration.supportedVersion))
            throw new IllegalStateException("fresh-supported-cast-config-required");
        modeAcknowledged = false; session.setNaviMode(0x23, mode);
    }); }
    void stopCasting() {
        actor.post(() -> {
            playing = false; mediaToken++;
            writes.removeIf(write -> write.characteristic == mediaWrite);
            if (session == null || !session.isAuthenticated()) { report("request_rejected", "authentication-required"); return; }
            if (pending != null && pending.characteristic == mediaWrite) {
                stopRequested = true;
                report("media_stopping", "等待当前本地写入完成后发送停止模式");
            } else if (pending == null && writes.isEmpty()) {
                try { modeAcknowledged = false; session.setNaviMode(0x23, 0); }
                catch (Exception error) { report("request_rejected", error.getMessage()); }
            } else report("request_rejected", "control-write-in-progress");
        });
    }
    private void action(Runnable runnable) {
        actor.post(() -> {
            try {
                if (session == null || !session.isAuthenticated()) throw new IllegalStateException("authentication-required");
                if (playing || pending != null || !writes.isEmpty()) throw new IllegalStateException("write-in-progress");
                runnable.run();
            } catch (Exception error) { report("request_rejected", error.getMessage()); }
        });
    }

    void play(Mpeg2ProbeClip selectedClip) {
        actor.post(() -> {
            try {
                if (session == null || !session.isAuthenticated() || !"authenticated".equals(session.state()))
                    throw new IllegalStateException("authentication-required");
                if (configuration == null || !configuration.accepts(selectedClip))
                    throw new IllegalStateException("fresh-ble-mpeg2-config-and-matching-clip-required");
                if (!modeAcknowledged || mediaWrite == null) throw new IllegalStateException("mode-ack-and-media-characteristic-required");
                if (playing || pending != null || !writes.isEmpty() || mtu <= 30)
                    throw new IllegalStateException("media-not-ready");
                clip = selectedClip; clipIndex = 0; playing = true;
                SecureRandom random = new SecureRandom();
                packetizer = new BleRtpPacketizer(random.nextInt(), random.nextInt(65536));
                report("media_start", "发送测试片段；GATT 回调仅表示本地写入结果");
                sendNextFrame(++mediaToken);
            } catch (Exception error) { report("request_rejected", error.getMessage()); }
        });
    }

    private void sendNextFrame(long run) {
        if (!playing || gatt == null || mediaToken != run) return;
        if (pending != null || !writes.isEmpty()) { fail("media-scheduler-overlap"); return; }
        if (clipIndex >= clip.frames.size()) {
            playing = false;
            report("media_submitted", "片段本地发送结束，需核对仪表是否显示；可点停止投屏");
            return;
        }
        int index = clipIndex++;
        List<byte[]> packets = packetizer.packetize(clip.frames.get(index), clip.keys.get(index), mtu, System.currentTimeMillis());
        if (packets.size() > 512) { fail("frame-exceeds-bounded-queue"); return; }
        long link = generation, began = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < packets.size(); i++) {
            Runnable done = i == packets.size() - 1 ? () -> {
                if (!playing || mediaToken != run) return;
                long delay = Math.max(0, 1000L / clip.fps - (android.os.SystemClock.elapsedRealtime() - began));
                actor.postDelayed(() -> { if (generation == link && mediaToken == run) sendNextFrame(run); }, delay);
            } : null;
            writes.add(new Write(mediaWrite, packets.get(i), "rtp", done));
        }
        journal.event("media_frame", "index", index, "key", clip.keys.get(index), "fragments", packets.size());
        pump();
    }

    private void startSession() {
        session = new NinebotProbeSession(deviceName, profileId, pendingPassword, new NinebotProbeSession.Listener() {
            @Override public void onSend(byte[] bytes, String stage) {
                int actual = NinebotEncryption2Codec.effectiveWriteMtu(session.isAuthenticated(), mtu);
                for (byte[] chunk : NinebotEncryption2Codec.fragmentForAtt(bytes, actual))
                    writes.add(new Write(controlWrite, chunk, stage, null));
                pump();
            }
            @Override public void onState(String stage, String detail) {
                report(stage, detail);
                long token = ++stageToken, link = generation;
                if (stage.startsWith("wait_")) actor.postDelayed(() -> {
                    if (generation == link && stageToken == token && session != null) session.onTimeout();
                }, stage.equals("wait_precomm") ? 10000 : 5000);
                if (stage.equals("failed")) fail(detail);
            }
            @Override public void onFrame(NinebotEncryption2Codec.Frame frame) {
                journal.event("control_response", "source", frame.sourceBoard, "command", frame.cmd,
                        "index", frame.index, "length", frame.data.length);
                if (frame.cmd == NinebotEncryption2Codec.CMD_READ_RESP)
                    journal.event("register_read", "source", frame.sourceBoard, "index", frame.index,
                            "hex", ProbeJournal.hex(frame.data));
            }
            public void onCastConfig(byte[] bytes) {
                try {
                    configuration = new CastConfiguration(bytes);
                    journal.event("cast_config_md5_verified", "json", configuration.json, "rawHex", ProbeJournal.hex(bytes));
                    report("config_verified", configuration.summary());
                } catch (Exception error) { fail("cast-config-invalid-" + error.getMessage()); }
            }
            public void onModeAcknowledged(int mode) {
                modeAcknowledged = mode == 2;
                report("mode_ack", "模式 " + mode + " 已有匹配回复，尚未验证显示");
            }
        });
        if (pendingPassword != null) Arrays.fill(pendingPassword, (byte) 0);
        pendingPassword = null;
        session.start();
    }

    private void pump() {
        if (gatt == null || pending != null || writes.isEmpty()) return;
        Write write = writes.remove(); pending = write;
        int properties = write.characteristic.getProperties();
        int type = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (write.characteristic == mediaWrite && type != BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            fail("media-write-without-response-unavailable"); return;
        }
        int result;
        if (Build.VERSION.SDK_INT >= 33) result = gatt.writeCharacteristic(write.characteristic, write.bytes, type);
        else {
            write.characteristic.setWriteType(type); write.characteristic.setValue(write.bytes);
            result = gatt.writeCharacteristic(write.characteristic) ? 0 : -1;
        }
        journal.event("tx", "characteristic", write.characteristic.getUuid().toString(), "stage", write.stage,
                "writeType", type, "hex", ProbeJournal.hex(write.bytes), "submitStatus", result);
        if (result != 0) { fail("write-submit-" + result); return; }
        long link = generation;
        actor.postDelayed(() -> { if (generation == link && pending == write) fail("gatt-write-timeout"); }, 6000);
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt link, int status, int newState) {
            actor.post(() -> {
                if (link != gatt) return;
                journal.event("connection_state", "status", status, "state", newState);
                if (status != 0 || newState == BluetoothProfile.STATE_DISCONNECTED) { fail("gatt-disconnected-" + status); return; }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    report("gatt_connected", "蓝牙已连接，尚未认证");
                    if (!link.discoverServices()) fail("discover-submit-rejected");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt link, int status) {
            actor.post(() -> {
                if (link != gatt) return;
                if (status != 0) { fail("discover-status-" + status); return; }
                servicesDiscovered = true;
                for (BluetoothGattService service : link.getServices()) for (BluetoothGattCharacteristic characteristic : service.getCharacteristics())
                    journal.event("gatt_characteristic", "service", service.getUuid().toString(), "uuid", characteristic.getUuid().toString(), "properties", characteristic.getProperties());
                BluetoothGattService nus = uniqueService(link, NinebotVehicleProfile.NUS_SERVICE_UUID);
                controlWrite = uniqueCharacteristic(nus, NinebotVehicleProfile.NUS_WRITE_UUID);
                controlNotify = uniqueCharacteristic(nus, NinebotVehicleProfile.NUS_NOTIFY_UUID);
                BluetoothGattService media = uniqueService(link, MEDIA_SERVICE);
                mediaWrite = uniqueCharacteristic(media, MEDIA_WRITE);
                report("services_discovered", "NUS控制=" + (controlWrite != null && controlNotify != null) + " 媒体=" + (mediaWrite != null));
                mtuPending = link.requestMtu(247);
                if (!mtuPending) report("mtu_request_rejected", "保持 ATT MTU 23；暂停媒体测试");
                else {
                    long generationAtMtu = generation;
                    actor.postDelayed(() -> { if (generation == generationAtMtu && mtuPending) fail("mtu-callback-timeout"); }, 6000);
                }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt link, int value, int status) {
            actor.post(() -> { if (link == gatt) {
                mtuPending = false;
                if (status == 0) mtu = value;
                journal.event("mtu", "rawAttMtu", mtu, "status", status);
                report("mtu", "实际 ATT MTU=" + mtu);
            }});
        }
        @Override public void onServiceChanged(BluetoothGatt link) {
            actor.post(() -> { if (link == gatt) fail("gatt-service-changed-reconnect-required"); });
        }
        @Override public void onDescriptorWrite(BluetoothGatt link, BluetoothGattDescriptor descriptor, int status) {
            actor.post(() -> {
                if (link != gatt || !subscriptionPending || descriptor != notificationDescriptor) return;
                journal.event("cccd", "status", status, "characteristic", descriptor.getCharacteristic().getUuid().toString());
                if (status != 0) { fail("cccd-status-" + status); return; }
                long generationAtSubscribe = generation;
                org.openninebot.reference.app.NinebotVehicleProfile profile = org.openninebot.reference.app.NinebotVehicleProfile.resolve(profileId);
                long delay = profile == null ? 0 : profile.settleDelayMs;
                actor.postDelayed(() -> {
                    if (generation != generationAtSubscribe || !subscriptionPending || session != null) return;
                    subscriptionPending = false;
                    try { startSession(); } catch (Exception error) { fail(error.getMessage()); }
                }, delay);
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value) {
            received(link, characteristic, value.clone());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt link, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] bytes = characteristic.getValue();
                received(link, characteristic, bytes == null ? new byte[0] : bytes.clone());
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt link, BluetoothGattCharacteristic characteristic, int status) {
            actor.post(() -> {
                if (link != gatt || pending == null || pending.characteristic != characteristic) return;
                Write completed = pending; pending = null;
                journal.event("local_write_callback", "stage", completed.stage, "status", status);
                if (status != 0) { fail("gatt-write-status-" + status); return; }
                if (stopRequested) {
                    stopRequested = false; modeAcknowledged = false;
                    try { session.setNaviMode(0x23, 0); }
                    catch (Exception error) { fail(error.getMessage()); }
                    return;
                }
                if (completed.done != null && playing) completed.done.run();
                long generationAtCompletion = generation;
                actor.postDelayed(() -> { if (generation == generationAtCompletion) pump(); }, completed.stage.equals("rtp") ? 0 : 12);
            });
        }
    };

    private void received(BluetoothGatt link, BluetoothGattCharacteristic characteristic, byte[] value) {
        actor.post(() -> {
            if (link != gatt) return;
            journal.event("rx", "characteristic", characteristic.getUuid().toString(), "hex", ProbeJournal.hex(value));
            if (characteristic == controlNotify && session != null) {
                try { session.accept(value); } catch (Exception error) { fail("response-" + error.getClass().getSimpleName()); }
            }
        });
    }

    void disconnect() { actor.post(this::disconnectInternal); }
    private void disconnectInternal() {
        generation++; stageToken++; mediaToken++; playing = false; subscriptionPending = false; modeAcknowledged = false;
        mtuPending = false; stopRequested = false; servicesDiscovered = false;
        writes.clear(); pending = null; configuration = null; clip = null; mtu = 23;
        if (pendingPassword != null) Arrays.fill(pendingPassword, (byte) 0);
        pendingPassword = null;
        if (session != null) { session.close(); session = null; }
        BluetoothGatt old = gatt; gatt = null;
        controlWrite = controlNotify = mediaWrite = null; notificationDescriptor = null;
        if (old != null) { old.disconnect(); old.close(); }
    }
    private void fail(String reason) { report("failed", reason == null ? "unknown" : reason); disconnectInternal(); }
    private void report(String stage, String detail) { journal.event("stage", "stage", stage, "detail", detail); listener.status(stage + " · " + detail); }
    private static BluetoothGattService uniqueService(BluetoothGatt link, UUID uuid) {
        BluetoothGattService found = null;
        for (BluetoothGattService service : link.getServices()) if (uuid.equals(service.getUuid())) {
            if (found != null) return null;
            found = service;
        }
        return found;
    }
    private static BluetoothGattCharacteristic uniqueCharacteristic(BluetoothGattService service, UUID uuid) {
        if (service == null) return null;
        BluetoothGattCharacteristic found = null;
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) if (uuid.equals(characteristic.getUuid())) {
            if (found != null) return null;
            found = characteristic;
        }
        return found;
    }
    @Override public void close() { actor.post(() -> { disconnectInternal(); journal.close(); thread.quitSafely(); }); }
    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final byte[] bytes;
        final String stage;
        final Runnable done;
        Write(BluetoothGattCharacteristic characteristic, byte[] bytes, String stage, Runnable done) {
            this.characteristic = characteristic; this.bytes = bytes; this.stage = stage; this.done = done;
        }
    }
}
