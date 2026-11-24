# 官方 APK：扫描、连接、prepared 与投屏控制复现

## 本轮新结论

1. **`DynamicDevice.onReadVersions` 并不读车端版本：它直接调用 callback。** 不能把抽象方法名理解成所有动态车型都必须完成一次版本寄存器读取。原 DEX 方法已执行验证。
2. 协议选择由动态车型配置的 `ble_protocol`、扫描得到的 `encryptProtocolVersion` 和部分旧分支的配置 `encrypt` 共同决定。这三个量不能互换。131 车型缓存的静态 `encrypt=2` 也不能排除该车当前广播为 Gen3。
3. 已有 131 车型目录中，本地只有 Mmax2/N3/Mz 三份解密后命令表足以确认 `tft=0x23`、文件命令130/131/132和模式命令113。其余车型有的包含 TFT 模块，但投屏命令、承载、编码和尺寸仍未知。
4. type1 配置是**原始字节的二进制 MD5 → UTF-8 → 去掉至多一个末尾 NUL → JSON 根对象**，没有观察到 gzip/base64/zip 解析步骤。此前“全部文件都是128字节固定小包”的表述过强：官方根据 `ceil(fileLen/128)` 决定包数，但实际按每包 `data.length` 拷贝。

可复制给测试工具的目录是 [model-catalog.json](model-catalog.json)。所有条目都有 `command_catalog_available`；缺表代表未知，不能标为不支持。目录不包含用户或车辆认证凭据。

## 1. 扫描与协议选择

旧 Java `ScanRecord` 识别 manufacturer AD 的原始字节前缀 `4e42`、`4e43`、`1f0f`。它按大端读取这两个字节；Android `getManufacturerSpecificData` 的 key 语义不能直接照抄这个大端数值。每种格式的最后一个字节是前述主体字节总和的按位取反，低8位。

| 原始 FF 前缀 | 主体中的车辆类型 | 主体中的认证版本 |
| --- | --- | --- |
| `4e42` | 首字节；首值1..15时还会按后续格式扩展 | 紧随类型的一个字节 |
| `4e43` | 前两个主体字节，大端16位 | 第三个主体字节 |
| `1f0f` | 后续 TLV 的 type0、len2，大端16位 | 主体首字节的低4位 |

`1f0f` 后续 TLV 使用高3位类型、低5位长度。type1/2/3 均被旧 Java 赋给 `sn` 字段，不能以这个赋值证明其语义始终是车架 SN。校验失败时旧方法仍可能返回一个类型 `-1`、版本0的未分类对象；测试工具应保留“未知”，不把0补成 Gen2。

SDK 扫描回调发现相同 MAC、不同认证版本时会更新设备对象。`scanConnect` 按 MAC 找到目标后，先更新 `BleDevice` 再停止扫描；`isSuportV3Auth()` 为当前 SDK 设备认证版本等于3，随后选择 FullConnect，其他走 GetSn。`scanConnect` 的 Java 搜索超时为15000ms。

`DynamicDevice.createBleProtocol` 的原方法矩阵：

| 配置 protocol | 扫描认证版本 | 结果 |
| --- | --- | --- |
| 1 | 2 | `BleEncryption2Protocol1` |
| 1 | 其他值 | `BleProtocol1` |
| 2 | 2 | `BleEncryption2Protocol2` |
| 2 | 3 | `BleEncryption3Protocol2` |
| 2 | 其他值，配置 encrypt=0 | `BleProtocol2`，encrypt=false |
| 2 | 其他值，配置 encrypt!=0/null | 旧 `BleProtocol2`，默认加密 |
| null、0、其他非1/2值 | 任意 | `BleEncryption2Protocol2` 默认值 |

这是官方分支选择事实，并非独立测试工具应该对未知广播自动回落发送 Gen2 的建议。原方法已覆盖75组组合；协议类构造器是测试桩，**没有执行其中的密码算法**。

## 2. 控制 GATT、媒体 GATT 与连接阶段

| 角色 | Service | Characteristic |
| --- | --- | --- |
| NUS 控制写 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| NUS 控制通知 | 同上 | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| HMSoft 旧控制 | `0000ffe0-0000-1000-8000-00805f9b34fb` | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| RTP 媒体写 | `6e400001-0000-0000-006e-696e65626f74` | `6e400002-0000-0000-006e-696e65626f74` |
| RTCP 写/通知 | 同上 | 分别 `...0003` / `...0004` |

HMSoft 在静态属性表里是 notify 角色，旧 `onServicesDiscovered` 的该角色分支同时把它设为控制写 UUID。NUS 的 `...0003` 在库的角色表里叫 `characteristic.read`，而旧枚举分支实际启用本地通知并写描述符；**不要仅从该角色字符串推断它使用 ATT Read 轮询**。

独立 Android 实现应发现服务和实际属性、为选定控制通知开本地通知，并在有通知描述符时完成 CCCD 写入。应用内部 `registerResponseCallback→RustBridge.notify` 是 native 解码/回调注册层，不能当成独立 GATT 客户端已经完成通知订阅的证据。当前媒体主入口 `sendRtcp=false`，RTP 开始前不必照 RTCP 的懒订阅代码额外写 RTCP。

