# 官方 Gen3 / V2_3 认证连接复现证据

本分支只读分析官方 Android 6.10.11，执行原始 ARM64 指令的局部分段验证；没有车辆、账户、云请求或真实凭据。记录中的 SN、key 和 token 均为字段名或合成测试材料。完整车辆互通尚未验证。

## 1. 样本与命名

- APK：`tmp/ninebot-gen3-20261009/official-6.10.11.apk`，包名 `cn.ninebot.ninebot`，versionCode `610114116`。
- APK SHA-256：`02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`。
- 本次执行 ELF：`artifacts/ninebot-ble-cast-20261115/raw/native/librust_lib.so`，arm64-v8a。
- ELF SHA-256：`2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`。
- RustBridge 版本字符串：`2.1.157.1263-a20e5d6`。

此处 Gen3 指官方 `encrypt_protocol_version = 3`。native 的协议枚举包含 `V1_0`、`V2_2`、`V2_0`、`V2_3`、`V3_2`；它们不能仅按名字中的数字 3 合并：

| native 枚举 discriminant | Debug 名称 | 证据 |
|---|---|---|
| 0 | V1_0 | `0x38cbc0`，字符串 `0xd680f` |
| 1 | V2_2 | 同函数，`0xd6813` |
| 2 | V2_0 | 同函数，`0xd6817` |
| 3 | V2_3 | 同函数，`0xd681b` |
| >=4 的该 formatter 分支 | V3_2 | 同函数，`0xd681f`；有效枚举集合仍由构造方约束 |

`0x388f44` 在 discriminant 3 分支调用 `0x38a818`，挂载 vtable `0x6582f0`；其 Debug 槽 `+0x18` 是 `0x38d7e8`，输出 `HandshakeV2_3`。这证明 native 内部枚举 3 与所追 `v2_3.*` 握手的关联。实际广告 `encrypt_protocol_version=3` 到该 native 构造参数的完整运行态映射尚未逐段闭合；Java 类选择、key 注入版本与 native 内部枚举证据应分层表述。`V3_2` 进入另一分支，不可混称为同一握手。

GitHub 项目中的 `gen3` 名称、MAX G3 产品名、官方 V3Auth 和 native `V3_2` 必须分别判断。

## 2. 先区分四种材料

| 材料 | 来源与用途 | 可否本地自行生成 |
|---|---|---|
| `session_id` / UUID `_user`、`_auto` | 官方连接追踪标识 | 可以；不是车辆密钥 |
| `encrypt_param` / `ENCRYPT2_PWD_HEX` | `BleSdkClient.connectFull` 仍传入的兼容材料 | 官方可由 provider 取存储值或生成；不证明它能替代 V3 key |
| SDK 激活 `token` | `BleSdkSessionTokenManager` 请求后调用 `activate` | 需有效 SDK/应用会话；与某车 `appEnc` 不同 |
| V3 `appEnc` / `session_key_hex` | 按 SN 从已授权会话取得，注入 native 安全存储 | 不能由随机 UUID、车辆名或旧密码代替 |

`deviceEnc`、`nonce`、`aad`、`efTimestamp`、`exTime`、`reTime` 属于同一云密钥分发响应的其他材料，刷新/重新分发流程需保留。已有合法 key 的 6005 生成器只使用解码后的 16 字节 key、本地时间和本地生成的 8 字节 nonce；不能把云响应中的 `nonce` 与该次挑战随机数混为一值，也不能由刷新所需字段推导出每次挑战都依赖云请求。

Java 证据：

- `tmp/ninebot-gen3-20261009/V3SessionKeyHelper.java:362`：`saveV3SessionKeyDataAndUpdateSdkKey` 调 `saveKeyInfo`。
- `.../decompiled/sources/cn/ninebot/library/nbbluetooth/blesdk/BleKeyStatusManager.java:153`：保存元数据，`183` 去除 `appEnc` 后持久化，`193–213` 另用 `updateSecret` 注入 appEnc。
- `.../BleSdkSessionTokenManager.java:162`：单独构造 `activate { token }`。
- `.../BleSdkClient.java:952`：兼容密码读取/生成；`969–981` 连接标识与 ConnectOption。

## 3. updateSecret 的真实行为：写 next，不直接覆盖 cur

