package com.motexa.openninebot;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Typed imported test material. Secret JSON is never used as a diagnostic string. */
public final class NinebotTestProfile {
    public static final int MAX_IMPORT_CHARS = 65536;
    public final String label, deviceName, bluetoothAddress, profileId, modelId;
    public final String transport, wireProfile, p2pName, p2pAddress;
    public final int authVersion, width, height, fps, bitrate;
    public final boolean externalCastingEnabled;
    private final JSONObject material;

    private NinebotTestProfile(JSONObject root) throws Exception {
        allowed(root, "schemaVersion", "label", "deviceName", "bluetoothAddress", "profileId", "modelId",
                "authVersion", "transport", "wireProfile", "p2pName", "p2pAddress", "width", "height",
                "fps", "bitrate", "externalCastingEnabled", "credentials");
        if (integer(root, "schemaVersion", 1, 1, 1) != 1) throw invalid("schemaVersion 必须为 1");
        label = string(root, "label", "九号车辆", 80);
        deviceName = string(root, "deviceName", "", 120);
        bluetoothAddress = mac(string(root, "bluetoothAddress", "", 17));
        profileId = string(root, "profileId", "encryption2_standard_nus", 80);
        if (!profileId.equals("encryption2_standard_nus") && !profileId.equals("encryption2_standard_nus_immediate")
                && !profileId.equals("encryption2_vcu_nus_settle") && !profileId.equals("encryption2_mmax90_capture_nus"))
            throw invalid("profileId 必须为已核验的 NUS 配置");
        modelId = string(root, "modelId", "", 32);
        if (!root.has("profileId") || !root.has("authVersion")) throw invalid("必须显式填写 profileId 和 authVersion，不自动猜测认证协议");
        authVersion = integer(root, "authVersion", 2, 0, 3);
        if (authVersion != 0 && authVersion != 2 && authVersion != 3) throw invalid("仅支持明确的认证版本 0、2、3");
        transport = choice(root, "transport", "ble", "ble", "udp", "kcp");
        wireProfile = choice(root, "wireProfile", "official", "official", "pro");
        p2pName = string(root, "p2pName", "", 128);
        p2pAddress = mac(string(root, "p2pAddress", "", 17));
        externalCastingEnabled = bool(root, "externalCastingEnabled", false);
        width = integer(root, "width", "ble".equals(transport) ? 240 : "kcp".equals(transport) ? 1024 : 848, 16, 4096);
        height = integer(root, "height", "ble".equals(transport) ? 320 : "kcp".equals(transport) ? 600 : 480, 16, 4096);
        fps = integer(root, "fps", "ble".equals(transport) ? 5 : "kcp".equals(transport) ? 25 : 20, 1, 60);
        bitrate = integer(root, "bitrate", 2000000, 16000, 20000000);
        if (authVersion == 0 && (!externalCastingEnabled || !wireProfile.equals("pro") || transport.equals("ble")))
            throw invalid("免 BLE 入口必须显式确认仪表已开启投屏，且使用 pro Wi-Fi 配置");
        if (authVersion != 0 && externalCastingEnabled) throw invalid("外部已开投屏与车辆认证入口须分别选择");
        JSONObject credentials = object(root, "credentials", true);
        allowed(credentials, "passwordHex", "v3");
        if (authVersion == 2) {
            if (credentials.has("v3")) throw invalid("Gen2 password 与 Gen3 材料不能混用");
            hex32(string(credentials, "passwordHex", "", 32), "Gen2 passwordHex");
        } else if (authVersion == 3) {
            if (credentials.has("passwordHex")) throw invalid("Gen3 不能使用旧 password 替代 appEnc");
            JSONObject v3 = object(credentials, "v3", false);
            allowed(v3, "sn", "appEncHex", "curAppEncHex", "nextAppEncHex", "authBoard", "authKey1Hex", "metadata", "sdkToken", "authApi");
            if (string(v3, "sn", "", 120).isEmpty()) throw invalid("Gen3 必须提供 sn");
            boolean hasKey = false;
            for (String key : new String[] { "appEncHex", "curAppEncHex", "nextAppEncHex" }) {
                if (v3.has(key)) { hex32(string(v3, key, "", 32), "Gen3 " + key); hasKey = true; }
            }
            if (!hasKey && (!v3.has("authApi") || !v3.has("authBoard") || !v3.has("authKey1Hex")))
                throw invalid("Gen3 无缓存密钥时须提供授权接口及明确 authBoard/authKey1Hex");
            if (v3.has("authBoard") != v3.has("authKey1Hex")) throw invalid("Gen3 authBoard 与 authKey1Hex 必须同时提供");
            if (v3.has("authBoard")) {
                integer(v3, "authBoard", 0, 0, 255);
                hex32(string(v3, "authKey1Hex", "", 32), "Gen3 authKey1Hex");
            }
            if (v3.has("metadata")) object(v3, "metadata", false);
            if (v3.has("sdkToken")) string(v3, "sdkToken", "", 8192);
            if (v3.has("authApi")) {
                JSONObject api = object(v3, "authApi", false);
                allowed(api, "endpoint", "token", "adapter");
                URI uri = new URI(string(api, "endpoint", "", 2048));
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null) throw invalid("合法认证材料接口必须为 HTTPS URL");
                String token = string(api, "token", "", 8192);
                if (token.isEmpty() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) throw invalid("授权接口 token 必须为单行非空字符串");
                if (!AuthorizedNinebotProvider.ADAPTER.equals(string(api, "adapter", "", 120)))
                    throw invalid("授权接口须使用 authorized-ninebot-v1 合同");
            }
        } else if (credentials.length() != 0) throw invalid("外部投屏模式不接受车辆密钥");
        material = new JSONObject(root.toString());
    }

    public static NinebotTestProfile parse(String text) {
        try {
            if (text == null || text.length() > MAX_IMPORT_CHARS) throw invalid("资料为空或超过 64 KiB");
            return new NinebotTestProfile(strictObject(text));
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw invalid("九号资料格式错误，请核对字段类型与 JSON"); }
    }

    /** Media-only preset, created in memory; never replaces stored Bluetooth credentials. */
    static NinebotTestProfile wifiPreset(boolean kcp) {
        return parse(json("schemaVersion", 1, "label", kcp ? "九号 7 英寸 Wi-Fi 投屏" : "九号 5 英寸 Wi-Fi 投屏",
                "profileId", "encryption2_standard_nus", "authVersion", 0,
                "transport", kcp ? "kcp" : "udp", "wireProfile", "pro", "externalCastingEnabled", true,
                "width", kcp ? 1024 : 848, "height", kcp ? 600 : 480, "fps", kcp ? 25 : 20,
                "bitrate", 2000000, "credentials", new JSONObject()).toString());
    }

    JSONObject options(String actualName, int actualAuthVersion, int mtu) {
        JSONObject options = json("authVersion", actualAuthVersion, "deviceName", actualName, "profileId", profileId,
                "modelId", modelId, "transport", transport, "wireProfile", wireProfile, "width", width,
                "height", height, "fps", fps, "bitrate", bitrate, "attMtu", mtu,
                "externalCastingEnabled", externalCastingEnabled);
        if (authVersion == 3 && actualAuthVersion == 3) {
            JSONObject v3 = credentials().optJSONObject("v3");
            if (v3 != null && v3.has("authBoard") && v3.has("authKey1Hex")) {
                try { options.put("authBoard", v3.getInt("authBoard")).put("authKey1Hex", v3.getString("authKey1Hex")); }
                catch (JSONException impossible) { throw invalid("Gen3 导入参数缺失"); }
            }
        }
        return options;
    }

    JSONObject credentials() {
        try { return new JSONObject(material.getJSONObject("credentials").toString()); }
        catch (JSONException impossible) { throw new IllegalStateException("credential-object-invalid"); }
    }
    NinebotTestProfile withV3(JSONObject v3) {
        try {
            JSONObject updated = new JSONObject(material.toString());
            updated.put("credentials", json("v3", new JSONObject(v3.toString())));
            return parse(updated.toString());
        } catch (JSONException invalid) { throw invalid("授权服务返回的材料格式错误"); }
    }
    /** The service may update material for this SN, never the destination or its access token. */
    NinebotTestProfile withAuthorizedRefresh(JSONObject result) {
        if (authVersion != 3) throw invalid("授权刷新仅用于 Gen3 材料");
        allowed(result, "v3");
        JSONObject returned = object(result, "v3", false);
        allowed(returned, "sn", "appEncHex", "curAppEncHex", "nextAppEncHex", "metadata", "sdkToken");
        JSONObject current = credentials().optJSONObject("v3");
        if (current == null || !current.optString("sn").equals(returned.opt("sn"))) throw invalid("授权材料 SN 与当前车辆不一致");
        JSONObject updated;
        try {
            updated = new JSONObject(returned.toString());
            if (current.has("authApi")) updated.put("authApi", new JSONObject(current.getJSONObject("authApi").toString()));
            if (current.has("authBoard")) updated.put("authBoard", current.getInt("authBoard")).put("authKey1Hex", current.getString("authKey1Hex"));
        } catch (JSONException invalid) { throw invalid("授权材料格式错误"); }
        return withV3(updated);
    }
    NinebotTestProfile withGen3KeyState(JSONObject state) {
        if (authVersion != 3 || !state.optBoolean("encryptedProof")) throw invalid("Gen3 密钥槽更新必须具备加密响应证明");
        JSONObject current = credentials().optJSONObject("v3");
        if (current == null) throw invalid("Gen3 当前材料缺失");
        JSONObject updated;
        try { updated = new JSONObject(current.toString()); }
        catch (JSONException invalid) { throw invalid("Gen3 当前材料格式错误"); }
        String promoted = state.optBoolean("promoteNext") ? updated.optString("nextAppEncHex") : "";
        if (state.optBoolean("promoteNext")) {
            hex32(promoted, "Gen3 待提升材料");
            try { updated.put("curAppEncHex", promoted); }
            catch (JSONException invalid) { throw invalid("Gen3 密钥提升失败"); }
        }
        if (state.optBoolean("promoteNext") || state.optBoolean("removeNext")) updated.remove("nextAppEncHex");
        return withV3(updated);
    }
    NinebotTestProfile withGen3Credentials(JSONObject action) {
        if (authVersion != 3 || !action.optBoolean("encryptedProof")) throw invalid("Gen3 材料更新必须具备加密响应证明");
        JSONObject delta = object(action, "v3", false);
        allowed(delta, "sn", "curAppEncHex");
        JSONObject current = credentials().optJSONObject("v3");
        if (current == null || !current.optString("sn").equals(delta.opt("sn"))) throw invalid("Gen3 材料 SN 与当前车辆不一致");
        String appEnc = string(delta, "curAppEncHex", "", 32); hex32(appEnc, "Gen3 新材料");
        JSONObject updated;
        try { updated = new JSONObject(current.toString()); updated.put("curAppEncHex", appEnc); }
        catch (JSONException invalid) { throw invalid("Gen3 材料更新格式错误"); }
        if (action.optBoolean("removeNext")) updated.remove("nextAppEncHex");
        return withV3(updated);
    }
    static JSONObject strictObject(String text) throws JSONException {
        JSONTokener parser = new JSONTokener(text);
        Object root = strictValue(parser, 0);
        if (!(root instanceof JSONObject) || parser.nextClean() != 0) throw invalid("必须为单个 JSON 对象");
        return (JSONObject) root;
    }
    String saved() { return material.toString(); }
    JSONObject summary() { return json("label", label, "deviceName", deviceName, "bluetoothAddress", bluetoothAddress,
            "modelId", modelId, "authVersion", authVersion, "transport", transport, "wireProfile", wireProfile,
            "p2pName", p2pName, "p2pAddress", p2pAddress, "width", width, "height", height,
            "fps", fps, "bitrate", bitrate, "hasCredentials", authVersion != 0,
            "externalCastingEnabled", externalCastingEnabled); }
    @Override public String toString() { return summary().toString(); }

    static JSONObject json(Object... values) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) value.put(String.valueOf(values[i]), values[i + 1]); }
        catch (JSONException impossible) { throw new IllegalArgumentException("invalid-json-value"); }
        return value;
    }
    private static void allowed(JSONObject value, String... keys) {
        Set<String> set = new HashSet<>(Arrays.asList(keys));
        Iterator<String> names = value.keys();
        while (names.hasNext()) if (!set.contains(names.next())) throw invalid("存在未知字段，请按九号导入模板填写");
    }
    private static String string(JSONObject value, String key, String fallback, int maximum) {
        if (!value.has(key)) return fallback;
        Object raw = value.opt(key);
        if (!(raw instanceof String) || ((String) raw).length() > maximum) throw invalid(key + " 必须为有界字符串");
        return (String) raw;
    }
    private static int integer(JSONObject value, String key, int fallback, int minimum, int maximum) {
        if (!value.has(key)) return fallback;
        Object raw = value.opt(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw invalid(key + " 必须为整数");
        long number = ((Number) raw).longValue();
        if (number < minimum || number > maximum) throw invalid(key + " 超出支持范围");
        return (int) number;
    }
    private static boolean bool(JSONObject value, String key, boolean fallback) {
        if (!value.has(key)) return fallback;
        if (!(value.opt(key) instanceof Boolean)) throw invalid(key + " 必须为布尔值");
        return (Boolean) value.opt(key);
    }
    private static JSONObject object(JSONObject value, String key, boolean optional) {
        if (optional && !value.has(key)) { JSONObject empty = new JSONObject(); try { value.put(key, empty); } catch (JSONException ignored) {} return empty; }
        Object raw = value.opt(key);
        if (!(raw instanceof JSONObject)) throw invalid(key + " 必须为对象");
        return (JSONObject) raw;
    }
    private static String choice(JSONObject value, String key, String fallback, String... valid) {
        String selected = string(value, key, fallback, 32);
        if (!Arrays.asList(valid).contains(selected)) throw invalid(key + " 不受支持");
        return selected;
    }
    private static String mac(String value) {
        if (!value.isEmpty() && !value.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) throw invalid("蓝牙/P2P 地址格式错误");
        return value.toUpperCase(Locale.ROOT);
    }
    private static void hex32(String value, String label) {
        if (!value.matches("(?i)[0-9a-f]{32}")) throw invalid(label + " 必须为 32 位 HEX 文本，不能填 token 或 UUID");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    /** JSONObject normally accepts duplicate names; imported key slots must be unambiguous. */
    private static Object strictValue(JSONTokener parser, int depth) throws JSONException {
        if (depth > 12) throw invalid("JSON 嵌套过深");
        char first = parser.nextClean();
        if (first == '{') {
            JSONObject result = new JSONObject();
            Set<String> names = new HashSet<>();
            char next = parser.nextClean();
            if (next == '}') return result;
            parser.back();
            for (;;) {
                if (parser.nextClean() != '"') throw invalid("JSON 字段名必须使用双引号");
                String name = parser.nextString('"');
                if (!names.add(name)) throw invalid("JSON 含重复字段");
                if (parser.nextClean() != ':') throw invalid("JSON 缺少冒号");
                result.put(name, strictValue(parser, depth + 1));
                next = parser.nextClean();
                if (next == '}') return result;
                if (next != ',') throw invalid("JSON 对象格式错误");
            }
        }
        if (first == '[') {
            JSONArray result = new JSONArray();
            char next = parser.nextClean();
            if (next == ']') return result;
            parser.back();
            for (;;) {
                if (result.length() >= 512) throw invalid("JSON 数组过长");
                result.put(strictValue(parser, depth + 1));
                next = parser.nextClean();
                if (next == ']') return result;
                if (next != ',') throw invalid("JSON 数组格式错误");
            }
        }
        if (first == '"') return parser.nextString('"');
        if (first == 0) throw invalid("JSON 值缺失");
        parser.back();
        Object scalar = parser.nextValue();
        if (scalar instanceof String || scalar instanceof JSONObject || scalar instanceof JSONArray) throw invalid("JSON 字符串必须使用双引号");
        return scalar;
    }
}
