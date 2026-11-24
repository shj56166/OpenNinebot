package com.motexa.ninebotbletest;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import org.openninebot.reference.app.NinebotVehicleProfile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Foreground engineering probe. Each packet-producing stage is explicit and logged locally. */
@SuppressLint("MissingPermission")
public final class ProbeActivity extends Activity {
    private final Handler main = new Handler();
    private final LinkedHashMap<String, Seen> devices = new LinkedHashMap<>();
    private final List<JSONObject> models = new ArrayList<>();
    private final List<String> deviceAddresses = new ArrayList<>();
    private ProbeJournal journal;
    private ProbeGattClient client;
    private BluetoothAdapter bluetooth;
    private BluetoothLeScanner scanner;
    private ArrayAdapter<String> deviceAdapter;
    private Spinner devicePicker, modelPicker, profilePicker;
    private EditText credential, target, index, length;
    private TextView log, selectedModel;
    private boolean scanning;
    private JSONObject activeModel;
    private Mpeg2ProbeClip clip;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            journal = new ProbeJournal(this);
            client = new ProbeGattClient(this, journal, message -> main.post(() -> append(message)));
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            bluetooth = manager == null ? null : manager.getAdapter();
            buildUi();
            try (InputStream input = getAssets().open("probe-240x320.json")) { clip = new Mpeg2ProbeClip(input); }
            append("已加载 " + clip.width + "×" + clip.height + " MPEG2 测试片段。先开启手机完整 HCI 日志。");
        } catch (Exception error) {
            TextView failure = new TextView(this); failure.setText("初始化失败：" + error.getMessage()); setContentView(failure);
        }
    }

    private void buildUi() throws Exception {
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(20, 20, 20, 20);
        scroll.addView(column); setContentView(scroll);
        label(column, "九号连接研究 0.1.0", 22);
        label(column, "依次测试：枚举 → Gen2握手 → 只读 → 配置 → 模式 → 片段。每一步记录结果；仪表显示需现场核对。", 14);
        modelPicker = new Spinner(this);
        List<String> names = new ArrayList<>();
        names.add("未知车型：只枚举服务"); models.add(null);
        try (InputStream input = getAssets().open("model-catalog.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            for (int size; (size = input.read(block)) >= 0;) buffer.write(block, 0, size);
            JSONObject catalog = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            JSONArray entries = catalog.getJSONArray("models");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject model = entries.getJSONObject(i); models.add(model);
                names.add(model.optString("name_zh", model.optString("name_en")) + " [" + model.optInt("server_id") + "]"
                        + (model.optBoolean("command_catalog_available") ? " · 有投屏命令表" : ""));
            }
        }
        modelPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names)); column.addView(modelPicker);
        selectedModel = label(column, "连接时固定所选车型；目录不代表实车兼容认证。", 13);
        button(column, "1. 扫描附近设备（8秒）", this::scan);
        devicePicker = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        devicePicker.setAdapter(deviceAdapter); column.addView(devicePicker);
        button(column, "2. 连接并枚举服务", this::connectSelected);
        profilePicker = new Spinner(this);
        profilePicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Gen2 NUS：PRE_COMM/AUTH目标0x04", "Gen2 NUS：PRE_COMM/AUTH目标0x09（VCU）"}));
        column.addView(profilePicker);
        credential = field(column, "已绑定车辆的16字节蓝牙密码：32位HEX，可留空先看预通信", "");
        credential.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credential.setSaveEnabled(false);
        credential.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        button(column, "3. Gen2 预通信／认证", this::authenticate);
        target = field(column, "只读板地址（HEX）", "01");
        index = field(column, "只读寄存器（HEX）", "26");
        length = field(column, "读取字节数（十进制）", "2");
        button(column, "4. 发送单条只读请求", () -> client.read(parseHex(target), parseHex(index), Integer.parseInt(length.getText().toString())));
        button(column, "5. 读取并校验投屏配置", () -> { requireCastModel(); client.readCastConfig(); });
        button(column, "6. 开启地图投屏模式", () -> { requireCastModel(); client.naviMode(2); });
        button(column, "导入其他尺寸的测试片段 JSON", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 11);
        });
        button(column, "7. 发送匹配尺寸的 MPEG2 片段", () -> { requireCastModel(); client.play(clip); });
        button(column, "停止投屏模式", () -> { requireCastModel(); client.stopCasting(); });
        button(column, "断开连接", () -> { activeModel = null; client.disconnect(); append("已请求断开"); });
        button(column, "导出本次收发日志", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-ndjson")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, journal.file.getName());
            startActivityForResult(intent, 12);
        });
        log = label(column, "日志保存在应用私有目录，不上传网络。\n", 12);
        log.setTextIsSelectable(true);
    }

    private void scan() {
        if (!permissions()) return;
        if (bluetooth == null || !bluetooth.isEnabled()) throw new IllegalStateException("请先开启蓝牙");
        if (scanning) return;
        scanner = bluetooth.getBluetoothLeScanner();
        if (scanner == null) throw new IllegalStateException("蓝牙扫描不可用");
        devices.clear(); deviceAddresses.clear(); deviceAdapter.clear();
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        scanning = true; journal.event("scan_start", "androidApi", Build.VERSION.SDK_INT);
        append("扫描开始"); main.postDelayed(this::stopScan, 8000);
    }

    private void stopScan() {
        if (scanning && scanner != null) scanner.stopScan(scanCallback);
        if (scanning) { journal.event("scan_end", "deviceCount", devices.size()); append("扫描结束，设备数=" + devices.size()); }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> {
                if (!scanning || result.getScanRecord() == null) return;
                byte[] raw = result.getScanRecord().getBytes();
                String address = result.getDevice().getAddress();
                Seen prior = devices.get(address);
                if (prior != null && Arrays.equals(prior.raw, raw)) return;
                String name = result.getScanRecord().getDeviceName();
                if (name == null) name = result.getDevice().getName();
                NinebotAdvertisement ad = NinebotAdvertisement.parse(raw);
                if (prior == null && devices.size() >= 100) return;
                Seen seen = new Seen(result.getDevice(), name, ad, raw); devices.put(address, seen);
                journal.event("advertisement", "address", address, "name", name, "rssi", result.getRssi(),
                        "rawHex", ProbeJournal.hex(raw), "validated", ad.valid, "authVersion", ad.authVersion, "reason", ad.reason);
                if (prior == null) {
                    deviceAddresses.add(address);
                    deviceAdapter.add((name == null ? "无名称" : name) + " · " + address + " · 认证=" + (ad.valid ? ad.authVersion : "未知"));
                } else {
                    int position = deviceAddresses.indexOf(address);
                    String row = (name == null ? "无名称" : name) + " · " + address + " · 认证=" + (ad.valid ? ad.authVersion : "未知");
                    deviceAdapter.remove(deviceAdapter.getItem(position)); deviceAdapter.insert(row, position);
                }
            });
        }
        @Override public void onScanFailed(int code) { main.post(() -> {
            scanning = false; journal.event("scan_failed", "code", code); append("扫描失败=" + code);
        }); }
    };

    private void connectSelected() {
        if (!permissions()) return;
        stopScan();
        int position = devicePicker.getSelectedItemPosition();
        if (position < 0 || position >= deviceAddresses.size()) throw new IllegalStateException("先扫描并选择设备");
        Seen seen = devices.get(deviceAddresses.get(position));
        activeModel = models.get(modelPicker.getSelectedItemPosition());
        journal.event("selected_model", "serverId", activeModel == null ? -1 : activeModel.optInt("server_id"),
                "deviceAddress", seen.device.getAddress(), "observedAuthVersion", seen.ad.authVersion);
        selectedModel.setText("本次连接车型：" + (activeModel == null ? "未知" : activeModel.optString("name_zh")));
        client.connect(seen.device, seen.name, seen.ad);
    }

    private void authenticate() {
        if (activeModel == null || activeModel.optInt("ble_protocol", -1) != 2)
            throw new IllegalStateException("本探针仅实现已知基础协议2；其余先枚举和抓HCI");
        String value = credential.getText().toString(); credential.setText("");
        byte[] password = null;
        if (!value.isEmpty()) {
            if (!value.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("蓝牙密码应为32位HEX");
            password = new byte[16];
            for (int i = 0; i < 16; i++) password[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        try {
            String profile = profilePicker.getSelectedItemPosition() == 0 ? NinebotVehicleProfile.ENCRYPTION2_STANDARD_NUS
                    : NinebotVehicleProfile.ENCRYPTION2_VCU_NUS_SETTLE;
            journal.event("selected_profile", "profile", profile, "credentialProvided", password != null);
            client.authenticate(profile, password);
        } finally { if (password != null) Arrays.fill(password, (byte) 0); }
    }

    private void requireCastModel() {
        if (activeModel == null || !activeModel.optBoolean("command_catalog_available") || activeModel.isNull("cast_commands"))
            throw new IllegalStateException("本次车型缺少已解码投屏命令表；先采集官方App HCI");
    }
    private boolean permissions() {
        String[] needed = Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : needed) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(needed, 1); append("授予权限后再点击扫描／连接"); return false;
        }
        return true;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == 11) {
                try (InputStream input = getContentResolver().openInputStream(data.getData())) { clip = new Mpeg2ProbeClip(input); }
                append("片段已加载：" + clip.width + "×" + clip.height + " " + clip.fps + "fps");
            } else if (request == 12) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) { journal.exportTo(output); }
                append("日志已导出，原始收发保留用于本地分析");
            }
        } catch (Exception error) { append("文件操作失败：" + error.getMessage()); }
    }
    @Override protected void onDestroy() {
        stopScan(); if (credential != null) credential.setText("");
        if (client != null) client.close();
        // The actor writes its final disconnect event, then closes the journal.
        super.onDestroy();
    }
    private int parseHex(EditText input) { return Integer.parseInt(input.getText().toString(), 16); }
    private void append(String text) {
        if (log == null) return;
        String current = log.getText().toString();
        if (current.length() > 18000) current = current.substring(current.length() - 12000);
        log.setText(current + "\n" + text);
    }
    private TextView label(LinearLayout column, String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); column.addView(view); return view;
    }
    private EditText field(LinearLayout column, String hint, String value) {
        label(column, hint, 13); EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(value); column.addView(edit); return edit;
    }
    private void button(LinearLayout column, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); column.addView(button);
        button.setOnClickListener(view -> { try { action.run(); } catch (Exception error) { append("未执行：" + error.getMessage()); } });
    }
    private static final class Seen {
        final BluetoothDevice device; final String name; final NinebotAdvertisement ad; final byte[] raw;
        Seen(BluetoothDevice device, String name, NinebotAdvertisement ad, byte[] raw) {
            this.device = device; this.name = name; this.ad = ad; this.raw = raw.clone();
        }
    }
}