官方 Java 下发形状：

```json
{
  "id": "abc123",
  "method": "updateSecret",
  "params": {
    "sn": "<已绑定车辆 SN>",
    "encrypt_protocol_version": 3,
    "session_key_hex": "<该 SN 的合法 appEnc>"
  }
}
```

Java 外层 `RustBridge.callMethod` 的第一个 id 可为空；此处 JSON 内 id 与车辆密钥无关。`callMethod` JNI `0x252210` 经 `0x377938` 路由到 `0x37e1e4`（调用点 `0x378444`）。

`0x37e1e4` 逐项判断：

1. 加密版本低 8 位必须为 3（`0x37e1f4–0x37e208`）；缺 SN 返回字符串 `0xd62ba`，缺 session_key_hex 返回 `0xd62f1`。
2. 非空 key 以 `w4 = 1` 调 `0x383b58`（`0x37e244–0x37e248`）。
3. `0x383b58` 在 flag=1 时用格式 `0xd1788` 将 SN 变成 `SN + ".__next__"`（`0x383b9c–0x383bbc`），再按该名字写入 keyring。
4. 空 key 先后以 flag=0 和 flag=1 调删除 helper `0x383d20`（`0x37e360–0x37e380`）。这相当于清 cur、next 两槽；不能当作只清 next。
5. `0x383b58` 还存在受全局字节 `0x684de8` 控制的绕过早退：该字节非零时直接返回成功而不写存储。它的全局配置来源本次未确认，测试明确覆盖这个边界，不能默认运行态一定如此。

32 字符校验与存储边界：

- 长度必须恰为 `0x20` 字节（`0x383bc0`）。
- 循环只接受 `0–9`、`A–F`、`a–f`（`0x383bd4–0x383c64`）。
- 成功分支 `0x383ca8–0x383cb4` 传给 `0x37d4bc` 的是原来的 32 字符文本指针和长度；该位置没有先变成 16 字节。
- `0x374040` 是错误格式化 helper，位于无效 key 分支，不是 hex 解码 helper。旧报告 `tmp/ninebot-rea-20261010/keyring/findings.md` 第 8 行有关“helper return”流向的表述应修正。

安全存储内部与通信密码仍应分开：`0x37d4bc` 准备标识符、调用 SHA-256 `0x2b8fdc`，然后 `0x393f0c → 0x27e1cc → 0x2bcf0c` 对文本按 32 字节摘要循环 XOR，再经 `0x59feec` trait 边界写入。`0x37d0bc` 读取后按相同摘要 XOR 并做 UTF-8 检查。此处是本地存储表示，不能当作无线加密算法。

## 4. 连接开始时的 cur / next 优先级

`0x31c524`（日志 `v2_3.start_handshake`）先取 SN，再读：

- cur：`0x31c60c–0x31c614 → 0x37d0bc`，名字为 SN；
- next：`0x31c7b0–0x31c7ec`，先拼 `.__next__` 再调用同一 getter。

`0x31ca58–0x31cb28` 的四种选择已执行原指令核实：

| cur | next | 选用 | used_key_type | cur_tried | next_tried |
|---|---|---|---:|---:|---:|
| 无 | 无 | 缺 key 分支 `0x31cce4` | 未写 | 0 | 0 |
| 有 | 无 | cur | 1 | 1 | 0 |
| 无 | 有 | next | 2 | 0 | 1 |
| 有 | 有 | next | 2 | 0 | 1 |

三字节状态在共享结构偏移 `0x50`、`0x51`、`0x52`；`0x390f24` 的 Debug 字段名为 `used_key_type`、`cur_tried`、`next_tried`。

选择以后才调用 `0x2bdd60`（`0x31cb28`），其检查 hex 文本长度为偶数，再进入 `0x2a6420 → 0x384da0 → 0x2bcf54/0x27e288` 的字节迭代器。这与入库的原文本边界不同。`emulate_hex_key.py` 执行完整原指令链，与 Python `bytes.fromhex` 交叉验证：有效 32 个 hex 字符输出 16 字节，支持大小写；无效字符、奇数长度、空格均拒绝。12 用例通过，只有 allocation/reserve/drop 是 hook。挑战所用 cipher 已在下节执行验证。

## 5. 已闭合的 6005 本地挑战与 TLV 边界

