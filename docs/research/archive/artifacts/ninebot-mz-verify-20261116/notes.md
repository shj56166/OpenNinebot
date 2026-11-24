# Mz / 14360：root 材料续研与 VerifyConfig 恢复

日期：2026-11-16（America/New_York）。本轮恢复配置、执行官方解析方法并冻结证据；没有连接车辆、使用账号或调用车辆授权接口，没有修改投屏核心或重新发布 APK。

## 结论

**14360 的 VerifyConfig 已恢复，原先“缺少证据”的配置缺口已消除。** 旧材料位于 `tmp/ninebot-gen3-20261009/`，对应 2026-10-09 的 root 导出。该目录已保存 Mz 的命令表、BLE 配置和能力配置，缺的是连接页面配置 `code/device_ui_connect.nb`。

缺失来自当时的选择性导出：`collect_configs.py` 只拉取 `manifest.json`、`code/ble.nb`、`code/device_commands.nb`、`capability/vehicle_capability_config.nb`；另一路运行时缓存仅导出了已加载的 14101/14357 的连接配置，没有主动加载 Mz 的连接配置。因此，旧结论只表示手头材料不完整，不能推出 Mz 不支持投屏。

恢复结果见 [mz-device-ui-connect.json](mz-device-ui-connect.json)，接入所需的有界只读表见 [mz-preparation-contract.json](mz-preparation-contract.json)。后者是从 Mz 自己的命令表与恢复配置生成，没有套用其他车型的准备表。

## 来源与完整性

冻结的九号 Android 6.10.11 APK：

`02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`

恢复链如下：

