package com.motexa.openninebot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native diagnostic controls; Activity keeps ownership of system screen-sharing consent. */
public final class NinebotTestPanel {
    public static final int PERMISSION_REQUEST = 7211;
    private final Activity activity;
    private final NinebotTestController controller;
    private final LinearLayout root;
    private final CastUi ui;
    private LabTarget target;
    private boolean officialWifi;
    private TextView summary, status, telemetry, wifiStatus, configStatus, externalNote, connectionDetails;
    private CheckBox bms;
    private View bluetoothControls;
    private Button connect, mirror, pattern, refresh;
    private Runnable permissionContinuation;

    public NinebotTestPanel(Activity activity, NinebotTestController controller, LabTarget target) {
        this.activity = activity; this.controller = controller; ui = new CastUi(activity); root = ui.column();
        setTarget(target);
    }
    public void setTarget(LabTarget value) {
        if (target == value) return;
        if (!value.ninebot()) throw new IllegalArgumentException("请选择九号功能");
        boolean restoreOfficial = target == null && controller.snapshot().optString("workflowId").equals(value.id + ":bluetooth");
        cancelPendingActions(); target = value; officialWifi = value.wifi() && restoreOfficial; build();
    }
    private boolean directWifi() { return target.wifi() && !officialWifi; }
    private void build() {
        controller.configureTest(target, officialWifi); root.removeAllViews();
        mirror = pattern = connect = refresh = null; wifiStatus = configStatus = telemetry = null;
        if (target.wifi()) {
            root.addView(ui.hint("连接方式"));
            Spinner route = ui.spinner(new String[]{"仪表已开启投屏 · Wi-Fi 直连", "蓝牙开启投屏 → Wi-Fi"});
            route.setSelection(officialWifi ? 1 : 0); root.addView(route);
            route.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(AdapterView<?> parent) {}
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    boolean next = position == 1;
                    if (next != officialWifi) { cancelPendingActions(); officialWifi = next; build(); }
                }
            });
        }
        if (directWifi()) { buildWifiDirect(); render(); return; }
        LinearLayout data = ui.section(root, "1 · 导入九号资料");
        summary = ui.hint("尚未导入资料"); data.addView(summary);
        ui.buttons(data, ui.button("导入九号资料", true, this::showImport), button("清除资料", controller::clearProfile));
        refresh = button("刷新第 3 代授权材料", controller::refreshAuthorizedMaterials); data.addView(refresh);
        LinearLayout connection = ui.section(root, "2 · 连接车辆");
        LinearLayout scan = ui.column(); bluetoothControls = scan; connection.addView(scan);
        ui.buttons(scan, button("扫描蓝牙", () -> permissions(controller::scan)), button("选择蓝牙目标", this::chooseBluetooth));
        externalNote = ui.hint("仪表须已开启投屏；此入口不执行蓝牙认证。"); connection.addView(externalNote);
        connect = ui.button(target.casting ? "连接并读取配置" : "连接并认证", true,
                () -> { requireMatchingProfile(); permissions(target.casting ? controller::connectAndPrepare : controller::connectForRead); });
        ui.buttons(connection, connect, button("断开连接", controller::disconnect));
        status = ui.text("尚未连接", 14); connection.addView(status);
        if (target.casting) connection.addView(button("重新读取投屏配置", () -> { requireMatchingProfile(); controller.readCastConfiguration(); }));
        connectionDetails = ui.hint(""); connectionDetails.setVisibility(View.GONE); connectionDetails.setTextIsSelectable(true);
        Button showDetails = button("展开连接详情", () -> {});
        showDetails.setOnClickListener(v -> { boolean show = connectionDetails.getVisibility() != View.VISIBLE; connectionDetails.setVisibility(show ? View.VISIBLE : View.GONE); showDetails.setText(show ? "收起连接详情" : "展开连接详情"); });
        connection.addView(showDetails); connection.addView(connectionDetails);
        if (target.wifi()) {
            LinearLayout wifi = ui.section(root, "3 · 选择 Wi-Fi 仪表");
            wifi.addView(ui.hint("完成配置读取后，先通过蓝牙开启仪表投屏；收到仪表应答后，再发现并选择 Wi-Fi 仪表。"));
            wifi.addView(button("蓝牙开启仪表投屏", () -> { requireMatchingProfile(); controller.openCastMode(); }));
            ui.buttons(wifi, button("发现仪表", () -> { requireMatchingProfile(); permissions(controller::discoverWifi); }), button("选择仪表", this::chooseWifi));
            wifiStatus = ui.hint("尚未选择 Wi-Fi 仪表"); wifi.addView(wifiStatus);
        }
        if (target.casting) {
            LinearLayout cast = ui.section(root, target.wifi() ? "4 · 开始投屏" : "3 · 开始半彩投屏");
            configStatus = ui.hint("请先完成上方连接及配置读取。"); cast.addView(configStatus);
            mirror = ui.button("手机屏幕镜像", true, () -> startCasting(NinebotTestController.FrameMode.MIRROR));
            pattern = button("动态示例画面", () -> startCasting(NinebotTestController.FrameMode.TEST_PATTERN));
            ui.buttons(cast, mirror, pattern); cast.addView(button("停止投屏", controller::stopCasting));
            cast.addView(ui.hint("镜像需系统录屏授权；动态示例画面用于对比编码与链路。仪表实际显示请现场确认。"));
        }
        if (!target.casting) buildVehicleRead();
        render();
    }
    private void buildWifiDirect() {
        LinearLayout wifi = ui.section(root, "1 · 发现并选择 Wi-Fi 仪表");
        wifi.addView(ui.hint("先在仪表或官方 App 中开启投屏。发现只扫描，不会自动连接；选择目标后，点击下方投屏按钮才建立 Wi-Fi Direct。此入口无需导入车辆资料或车辆蓝牙。"));
        ui.buttons(wifi, ui.button("发现车辆 Wi-Fi", true, () -> permissions(controller::discoverWifi)), button("选择车辆 Wi-Fi", this::chooseWifi));
        wifiStatus = ui.text("尚未选择 Wi-Fi 仪表", 14); wifi.addView(wifiStatus);
        status = ui.hint(""); wifi.addView(status);
        wifi.addView(button("断开 Wi-Fi 投屏", controller::disconnect));
        LinearLayout cast = ui.section(root, "2 · 开始 Wi-Fi 投屏");
        configStatus = ui.hint(""); cast.addView(configStatus);
        mirror = ui.button("手机屏幕镜像", true, () -> startCasting(NinebotTestController.FrameMode.MIRROR));
        pattern = button("动态示例画面", () -> startCasting(NinebotTestController.FrameMode.TEST_PATTERN));
        ui.buttons(cast, mirror, pattern); cast.addView(button("停止投屏", controller::stopCasting));
        cast.addView(ui.hint("选择画面来源后建立 Wi-Fi Direct 连接；按系统提示完成配对。预设不代表仪表已确认兼容，实际显示请现场核对。"));
    }
    private void buildVehicleRead() {
        LinearLayout read = ui.section(root, "3 · 读取车辆信息");
        read.addView(ui.hint("按需读取，不自动持续轮询；不修改车辆设置。"));
        bms = new CheckBox(activity); bms.setText("附加电池信息：电压、电量、温度"); bms.setTextSize(13); bms.setTextColor(ui.foreground); read.addView(bms);
        ui.buttons(read, button("读取一轮", () -> controller.readVehicle(false, bms.isChecked(), 0x07)),
                button("持续读取", () -> controller.readVehicle(true, bms.isChecked(), 0x07)));
        read.addView(button("停止读取", controller::stopReading));
        telemetry = ui.hint("连接认证后，点击读取。"); telemetry.setTextIsSelectable(true); read.addView(telemetry);
    }
    public View view() { return root; }
    public void cancelPendingActions() { permissionContinuation = null; }
    public void render() {
        JSONObject data = controller.snapshot(); JSONObject profile = data.optJSONObject("profile");
        if (directWifi()) { renderWifiDirect(data, profile); return; }
        boolean external = profile != null && profile.optInt("authVersion", -1) == 0;
        String profileText = profile == null ? "尚未导入资料\n九号蓝牙加密版本：待核对" : "九号蓝牙加密版本：" + encryptionLabel(profile.optInt("authVersion", -1));
        if (profile != null && !profile.optString("deviceName").isEmpty()) profileText += "\n资料目标：" + profile.optString("deviceName");
        boolean matching = profileMatches(profile);
        if (profile != null && !matching) profileText += "\n保存资料与当前功能不同，请导入对应资料。";
        JSONObject selected = data.optJSONObject("selected");
        summary.setText(profileText);
        refresh.setVisibility(profile != null && profile.optInt("authVersion") == 3 ? View.VISIBLE : View.GONE);
        bluetoothControls.setVisibility(external ? View.GONE : View.VISIBLE); externalNote.setVisibility(external ? View.VISIBLE : View.GONE);
        connect.setText(external ? "启用外部投屏" : target.casting ? "连接并读取配置" : "连接并认证");
        ui.enabled(connect, matching && !data.optBoolean("connected"));
        String text = stageLabel(data.optString("stage"));
        if (selected != null && !external) text += "\n" + selected.optString("name") + " · 广播：" + encryptionLabel(selected.optInt("authVersion", -1));
        if (data.optBoolean("authenticated")) text += "\n蓝牙认证通过" + (target.casting ? (data.optBoolean("prepared") ? " · 本地准备完成" : " · 等待本地准备") : "");
        if (data.optString("stage").equals("failed")) text += "\n" + data.optString("detail");
        else if (!data.optString("lastError").isEmpty()) text += "\n操作未完成：" + data.optString("lastError");
        status.setText(text);
        connectionDetails.setText("诊断：" + data.optString("detail") + "\n实际 ATT MTU：" + data.optInt("attMtu", 23)
                + " · 待写：" + data.optInt("queueLength") + "\n本地只读准备：" + (data.optBoolean("prepared") ? "已完成" : "未完成")
                + "\n账号绑定检查：未执行\n连接信息上报：未执行");
        if (wifiStatus != null) setWifiStatus(data);
        JSONObject config = data.optJSONObject("configuration");
        if (configStatus != null) {
            boolean routeMatches = config == null || target.transport.equals(transport(config.optInt("channel", -1)));
            String description = config == null ? external ? "采用导入的外部投屏参数。" : "请先完成上方连接及配置读取。"
                    : config.optInt("width") + " × " + config.optInt("height") + " · " + (config.optInt("codec") == 0 ? "MPEG2" : "H.264")
                    + " · " + config.optInt("fps") + " fps · " + config.optInt("bitrate") / 1000 + " kbps";
            if (!routeMatches) description += "\n仪表返回的传输方式与当前选择不同，请切换到对应投屏方式。";
            configStatus.setText(description);
            boolean ready = matching && routeMatches && !data.optBoolean("casting") && (external ? data.optBoolean("connected") : config != null);
            if (target.wifi() && !external) {
                String nativeStage = data.optString("nativeStage");
                ready &= (nativeStage.equals("cast_mode_ready") || nativeStage.equals("casting_ready"))
                        && !data.optString("p2pName").isEmpty() && !data.optString("p2pAddress").isEmpty();
            }
            ui.enabled(mirror, ready); ui.enabled(pattern, ready);
        }
        if (telemetry == null) return;
        JSONObject fields = data.optJSONObject("telemetry"); StringBuilder values = new StringBuilder();
        if (fields != null) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject field = fields.optJSONObject(key); if (field == null) continue;
                values.append(key).append(": ").append(field.optBoolean("valid")
                        ? field.has("values") ? field.opt("values") : field.opt("value") : "未确认 / " + field.optString("hex"))
                        .append(' ').append(field.optString("unit")).append(" [board=").append(field.optInt("target"))
                        .append(" reg=").append(field.optInt("register")).append("]\n");
            }
        }
        telemetry.setText(values.length() == 0 ? "连接认证后，点击读取；未收到的字段不会显示为零。" : values.toString());
    }
    private void renderWifiDirect(JSONObject data, JSONObject profile) {
        boolean matching = profileMatches(profile) && data.optString("workflowId").equals(target.id + ":wifi");
        String name = data.optString("p2pName"), address = data.optString("p2pAddress");
        setWifiStatus(data);
        String state = stageLabel(data.optString("stage"));
        if (!data.optString("lastError").isEmpty()) state += "\n" + data.optString("lastError");
        else if (data.optString("stage").equals("failed")) state += "\n" + data.optString("detail");
        status.setText(state);
        NinebotTestProfile preset = NinebotTestProfile.wifiPreset(target == LabTarget.NINEBOT_SEVEN);
        configStatus.setText("请求参数：" + preset.width + " × " + preset.height + " · H.264 · " + preset.fps + " fps · 2000 kbps\n"
                + (target == LabTarget.NINEBOT_SEVEN ? "RTP / KCP" : "RTP / UDP") + " · Pro 兼容格式\n不执行蓝牙认证、配置读取或车辆信息轮询。");
        boolean ready = matching && !name.isEmpty() && !address.isEmpty() && !data.optBoolean("casting");
        ui.enabled(mirror, ready); ui.enabled(pattern, ready);
    }
    private void setWifiStatus(JSONObject data) {
        if (wifiStatus == null) return;
        String selected = data.optString("p2pName").isEmpty() ? "尚未选择车辆 Wi-Fi" : "已选：" + data.optString("p2pName") + "\n" + data.optString("p2pAddress");
        JSONObject scan = data.optJSONObject("wifiDiscovery");
        if (scan == null || scan.length() == 0) { wifiStatus.setText(selected); return; }
        int count = scan.optInt("count", data.optJSONArray("wifiPeers") == null ? 0 : data.optJSONArray("wifiPeers").length());
        int eligible = scan.optInt("eligibleCount", count);
        long firstResult = scan.optLong("firstResultElapsedMs", -1);
        String elapsed = firstResult < 0 ? "等待返回" : firstResult + " ms";
        String filter = "complete_name_mac_and_configured_target".equals(scan.optString("filterPolicy")) ? "完整名称 + MAC + 资料匹配" : "完整名称 + MAC（品牌待核对）";
        wifiStatus.setText(selected + "\n筛选：" + filter + "\n候选 " + count + " · 可选 " + eligible + " · 首次结果返回 " + elapsed
                + "\nRSSI：Wi-Fi Direct API 未提供；连接耗时、频率和路由见日志");
    }
    private boolean profileMatches(JSONObject profile) {
        return profile != null && (directWifi() ? profile.optInt("authVersion", -1) == 0 && target.transport.equals(profile.optString("transport"))
                : profile.optInt("authVersion", 0) != 0 && (!target.casting || target.transport.equals(profile.optString("transport"))));
    }
    private void requireMatchingProfile() {
        if (!profileMatches(controller.snapshot().optJSONObject("profile"))) throw new IllegalStateException("请先导入与当前功能对应的九号资料");
    }
    private void startCasting(NinebotTestController.FrameMode mode) {
        requireMatchingProfile(); permissions(() -> controller.startCasting(mode));
    }
    private static String encryptionLabel(int version) {
        return version == 2 ? "第 2 代（Gen2）" : version == 3 ? "第 3 代（Gen3）" : version == 0 ? "外部投屏，无蓝牙认证" : "未识别";
    }
    private static String transport(int channel) { return channel == 0 ? "ble" : channel == 1 ? "udp" : channel == 2 ? "kcp" : "unknown"; }
    private static String stageLabel(String stage) {
        switch (stage) {
            case "scanning": return "正在扫描蓝牙";
            case "scan_complete": return "扫描完成，请选择蓝牙目标";
            case "device_selected": return "已选择蓝牙目标";
            case "material_saved": return "九号资料已保存";
            case "material_refreshing": return "正在刷新授权材料";
            case "material_refresh_failed": return "授权材料刷新失败，请查看详情";
            case "scan_failed": return "蓝牙扫描失败，请查看详情";
            case "authorization_expired": return "连接服务不可用";
            case "connecting": case "subscribing": return "正在建立蓝牙连接";
            case "wait_precomm": case "wait_auth": case "auth_key_selected": return "正在进行蓝牙认证";
            case "wait_gen3_fast": case "wait_gen3_full": case "wait_gen3_provider": return "正在进行第 3 代蓝牙认证";
            case "wait_gen3_vehicle_confirmation": return "请在车辆端确认认证";
            case "wait_prepare": return "正在校验本地只读准备";
            case "wait_config_begin": case "wait_config_data": case "wait_config_verify": return "正在读取并校验仪表配置";
            case "authenticated": return "蓝牙认证已完成";
            case "p2p_connecting": case "p2p_releasing": return "正在连接 Wi-Fi 仪表";
            case "wait_mode": return "等待仪表确认投屏模式";
            case "cast_mode_ready": return "仪表已应答开投屏 · 请发现并选择 Wi-Fi";
            case "casting_ready": return "投屏中 · 请确认仪表画面";
            case "wait_read": return "正在读取车辆信息";
            case "external_mode_ready": return "外部投屏入口已就绪";
            case "wifi_ready": return "请发现并选择已开启投屏的 Wi-Fi 仪表";
            case "failed": return "操作未完成，请查看原因";
            case "stopped": return "已停止";
            default: return "尚未连接";
        }
    }
    private void showImport() {
        String[] labels = { "蓝牙加密第 2 代", "蓝牙加密第 3 代 · 缓存材料", "蓝牙加密第 3 代 · 授权接口" };
        new AlertDialog.Builder(activity).setTitle(target.label + " · 导入模板").setItems(labels, (dialog, which) -> editImport(template(target, which))).show();
    }
    private void editImport(String template) {
        EditText input = new EditText(activity); input.setText(template); input.setTextSize(12); input.setMinLines(9); input.setMaxLines(18);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSelectAllOnFocus(true);
        LinearLayout wrapper = new LinearLayout(activity); wrapper.setPadding(dp(16), 0, dp(16), 0); wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("导入本人获授权的九号资料").setView(wrapper)
                .setMessage("替换模板占位符，或粘贴完整 JSON。Gen2 password 与 Gen3 appEnc 不可互换；不会自动创建或覆盖车辆密码。")
                .setPositiveButton("加密保存", null).setNegativeButton("取消", (d, which) -> input.setText("")).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { controller.importProfile(input.getText().toString()); input.setText(""); dialog.dismiss(); }
            catch (RuntimeException error) { toast(error.getMessage()); }
        }));
        dialog.setOnDismissListener(d -> input.setText("")); dialog.show();
    }
    private static String template(LabTarget target, int which) {
        boolean kcp = target.transport.equals("kcp"), ble = target.transport.equals("ble");
        JSONObject credentials;
        if (which == 1 || which == 2) {
            JSONObject v3 = NinebotTestProfile.json("sn", "请填车辆SN", "authBoard", "请改为已核验的板ID整数0至255",
                    "authKey1Hex", "请填32位HEX的已核验authKey1", "metadata", new JSONObject());
            try {
                if (which == 2) v3.put("authApi", NinebotTestProfile.json("adapter", AuthorizedNinebotProvider.ADAPTER,
                        "endpoint", "https://请替换已授权服务地址/", "token", "请填本人已获授权的服务token"));
                else v3.put("curAppEncHex", "请填32位HEX的已授权缓存appEnc");
            } catch (Exception ignored) {}
            credentials = NinebotTestProfile.json("v3", v3);
        } else credentials = NinebotTestProfile.json("passwordHex", "请填32位HEX的已有password");
        JSONObject root = NinebotTestProfile.json("schemaVersion", 1, "label", target.label,
                "deviceName", "请填当前原始广播名称", "bluetoothAddress", "",
                "profileId", "encryption2_standard_nus", "modelId", "请填型号ID",
                "authVersion", which == 1 || which == 2 ? 3 : 2,
                "transport", target.transport, "wireProfile", "official",
                "width", ble ? 240 : kcp ? 1024 : 848, "height", ble ? 320 : kcp ? 600 : 480,
                "fps", ble ? 5 : kcp ? 25 : 20, "bitrate", 2000000,
                "p2pName", "", "p2pAddress", "", "externalCastingEnabled", false,
                "credentials", credentials);
        try { return root.toString(2); } catch (Exception ignored) { return root.toString(); }
    }
    private void chooseBluetooth() {
        JSONArray rows = controller.snapshot().optJSONArray("devices");
        if (rows == null || rows.length() == 0) { toast("请先扫描蓝牙"); return; }
        String[] labels = new String[rows.length()];
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); labels[i] = row.optString("name", "无名")
                + "\n" + row.optString("address") + " · " + row.optInt("rssi") + "dBm · " + encryptionLabel(row.optInt("authVersion", -1)); }
        new AlertDialog.Builder(activity).setTitle("选择九号蓝牙目标").setItems(labels, (dialog, index) -> controller.selectDevice(rows.optJSONObject(index).optString("address"))).show();
    }
    private void chooseWifi() {
        JSONObject snapshot = controller.snapshot();
        JSONArray rows = snapshot.optJSONArray("wifiPeers");
        if (rows == null || rows.length() == 0) { toast("请先发现 Wi-Fi Direct 仪表"); return; }
        JSONObject profile = snapshot.optJSONObject("profile");
        String expectedName = profile == null ? "" : profile.optString("p2pName"), expectedAddress = profile == null ? "" : profile.optString("p2pAddress");
        List<JSONObject> eligible = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null || !row.optBoolean("eligible", true)) continue;
            if (!NinebotP2pTransport.peerRejectionReason(row.optString("name"), row.optString("address"), expectedName, expectedAddress).isEmpty()) continue;
            eligible.add(row);
        }
        boolean configured = !expectedName.isEmpty() || !expectedAddress.isEmpty();
        if (eligible.isEmpty()) { toast(configured ? "未发现资料指定的车辆 Wi-Fi" : "未发现具有完整名称和 MAC 的候选，请确认仪表已开启投屏"); return; }
        String[] labels = new String[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) { JSONObject row = eligible.get(i); labels[i] = row.optString("name") + "\n" + row.optString("address")
                + " · status=" + row.optInt("status") + " · type=" + row.optString("deviceType", "unknown")
                + (configured ? " · 已匹配资料" : " · 品牌待核对"); }
        new AlertDialog.Builder(activity).setTitle("选择车辆 Wi-Fi（完整名称 + MAC）").setItems(labels, (dialog, index) -> {
            JSONObject row = eligible.get(index); controller.selectWifi(row.optString("name"), row.optString("address"));
        }).show();
    }
    private void permissions(Runnable action) {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions(Build.VERSION.SDK_INT, !directWifi(), target.wifi())) addMissing(missing, permission);
        if (missing.isEmpty()) { action.run(); return; }
        permissionContinuation = action; activity.requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
    }
    static String[] requiredPermissions(int sdk, boolean bluetooth, boolean wifi) {
        List<String> values = new ArrayList<>();
        if (bluetooth && sdk >= 31) { values.add(Manifest.permission.BLUETOOTH_SCAN); values.add(Manifest.permission.BLUETOOTH_CONNECT); }
        values.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (sdk >= 33) { if (wifi) values.add(Manifest.permission.NEARBY_WIFI_DEVICES); values.add(Manifest.permission.POST_NOTIFICATIONS); }
        return values.toArray(new String[0]);
    }
    private void addMissing(List<String> missing, String permission) {
        if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
    }
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != PERMISSION_REQUEST) return false;
        controller.recordPermissionResult(permissions, results); Runnable continuation = permissionContinuation; permissionContinuation = null;
        for (int i = 0; i < permissions.length; i++) {
            if (i >= results.length || results[i] != PackageManager.PERMISSION_GRANTED) {
                if (!permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS)) { toast("附近设备或定位权限未允许，操作已停止"); return true; }
            }
        }
        if (continuation != null) continuation.run(); return true;
    }
    private Button button(String label, Runnable action) {
        return ui.button(label, false, action);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(activity, message == null ? "操作失败" : message, Toast.LENGTH_LONG).show(); }
}