实际发送器为 `0x302f38`：先以 `w1=9` 调 `0x327590` 设置挑战状态，通过 `0x37fd18` 读取选中的解码 key，再调 `0x383df8` 构建 payload。`0x30305c–0x303064` 以 `w1=0x60,w2=5` 调消息构造器 `0x5a3d44`，由 `0x31dccc` 发出。重试期间检查状态仍为 9；首轮等待 2 秒，后续等待 500ms，次数读取运行时全局配置，而非本报告假定常量。

`0x383df8` 的完整 payload 构造原函数已执行：

```text
key = hex_decode(selected_appEnc)     // 16 bytes
nonce = local_random_u64_as_LE_bytes  // 8 bytes
plain = 0c 04 || LE32(unix_seconds & 0xffffffff)
sealed = AES-128-CCM(key, nonce, plain, AAD="", tag_length=16)
payload = 13 16 || sealed[22] || 02 08 || nonce[8]  // total 34 bytes
```

其中 `13`、`16`、`02`、`08` 均为十六进制字节。`sealed` 是 6 字节 ciphertext 后接 16 字节 tag。原函数的时间低 32 位截断（包括跨 `2^32`）和 nonce 小端表示均经原指令验证。独立交审追到真实时钟 `0x617830 → 0x612f20 → clock_gettime(w0=0)`，为 `CLOCK_REALTIME`。随机数测试仅给 `0x59a148` 合成 buffer/index=0 状态；初始化、耗尽刷新与 reseed 不在这 12 例覆盖中。此函数没有把 SN、SDK token、云响应 nonce 或 AAD 填进本次明文。

密码链为 `0x37cd04 → 0x5a0f48`（AES-128 key schedule）、`0x2ce990 → 0x39a09c → 0x38dc74`。`emulate_fast_auth.py` 执行完整原链，20 组不同明文长度/空与非空 AAD 与独立 `cryptography.AESCCM` 逐字节一致，确认是 **CCM，nonce=8、tag=16**。另 12 组执行完整 `0x383df8`，只 hook 时钟/RNG 来源和内存运行时服务，全部符合上述独立格式；未 hook AES、CCM 或 TLV 构造算法。

可复用独立模块 `tools/ninebot-ble-test/scripts/v3_fast_auth.py` 提供 `build_challenge(app_key16, unix_seconds, nonce8)`；无官方 SO、CLI 密钥参数、账户读取、日志、网络或无线操作。`test_v3_fast_auth.py` 独立复制 12 组冻结原函数合成输出作比较，不依赖原 APK、raw 目录或调用被测编码器生成 expected。它只是 34 字节 payload 生成器；消息外层、BLE 分片、6006 接收与认证后业务加密不由此模块实现。

TLV parser `0x37ce98` 的输入是 `ptr,len,typeWidth,lengthWidth`，认证 handler 观察到 `typeWidth=lengthWidth=1`；宽度 `(2,2)` 测试仅证明通用 parser 能力，不是另一套已确认车辆 schema。15 个原 parser 测试还验证：多字节 type 按大端累积，多字节 length 按小端累积；不完整头/值使解析停止，已完成前缀仍保留；零长度值会被插入。重复 tag 在本脚本里仅确认原 parser 依次调用两次插入边界，因 `0x355fb8` 被 capture hook，**本脚本不证明最终 HashMap 覆盖语义**。独立交审的 `official-connect/cross_validate_fast_auth.py` 移除此 hook、执行原 HashMap，7 个补充测试确认：完整后条覆盖相同 tag，不完整后条保留旧条。结果为 `official-connect/fast-auth-map-cross-validation.json`，交审说明见 `official-connect/cross-review-gen3.md` 第 7 节。

## 6. fast 6006 与 full-auth 6006 必须分开

fast listener 为 `0x2e4f24`，先检查来源 `0x60`、command=4/6（`0x2e5368–0x2e5388`），6006 分支读取 TLV `0x0f`（key_state，长度必须 1）、`0x14`（SN，UTF-8 检查）。收到的数据可能已经通过公共解帧/解密层；本节不证明伪造明文 key_state=0 即可认证。