SDK Java连接入口有三条主要支路：

- 需要扫描：`scanConnect`，当前广播更新对象后再选 FullConnect/GetSn。
- 不扫描且本地已知 SN：`connectFull`，创建 `ConnectOption(connect_type=FullConnect,connect_stage_timeout_mills=30000,...)`。
- 不扫描且缺 SN：`connectGetSN`，`connect_type=GetSn,strategy=enqueue`；过程id2获得 SN 后，Gen2 processor 触发 `connectAfterGetSN`，此时 `connect_type=AfterGetSn`。

这些 `ConnectOption` 是 App→native 的本地 JSON，不是可以原样写到 GATT 的连接数据包。

需要分开记录的状态：

1. 发现服务/控制通知可用。
2. SDK `onProcess(0)`：设置 `mConnectedDevice` 和 `ConnectionState.CONNECTED`，此刻可能仍未认证。
3. native 加密连接回调 `onSuccess`：Java SDK 状态设 CONNECTED。
4. processor `readVersions→onReadVersionsComplete→prepareSuccess`：设置 `isPrepared=true`。
5. 动态车型的 worker prepare listener：`updateRetrofitDevice→getVerifyPageConfig→requestMtuIfNeeded→updateCommandCondition→checkIsActivate`；执行后才设置它另一个 `prepareSuccess` 字段。

`NbBluetoothDevice.isConnected` 对 SDK 路线要求同 MAC 的连接设备及 processor.isPrepared；旧路线要求父类连接及 processor.isPrepared。媒体底层 writer 自己的门控较弱，不代表车端会接受未认证媒体。

默认 preparatoryTimeout 为30000ms。MTU 可由动态连接配置条件触发，等待上限1000ms，并被吞掉异常后继续；MTU 的请求值不等于实际协商值。独立实现的媒体分包必须读取实际 ATT MTU，不能把该等待结束当作已经协商到247。

### prepared 里的实际读取

`DynamicDevice.java:535..538`：

```java
protected void onReadVersions(Function1<? super NbBluetoothDevice, Unit> callback) {
    Intrinsics.checkNotNullParameter(callback, "callback");
    callback.invoke(this);
}
```

本轮执行该原 DEX 方法，回调恰好一次、参数为该设备；测试环境没有提供寄存器读/写实现，也没有被调用。该结论只适用于此动态类，不能推广到所有其他 `NbBluetoothDevice` 子类。

连接配置快照 [connect-ui-configs.json](connect-ui-configs.json) 中的 Mmax2/N3 会在后续条件读取里使用 `rMainPower`，激活状态读取 `rBool` 并检查mask2048；VerifyConfig缺激活命令时直接true，命令无回复时回落 `defaultActivation()`。这些是 App 本地准备与激活展示逻辑，**不等于认证挑战/响应，也不能单凭可读状态断言车端投屏已获准**。测试工具可以把认证通过和显式读取成功分别显示；在未复现全部车型条件前不冒称官方 prepared 完整等价。

## 3. type1 投屏配置：请求与回复

动态设备的 `ScreenCastConfigClient` 获取 `file_read_begin/data/verify` 命令，再以 begin.targetId 匹配 board.sendId；它不只是永远按构造参数 `"tft"` 取板。三份已解码表恰好都为 tft sendId=receiveId=35，即0x23。普通设备可使用构造时取到的板和默认命令。

下表的字段为**解密后的九号命令语义**，不是完整明文 GATT 发包。外层帧和 Gen2/Gen3 加密由会话层处理。

| 阶段 | 请求 | 回复判据 |
| --- | --- | --- |
| 开始 | cmd=0x82,index=fileType=1,data=`01 08 00` | cmd=0x82，data从offset0按小端读取至多4字节文件长度 |
| 读取组 | cmd=0x83,index=groupIndex&255,data空 | cmd=0x83，index=0..该组最后小包索引，data为原始文件片段 |
| 校验 | cmd=0x84,index=0,data空 | cmd=0x84,index=0,data恰好16字节二进制 MD5 |

分组公式：`allPackets=ceil(fileLen/128)`；第g组的包数 `min(8,allPackets-8*g)`。小包索引每组从0重新开始。官方按收到的 `data.length` 顺序拼接，在小包索引等于组末索引时才提交 `successLen=readLen` 并唤醒等待。错序/缺索引时，立即把readLen回滚到上一已提交组、smallPackIndex重置0，但没有即时返回错误，而是等待超时。

注意以下原方法边界均已执行确认：

- begin只匹配cmd，不核index；index42也能受理。空/1/3/4/8字节输入分别读0/短整数/前4字节。独立实现要求恰好4字节并设置文件长度上限，是额外健壮性限制。
- reader注册的是原始帧 listener；`CommandDispatcher.onResponse` 先通知它，之后才做 executingCommand/waitResp 匹配。因此不能假设后面的期望响应过滤补上了begin的index校验。
- fileLen129，数据小包64+65字节也会组尾成功。官方没有逐小包强制128。
- fileLen129，首包128、末包128会触发 `System.arraycopy` 越界；**没有自动截断尾部padding**。独立工具若接受padding，必须披露这是增强策略并用MD5确认被裁出的完整原文件；否则应有界拒绝并取HCI判断真实固件行为。
- 本类仅匹配currentCmd，不额外核source板；独立工具应避免把其他板的同命令响应混入文件。
- 组请求index只有8位；超过256组会别名。测试工具设置256KiB上限可避免继续递增发送相同低8位索引。