1. 原 APK 的 `BackupClient` 读取内置 `assets/backup_config.nb`，可得到公开备用配置包索引。
2. 索引中 14360 对应版本 **600015500**，与 root 导出的 Mz `manifest.json` 一致。公开下载地址为 [官方 Mz 备用配置包](https://oms-oss-public.ninebot.com/dsIKKa6d22/device-config/package/1789526971820__D2_0x1108_600015401.zip)。URL 文件名中的 `600015401` 与索引版本不同，两个值均按原文保留。
3. ZIP 共 4,291,436 字节，CRC 校验通过，SHA-256 为 `07e7c35f23291d3f971e0c276c737895ead27a1afc2a2751fd0cb51bc24ef465`。获取包时没有使用账号、令牌或车辆会话。
4. `DeviceApplication.generateDynamicConfig` 通过 `NativeSecrets` 两个 accessor 提供默认配置解码上下文。本轮用 Unicorn 执行 APK 内原始 ARM64 accessor 指令，仅替代 JNI `NewStringUTF` 边界；配置解码参数仅在研究进程内使用，没有写入报告或 App。
5. 包中的 `ble.nb`、`device_commands.nb`、`vehicle_capability_config.nb` 解码后，与 10 月 root 导出的对应明文逐字节一致。**密文封装不同，不能声称密文相同。**
6. 包中的上述三份文件及新恢复的 `device_ui_connect.nb`，均通过 APK 内 `config_rsa_public_key.pem` 对应公钥的 **SHA256withRSA / PKCS#1 v1.5** 验签，修改密文的一个字节即验证失败。该 asset 虽名为 `.pem`，实际为 Base64 X.509 DER；算法与 `ConfigInterfaceImpl.checkSign → EncryptionUtils.rsaCheckSignThrow` 一致。

新配置的原始解码内容 1,613 字节，SHA-256：

`522bf954e490640d9df1cd5159d61eb3be211cba43da5a7dbc90d0d354d26dce`

保存的 JSON 经格式化，文件自身哈希与上述原始明文哈希不同，分别见 [evidence-manifest.json]（本地来源，未随附原始材料） 与 [recovery-validation.json](recovery-validation.json)。签名验证的是 ZIP 内原始加密文件，不是重新格式化后的 JSON。

## Mz 的实际准备规则

车型 server ID 为 **14360**，广播 hardware ID 为 **4360 / 0x1108**。静态 `ble_protocol=2`、`encrypt=2` 不代表每台车当前广播的认证代际；仍须依据实际广播识别 Gen2/Gen3。

| 作用 | 命令 | 模块 / 目标与响应来源 | 索引 | 读回字节 | 校验 |
| --- | --- | --- | --- | --- | --- |
| 主电源条件 | `rMainPower` | ecu / 9 | `0x03` | 2 | 小端值 `& 0x0004` 非零 |
| 激活状态 | `rBool` | dis / 1 | `0xB2` | 2 | 小端值 `& 0x0800` 非零 |
| 车辆 SN | `rSN` | dis / 1 | `0x10` | 14 | `toASCIIString, 0, 14` |
| ECU PN | `rECUPN` | ecu / 9 | `0x28` | 14 | `toPn, 0, 14` |
| 功能位一 | `rFunAppBool` | dis / 1 | `0x8A` | 2 | 原始值供缓存 / 上报 |
| 功能位二 | `rFunAppBool2` | dis / 1 | `0x8C` | 2 | 原始值供缓存 / 上报 |

这些读取使用默认请求命令 1、响应命令 4；读取请求的数据为一个长度字节。默认 timeout=1000ms、retry=5 是命令表默认值，不能覆盖文件读取器自身的重试状态机。

官方调用关系需分两段理解：

- 本地动态准备回调执行 `updateRetrofitDevice → getVerifyPageConfig → requestMtuIfNeeded → updateCommandCondition → checkIsActivate → setActivate`；其中条件刷新读取 `rMainPower`，激活检查读取 `rBool`。
- 后续 `doCheck(..., true)` 或 `doRequestWhenConnect` 才查询业务队列：`rSN → rBool → rECUPN → rFunAppBool → rFunAppBool2`。因此原流程会再次读取 `rBool`。Mz 的该队列**没有 N3 的 `rInfoBool2 / 0x37`**。

`updateCommandCondition` 先更新条件值，`setActivate` 更新本地激活状态；不能把方法执行完成写成车辆已经允许投屏。现有测试核心额外要求来源、命令、索引和长度严格匹配、主电源与激活位均成立，再把一次明确的只读准备记为成功。这是独立工具的严格策略，并非所有官方 UI 分支逐行等价。

本文件没有 `mtu_condition`。原始 `VerifyConfig.getMtuSetCondition()` 返回 null，不能虚构默认条件。Mz 车型配置另有 `mtu=true`：旧 Java 路径构造时可经 `requestBleMtu` 申请 MTU，SDK 路径在该方法直接返回。**连接配置没有 MTU 条件不等于不协商 MTU，更不等于已协商到某个固定值。** 独立 GATT 传输仍必须使用实际 ATT MTU。另需区分官方 SDK `getMtu()` 对底层返回值减 3 后的负载容量与 Android `onMtuChanged` 给出的 ATT MTU。

Mz 自己的投屏命令表也确认 `tft=35 / 0x23`，配置文件命令 130/131/132，模式命令 113。它们证明控制路由，不直接证明屏幕尺寸、当前媒体承载或编码；仍须读取仪表 type1 配置并校验原始 MD5 后选择媒体路径。

## 账号绑定检查与信息上报

`VerifyConfig.server` 与继承自 `PageConfig` 的 `servers` 对应两个独立入口：

| 字段 | 调用入口 | 配置里的操作 | 依赖 |
| --- | --- | --- | --- |
| `server` | `doCheck`，由 `checkConnect` 调用 | POST `/app-api/device/bind/is-binding`，`success_code=1`，`cache_enable=true` | uid、wnumber、vehicle_type、ecu_pn |
| `servers` | `doRequestWhenConnect → RequestController` | POST `/app-api/device/mac/update-mac-info`，`success_code=1` | 另含 vehicle_mac、app_0xb2、app_0x8a、app_0x8c、battery_in |

两项均声明 `server_platform=java_v2`、`apply_server_config=false`。这些是配置模板，不能代替真实账号登录、请求上下文、合法授权或响应校验。`battery_in` 占位参数也不在本文件的六条缓存映射中，不能擅自填一个“成功值”。

`checkConnect` 首先检查 `DeviceManager.getDeviceBean(device)`；非 null 时直接返回 true。因此，不能声称官方每次连接都会执行该 HTTP 请求。走 `doCheck` 的分支会按返回 code 决定后续 UI、断开或失败；配置加载失败还有远端强制检查开关。`cache_enable=true` 只证明声明允许缓存，本轮未以此推断缓存有效期或离线授权时长。

这些 App 业务检查与 Gen3 的 6001—6007、本地密钥认证分别建模。本地准备、账号业务检查、投屏配置校验、模式回复、媒体写出及车端显示确认都应保留独立状态，缺少合法账号上下文时明确记录未执行，不能伪造云端成功。

## 验证与复跑

- [original-config-validation.json](original-config-validation.json)：复用已冻结的 dex2jar `original-classes20.jar`，**直接执行原始类的 getter、CommandParser、BoolMaskParserStrategy、toBoolean、getShortAt 和比较操作**；65,536 个输入 × 3 个布尔转换，加结构及边界检查，共 **196,624 条断言通过**。没有改写方法体，也没有提供 HTTP / BLE 实现。
- 原始解析器接受部分短数据：`04` 即可令主电源布尔为 true；空数据为 false；`00 08` 才设置激活位。独立协议核心要求两字节状态回复，是刻意收紧的帧校验，不能拿原解析器的容忍行为作为无线完整响应的证据。
- 验证用 Gson 排除未标注的运行时字段，避免把 `RequestConfig.exception` 等状态按 Java 桌面反射解析；配置字段及原始解析方法保持不变。没有执行完整 Android 协程、账号请求或车辆连接。
- [package-signature-validation.json](package-signature-validation.json)：四份配置签名及单字节损坏的负例均通过预期。
- [source-index.json]（本地来源，未随附原始材料） 保存源文件哈希和定位范围；`dex-input-locations.json` 是定位候选调用的辅助索引，不是完整 DEX 控制流证明。主输入 APK 和四个 DEX 的内容对应关系由 `collect_evidence.py` 校验。

复跑需要保留冻结 APK、DEX、原 JAR、JADX，以及 Python cryptography/pyelftools/Unicorn 和本机缓存的 Kotlin 2.1.20、Gson 2.10.1。Unicorn 使用既有研究目录 `artifacts/ninebot-ble-cast-20261115/raw/tools/python`。先按以下对应关系用 JADX 的 `--single-class` 导出到本目录 `raw/`；JADX 对缺失依赖的诊断不代表其完整反编译已成功，使用的具体方法须结合原 DEX / 原方法验证解释。

| 原始 DEX | 要导出的类 |
| --- | --- |
| classes4.dex | `cn.ninebot.tools.util.EncryptionUtils` |
| classes16.dex | `cn.ninebot.device.DeviceApplication`、`cn.ninebot.device.NbBluetoothDeviceEtKt`、`cn.ninebot.device.dynamic.ConfigInterfaceImpl` |
| classes22.dex | `cn.ninebot.ninebot.mainshell.BackupClient` |

在工作区根目录依次运行：

```powershell
python -X utf8 artifacts/ninebot-mz-verify-20261116/recover_backup_index.py
python -X utf8 artifacts/ninebot-mz-verify-20261116/fetch_mz_package.py
python -X utf8 artifacts/ninebot-mz-verify-20261116/recover_mz_verify.py
python -X utf8 artifacts/ninebot-mz-verify-20261116/verify_package_signatures.py
pwsh.exe -NoLogo -NoProfile -File artifacts/ninebot-mz-verify-20261116/run-validation.ps1
python -X utf8 artifacts/ninebot-mz-verify-20261116/collect_evidence.py
```

`fetch_mz_package.py` 复用本地 ZIP，首次获取才访问公开配置 URL；其余步骤离线。`raw/` 及编译文件不进入源码交付或 Release。没有导出账号令牌、车辆密码或长期密钥。

## 对现有测试包的影响

本轮已补齐解除“14360 缺少冻结 VerifyConfig”限制所需的配置证据。现有 **0.3.0 / 28** 的源码与已发布 APK 仍保留该拒绝条件；本轮没有改包，也没有把历史交付记录改成已支持 Mz。

后续接入可复用共享核心的严格只读准备路径，按本证据表补充 14360 的允许项及来源 / 索引 / 长度 / 激活负例验证，保留账号业务状态的真实含义。上线需使用更高版本重新构建和审计。Mz 的真实广播、合法账号 / Gen3 provider 验收、实际仪表配置、显示和长时间稳定性仍待实车测量，配置恢复与离线断言不计作这些结果。