| key_state / 条件 | 本地分支 | 云关系与边界 |
|---|---|---|
| 0 | `0x2e5b28–0x2e5c00` 本地完成握手与 cipher 配置，随后 `0x2e5f38` 清理退出 | 这条已定位分支不调用 full-auth 的 cloud verify；不能据刷新 API 的存在宣称每次连接必须联网 |
| 1 | 与 0 一样先完成本地成功，再在 `0x2e5e88–0x2e5f28` 构造后台 future 并 `0x2a512c` spawn | 后台 future 后续语义/是否刷新云请求本轮未闭合，不与状态 0 合并宣称“完全无后续云活动” |
| 2 / 4 且用了 next | 先读取 cur，再作 hex 解码、替换 cipher key、删除 next、状态改为 9 并重试 6005 | 正常本地回退；存储/解码/锁错误各有独立失败分支 |
| 2 / 4 且未用 next | 检查 cur_tried / next_tried；两者均试过后发需要重新认证事件 | 不把所有 expired/invalid 都写成立即 full-auth；还需检查 key 尝试历史 |
| 3 | device key missing，发 NeedAuthentication 事件 | 后续合法认证/重新分发属于 full-auth 分支 |
| 其他值 | invalid key state 错误 | 不视为成功 |

本地成功 helper：`0x327f28` 设置状态 8 / is_handshaked 并发成功事件；`0x326e60` 重置 cipher SN；`0x326cb0` 配 key2；`0x326a84` 配 authParam。内置 backend 下后二者分别经 `0x37f254 → 0x425928` 和 `0x37fcb4 → 0x425568`，检查/拷贝 16 字节参数到本地结构；兼容 backend 分别转导入 `setKey2` / `setAuthParam`。这些局部静态链支持“存在已缓存 key 的本地成功路径”，**尚不等于完整 SDK 离线运行或独立 iOS 互通验证**。

另一个 full-auth handler `0x2f5be4` 只在其内部 FSM 快照为 2 或 9 时处理；在该 listener 的状态 9 / key_state 0/1 分支还会提取 SN、PN、deviceEnc、AAD 并进入 `/session-key/verify`。这是重新认证/分发上下文，不能把它接到 fast listener 的每个 6006 后，推导“所有连接必须先云 verify”。完整认证监听器 `0x2e6d38` 另分派 6002/4/6/7。

## 7. 已定位的完整认证消息与云流程

本节是处理路径定位，不是已还原完整 wire schema。消息编号来自同函数的真实字段分派与日志，不把日志字符串单独当作 payload 定义。

| 分支 | 已定位 native | 已确认处理内容 |
|---|---|---|
| 开始 / 选 key | `0x31c524` | SN、cur/next、SDK token warning、优先 next；进入后续挑战流程 |
| 6001 发起安全认证 | `0x2f70a0` | `/sdk/api/bluetooth3/auth/v1/handshake/challenge`，响应存在性和重试/终态检查 |
| 6002 安全握手回复 | `0x2f2bf4` | 检查 signature、nonce、device public key、client certificate；调 `/sdk/api/bluetooth3/auth/v1/handshake/response` |
| 6003 密钥分发 | `0x2e22e4` 发送点日志 `0x2e3730` | 组装下发数据；刷新相关路径需要 `deviceEnc`、nonce、aad 等材料 |
| 6004 密钥分发结果 | `0x2e6d38`、`0x2e4f24` | 查 key_state，空/长度错误单独报错；成功存储当前 key 的路径可见 |
| 6005 本地挑战 | `0x302f38 → 0x383df8` | 34 字节 CCM payload 已闭合；消息外层与后续业务 cipher 另验 |
| 6006 fast 回复 | `0x2e4f24` | 本地 key 挑战成功、key_state=1 后台 future、cur/next 回退、缺 key 上报 |
| 6006 full-auth 回复 | `0x2f5be4` | 在重新认证/密钥分发 FSM 中提取云校验材料、verify/用户确认；不能代替 fast listener |
| 6007 实体按键确认 | `0x2e7884–0x2e79b4` | 检查消息分支7；调用确认状态更新，向上发 process=5 / user confirmed |

`0x2e6d38` 实际分派在检查来源字节 `0x60` 后，按字段值 2/4/6/7 分支（`0x2e7878–0x2e78e0`）。这能确认 6002、6004、6006、6007 在一个真实 listener 中被处理。

native 云路径与常量：