超时和重试来自 `BtFileReader` 本身：begin/verify通常1000ms；read组等待8×500=4000ms。begin/verify的默认retry=true，最多6次write尝试，失败间隔300ms。read的内层write参数retry=false，但外层失败组最多7次尝试。MD5不符时会再读一次verify，其中源码有一次硬编码0x84；这些只是官方行为记录，测试工具无需为了初次诊断照抄所有重试。

文件长度0的原官方路径可以返回空文件，但不能据此得出可投屏配置。缓存路径可在本次读取失败时回退先前本地JSON；独立诊断工具应把“本次车端MD5验证”与“旧缓存”区分。

## 4. JSON与模式控制

MD5计算对象是重组得到的**全部raw bytes，包括文件末尾可能的NUL**。`BtFile.rawText` 用UTF-8解码；`getText` 仅移除一个末尾 `\u0000`，不做其他解压/解码。`ScreenCastConfigClient` 对其直接创建 `JSONObject`，再Gson解析。根是配置对象，不再包一层data。

常用字段：

| 字段 | 官方解释 |
| --- | --- |
| `rtpChannel` / `channel` | 字符串数字0=BLE、1=WIFI、2=WIFI_KCP，前者有效时优先，否则后者，再回落BLE |
| `rtcpChannel` | 单独选择；不能替换rtpChannel |
| `codecType` | 字符串0=MPEG2、1=H264、2=MJPEG；有效时优先，缺失/其他值回退codecName |
| `codecName` | JSON实际字段名；Kotlin内部名为codec_name。仅Locale.ROOT转小写后精确匹配h264/mjpeg/mpeg2，不trim、不按contains匹配；不支持则抛异常 |
| `codecWidth/codecHeight` | 编码尺寸，优先于dimensionWidth/Height；完整缺省链最终240×320，非所有仪表固定值 |
| `frameRate/bitRate/gopSize/maxBFrame` | 编码参数 |
| `dimensionWidth/dimensionHeight` | 布局尺寸/尺寸回退 |
| `version` | 字面点号切分取首段，toIntOrNull成功且>5才拒绝；不trim，前置+/-合法；null/空/非整数/Int溢出不被此检查拒绝，未见默认版本字符串 |
| `hearBeat`, `max_bit_rate`, `naviType`, `layout` | 其他可选配置；保留原始JSON便于交叉分析 |

`setNaviStart` 为一字节payload：0停止、1SimpleNavi、2MapNavi、3Cruise。三份已解码表使用cmd0x71/index0、target0x23，不能扩展到其他128车型。

官方 callback 只检查 `NbFrame!=null`；缺命令时也能直接返回true。因此测试工具应称“收到匹配模式回复”，不要称“仪表已显示”。媒体发送仍以配置选择的承载、编码、尺寸为条件，并通过实际画面验收确认。

## 5. 验证与复跑

执行：

```powershell
pwsh -NoLogo -NoProfile -File artifacts/ninebot-connection-repro-20261115/official-connect/run-validation.ps1
python -X utf8 artifacts/ninebot-connection-repro-20261115/official-connect/collect_evidence.py
```

[original-method-validation.json](original-method-validation.json)：**120个本地原方法场景、277个断言通过**。其中75个协议选择组合；其余覆盖版本回调、文件组重组、错序、尾包越界、MD5二进制长度、UTF-8/单NUL、两套UUID隔离、三种manufacturer格式及校验错误。边界全部为合成输入；没有录制实车完整成功会话。

[original-method-inputs.json]（本地来源，未随附原始材料） 记录APK/DEX/jar哈希、stub边界和运行依赖。ASM仅复制所选原方法；简化构造、类层级和所需字段，协议选择器输入由stub提供，取消真实Android Handler/日志环境。`-Xverify:none` 是此前dex2jar方法运行需要，不能把这个本地JVM实验说成Android设备验证。旧 `ScanRecord` 在某些短buffer下会打印其内部越界position异常再返回已解析对象；原始stderr保留于忽略目录 `raw/runtime-stderr.log`。

[catalog-validation.json](catalog-validation.json)：131条车型完整导出，110条protocol2、7条protocol1、14条缺protocol；66条静态encrypt2、5条encrypt0、60条缺失；56条含TFT模块；仅3条有当前解密命令证据。Mmax2资产/缓存命令在归一化r_cmd默认0后完全匹配。该目录校验不计作277原方法断言，也不作为车型实测覆盖数。

源码导航见 [source-index.json]（本地来源，未随附原始材料） 与 [source-excerpts.txt]（本地来源，未随附原始材料）。`raw/`、生成classes/stubs均为本地复跑文件，不能假设在其他机器已存在。主证据仍是源DEX和冻结APK。