| 地址 | 路径 | 使用函数 |
|---|---|---|
| `0xd5607` | `/sdk/api/bluetooth3/auth/v1/handshake/response` | `0x2f2bf4`，路径装载点 `0x2f3c68` |
| `0xd5635` | `/sdk/api/bluetooth3/auth/v1/session-key/verify` | `0x2dd328`，`0x2dde90` |
| `0xd5663` | `/sdk/api/bluetooth3/auth/v1/handshake/challenge` | `0x2f70a0`，`0x2f7d48–0x2f7d60` |
| `0xd5692` | `/sdk/api/bluetooth3/auth/v1/session-key/refresh` | `0x2e22e4` 的刷新请求路径 |

这四条 `/sdk/api/` 与 Java 应用层的 `app-api/bluetooth3/auth/v1/session-key/info` 是不同边界，不可互换。HTTP 路径存在不证明普通随机 token 可以调用。

HTTP header 见 `0xd4a4f` 起：`X-Ctx-Hash`、`X-Nonce`、`X-Timestamp`、`X-Jti`；相应请求函数里有真实 header 构建引用（如 `0x2f4a84`、`0x2f8998`、`0x2de684`、`0x2e3e00`）。这些字段的完整算法/有效云会话尚未验证。

序列化字段局部定位：`0x2ba96c` 包含 `deviceSign`、`pkDeviceTemp`、`clientCert`；`0x2bc234` 包含 `pkCloudTemp`、`cloudSign`；共同 rodata `0xd3c40` 附近还有 `useCase/version/level/pn/nonce/deviceEnc/appEnc/efTimestamp/exTime/reTime/aad/serverCert`。需进一步用序列化原函数或实际合法会话核对大小写、可选项与字节编码。

## 8. 失败恢复与 next 提升的交叉核对

`0x2e4f24` 的挑战回复处理包含这些真实分叉：

- `0x2e5d4c–0x2e5d78`：响应状态 2 对应日志 `session key expired`，状态 4 对应 `session key invalid`。
- `0x2e5f84–0x2e5fe0`：状态 3 对应 `device key missing, start authentication`，向上发认证需要事件。
- `0x2e5d80–0x2e5ddc`：当 used_key_type=2 的 next 挑战失败，尝试重新读取 cur。
- `0x2e6038` 之后有 `both cur and next keys failed, need authentication`。
- `0x2e65d4–0x2e65e4`：`fallback to cur key and next removed, retry challenge`。
- next 提升有真实 guard：`0x2e5c00–0x2e5c14` 要求 used_key_type=2；`0x2e5c34` 以 flag=0 写 cur。写 cur 成功才在 `0x2e5c9c` 以 flag=1 删除 next；写入失败会跳过删除。删除错误虽被记录，后面的“next removed”日志仍可能执行，不能用这句日志证明删除成功。该 guard/flag/失败次序已由独立交审复核。
- 状态 2/4 的分支先判断 used_key_type=2 并尝试 cur，未用 next 才进入 tried flags 判断。全部 key 失败时，是否清 cur 还受运行时全局配置控制（`0x2e6064–0x2e6080`）；不能无条件实现成删除全部缓存。

该状态码的错误 / 重试 / 上报先后仍需更大范围原函数模拟。当前证据已足够否定“next 失败立即无条件永久失败”或“每次都重新认证”的实现。

## 9. 可执行复现顺序与门槛

以下是沿官方 SDK 可落地的调用顺序，未宣称在 PC 直接运行 Android JNI 就能连接：

1. 在 Android host 初始化官方 RustBridge / Peripheral 依赖。Java `BleSdkClient.java:263–275`：`RustBridge.init() >= 0` 才置 READY、同步配置、获取并激活 SDK token。native `init` `0x253fa8` 只是转到 `initBleSdk`；后者 `0x253fac` 调 `0x37b778` 与 `0x37884c`。
2. 注册连接、通知、日志与错误回调；保留物理 GATT connected、SDK authenticated、Java prepared 三个状态。
3. 使用用户已有合法绑定 / 云会话获取该 SN 的 V3 key data；保留时效与分发元数据，调用上述 `updateSecret`。不得拿兼容 Gen2 密码代填 `appEnc`。
4. 激活 SDK `activate { token }`；有效 token 与某车 key 均应有效。SDK token warning 路径不等于车辆已认证。
5. 扫描实际广告以确定 BLE type 和 encryptProtocolVersion；缺 SN 时先走 GetSn/AfterGetSn 分支。不能按车型名强制所有仪表用相同协议。
6. 参考 `BleSdkClient.java:975` 调 `RustBridge.connect(<id>, <ConnectOption JSON>, callback)`。官方 FullConnect 显式 30秒连接超时，session_id 本地 UUID 带 `_user`/`_auto`；encrypt_protocol_version 该 Java 调用传 null、由 SDK 结合设备数据处理。
7. native 优先 next，执行已还原的本地 6005 挑战。有效 key / fast key_state=0 存在本地完成认证的分支；key_state=1 的后台任务单独保留。若进入 full-auth 并要求车端按键确认，等待真实 process=1/5 与 6007 事件；不能人工把状态改成功。
8. 若 next 失效，保留 SDK 的 cur 回退 / next 清理 / 重试；若双方失败或 key 缺失，按错误请求合法重新授权 / 分发。等待 connect.onSuccess，再做上层 readVersions/prepare，然后才启投屏。

独立实现边界：UUID、超时、hex 文本校验、cur/next 选择、缓存时效、6005 CCM payload 均可独立复现；SDK 原生桥也可设计调用适配。6005 已有无官方 SO 的本地实现。完整 Gen3 独立连接仍缺共同消息外层/6006 安全接收/业务加密的闭合验证，以及 full-auth wire 全字段、签名上下文和有效云会话验证。随机合成密钥能用于本地单测，不能成为车辆认证凭据。是否能离线直连取决于合法 key/SDK 会话与车端状态，不能由“需车辆认证”推导成“每次都需车辆云认证”。

## 10. 原函数验证与复现

运行（PowerShell 7 / UTF-8）：

```powershell
python -X utf8 artifacts/ninebot-connection-repro-20261115/gen3-auth/emulate_secret_boundary.py
python -X utf8 artifacts/ninebot-connection-repro-20261115/gen3-auth/emulate_hex_key.py
python -X utf8 artifacts/ninebot-connection-repro-20261115/gen3-auth/emulate_fast_auth.py
python -X utf8 -m unittest discover -s tools/ninebot-ble-test/scripts -p test_v3_fast_auth.py -v
```

结果：`secret-boundary-validation.json`，275 个原 ARM64 分段用例通过：

- 256 个 ASCII 首/尾字符校验，覆盖全部 0–127 字节；
- 6 个错误长度；
- 2 个 next 名字/非 ASCII 校验；
- 6 个 SecretInfo 版本/缺字段/清除/有效与无效 key 分支；
- 1 个 `0x684de8` 全局旁路；
- 4 个 cur/next 选择组合。

`emulate_hex_key.py` 的 `hex-key-validation.json` 共 12 例通过，包含完整 hex32→bytes16、单字节、256 字节序列及无效输入。

`emulate_fast_auth.py` 的 `fast-auth-crypto-validation.json` 共 47 例通过：20 AES-CCM、12 完整挑战生成、15 TLV parser。三个原库脚本合计 **334 个局部/算法边界用例**，不是 334 次完整连接。时钟/RNG 来源、Rust 内存服务与 parser 的 map 插入边界 hook 均在源码明确记录。

独立模块测试另有 4 个 unittest 方法、25 个输入子场景（12 冻结向量 + 13 错误输入），全通过；12 向量复用了相同合成输入，不重复算成新的原库证据。独立交审的额外恢复/HashMap 动态测试不包含在本文 334 数字中。

校验函数和分派/选择指令执行自同一冻结 ELF。SecretInfo 测试的 Rust 格式化、错误对象、keyring 和释放操作用明确 hook；选择段停在 hex 解码入口。SecretInfo 测试只验证存储边界和状态选择；新增 fast-auth 测试才验证挑战密码算法。两者均不计作云 API 可用性或车辆互通。

工具 `native_trace.py`、`locate_auth.py` 和 `fn-*.asm.txt` 保留复核地址。反汇编的字符串注释是简化线性寄存器追踪，可能跨基本块保留旧值；结论应回看指令实际来源，不用注释单独证明数据流。

## 11. 本轮未闭合项
