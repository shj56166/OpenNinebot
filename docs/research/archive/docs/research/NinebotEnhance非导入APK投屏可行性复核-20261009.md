# NinebotEnhance：非导入 APK 投屏可行性复核

> 2026-11-15续研：[半彩屏BLE完整链路与native复核](九号半彩屏BLE投屏完整链路与Native复核-20261115.md)。第8.6节当时未解析的具体future和GATT消费者已在新报告闭合；新增实际FFmpeg入口、RTP原方法及ARM64验证，车辆认证与实车兼容仍分别限定。

> 复核日期：2026-10-09。  
> 对象：NinebotEnhance 1.1.15，GitHub 提交 `9044b52d5964aff4f98c5284728634dc4c511472`。  
> 条件：HT2 为 Android 13 / API 33、已具备 root、已安装九号出行 6.10.11；附近没有实车。本轮只读分析，未安装模块或改变车辆连接。  
> 与认证研究配套阅读：[九号 Gen3 与 2025 后车型蓝牙握手深度研究](九号Gen3与2025后车型蓝牙握手深度研究-20261009.md)。

## 1. 可行性结论

| 目标 | 判断 | 实际依赖 |
| --- | --- | --- |

## 2. 当前代码实际承担的工作

上游固定版本入口：[README](https://github.com/Margele/NinebotEnhance/blob/9044b52d5964aff4f98c5284728634dc4c511472/README.md)、[架构说明](https://github.com/Margele/NinebotEnhance/blob/9044b52d5964aff4f98c5284728634dc4c511472/docs/architecture.md)。

| 部件 | 核验到的职责 | 对独立投屏的意义 |
| --- | --- | --- |
| `MirrorModule` | 在 `cn.ninebot.ninebot` 主进程安装 Hook，替换捕获路径的 Bitmap、返回画面及 View/Canvas 绘制 | 可接入自定义画面，仍运行在官方进程 |
| `FrameClient` | 接收 RGBA，生成替换 Bitmap；绘制模式直接画 Canvas | 提供画面源，未实现车辆认证 |
| `FrameBridgeService` | 经 Binder 传递元数据、Surface 和会话句柄；校验调用 UID | 是内部进程桥，不是现成的第三方 App SDK |
| `DrawSession` | 保存绘制会话、监视九号进程死亡，不启动采集辅助进程 | 自有仪表画面可以省去任意应用副屏采集 |
| `StatisticsHooks` | 观察官方编码回调、RTP/BLE 发送器和发送队列 | 明确指出实际发送仍依赖官方类 |
| `HookCatalog` | 汇总官方类/方法/资源目标并在运行时核验 | 官方 App 更新会影响桥接兼容性 |

源码证据：

新版支持官方 App 6.10.10、6.10.11 和 6.10.12；其他版本仍尝试 Hook 并提示未测试、列出缺失目标。6.10.12 的加固业务类需运行时核验。这里的“支持”是上游声明及目标核验范围，不等于 HT2 与所有车辆组合都已实测。

## 3. 指向项目能解决什么

| 项目/框架 | 能提供 | 仍需别处提供 |
| --- | --- | --- |
| [VirtualDisplay](https://github.com/Ynkcc/VirtualDisplay/tree/67aabb32b87ff9ce90ff31b5e35f53b90ab8bb35) | 建立本地虚拟屏、在副屏启动应用、预览和触摸控制 | 九号车辆认证、仪表会话及媒体格式 |
| [VirtualDisplayDaemon](https://github.com/Ynkcc/VirtualDisplayDaemon/tree/0552fe9d9abfbde6e6a21ca71a86976c47b0ceba) | scrcpy 子模块及显示注册、应用启动等补丁 | 九号 BLE/Wi-Fi 车辆客户端 |
| [scrcpy 参考分支](https://github.com/ynkcc/scrcpy/tree/2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0) | Android 显示捕获、编码、输入及辅助进程技术 | 九号接收端兼容协议 |
| [Shizuku/Sui API](https://github.com/RikkaApps/Shizuku-API) | 让画面辅助进程使用 shell/root 权限 | LSPosed Hook、车辆密钥和认证 |
| LSPosed/libxposed | 在官方 App 进程里接入画面入口 | 自身不提供九号协议 |

`VirtualDisplayDaemon` 的固定提交没有根目录 `README.md`。本轮通过 GitHub tree 核验了 `.gitmodules` 指向 scrcpy，以及 `VirtualDisplayRegistry.java.patch`、`ActivityLauncher.java.patch` 中的显示创建和应用启动行为，没有把其他仓库的 README 当作该仓库证据。

[第三方声明](https://github.com/Margele/NinebotEnhance/blob/9044b52d5964aff4f98c5284728634dc4c511472/THIRD_PARTY_NOTICES.md)明确说明：NinebotEnhance 将参考项目的视频/网络传输替换为经过认证的 Binder Surface 句柄，并把生命周期绑定到九号会话。它没有把 scrcpy 的网络视频流直接发送给九号仪表。借用守护进程补丁前也不能把父项目或 scrcpy 的许可证自动视为该补丁仓库的授权。

新版另有 DL/ANT/JBD/JK 等 BMS 外设直连；这部分是仪表叠加数据源，不是九号车辆独立认证或独立投屏实现。

## 5. 对 Gen3 和 2025 后车型的影响

但 **车型年份、Gen3 名称和投屏能力不是同一个维度**。车型需要分别核验广播认证版本、仪表板配置、投屏通道/编码和官方开通能力。不能把 2025 后车型统一归入某个帧头或固定投屏参数。

上游目前声明实测 M5P，车型编号 14103，848×480、Wi-Fi Direct、H.264。240×320 蓝牙 MPEG2 半彩屏为按配置适配、待实测。不能把这两个参数套用全部新车型。

若要完全脱离官方 App，缺口仍包括：

- 按广播和车型配置选择协议，完成 V3Auth 云端材料获取、SDK 激活、native 状态机及精确 wire 交互。
- 读取真实仪表投屏配置，执行会话开始/停止、板端反馈和保活。
- Wi-Fi Direct 发现/组网、端口和角色协商，以及官方实际采用的 RTP/KCP 分片、时间戳、关键帧和恢复行为。
- BLE 半彩屏的 MPEG2 编码能力、分片、流控和 native `writeRtpData` 发送行为。

已有静态证据显示官方 Java Wi-Fi 发送器按约 1400 字节分片后交给 RTP 会话；它不等于把任意 scrcpy H.264 流或标准 RFC 6184 打包直接送车就能显示。也没有证据证明所有视频包逐包 AES 加密。

全独立路线可优先研究已经有上游成功记录的 M5P Wi-Fi H.264 路径，但仍需实车得到完整官方会话和媒体证据；不能在没有车辆时宣布端到端实现完成。

## 6. 进一步去掉 LSP 和 ADB

用户补充要求：绕开 LSPosed 和 ADB。需区分产品运行依赖与协议实现依赖。

**ADB 可以从产品运行链路中去掉。**它在此前研究中用于调试、取证和启动临时辅助进程，不是九号仪表媒体协议的一部分。自有主题输出、MediaCodec、普通 App 的 BLE 和 Wi-Fi Direct API 都不以 ADB 为运行前提；仍需相应系统权限及车辆授权。如果选择经 ADB 激活的 Shizuku，才会重新引入这一部署依赖，不能把它当成满足本要求的路径。

| 路线 | LSP | 运行时 ADB | 仍依赖 | 当前判断 |
| --- | --- | --- | --- | --- |
| 设备本地 root 注入官方进程 | 无 | 无 | root、官方 App、专用注入/桥接组件 | 可研究的架构方案，未验证完整投屏 |

若目标是普通 Android App、不使用 root 和进程注入，路线应当是：

这里自有主题不需要系统录屏授权；投第三方 App 的画面才使用 MediaProjection 并取得系统录屏授权。Wi-Fi 全彩屏可以优先研究，BLE 半彩屏还另需 MPEG2 编码和 BLE 分片/流控。不能默认所有 2025 后车型都是 V3Auth，也不能因媒体走 Wi-Fi 就省掉控制层的车辆认证。

此补充未实施注入、未安装组件、未改变官方 App，也未实测九号仪表。它说明的是可选择的替代架构及待补齐内容。

## 7. 本轮完成范围

历史对照：[九号投屏链路与加密研究汇总（2026-09-22）](九号投屏链路与加密研究汇总-20260922.md)。该旧文基于 NinebotEnhance 1.1.2；本轮已确认新版增加绘制、Android 11+ 系统录屏、6.10.12 和非名单版本的运行时目标核验，应以本页判断当前兼容范围。

## 8. 静态缺口续研：投屏控制、配置与媒体包

### 8.1 开停控制和真实配置入口

`ScreenCastHelper` 对 `setNaviStart` 的 payload 为：`00` 停止、`01` SimpleNavi、`02` MapNavi、`03` Cruise。官方流程先检查连接、引擎状态和命令可用性，再准备投屏会话。收到非空响应帧只是该调用的成功条件，不能代替仪表显示验收。

已解码的 Mmax2/N3/Mz 对照命令表中，`setNaviStart` 位于 `tft`，发送/接收命令为 `0x71`、索引 0；这不是全部车型的通用命令地址。相同表还给出 `rSSID`、`rPASSWORD`、`rPORT`、`rWifiPORT` 和 `setWiFiChannel` 等能力入口。仅有命令定义不证明每个车型都支持对应通道。

投屏配置经 `BtFileReader` 下载，文件类型 1，缓存名为 `<SN>_screen_cast_config.nb`：

| 阶段 | 请求 | 接收/校验 |
| --- | --- | --- |
| 可选缓存检查 | `file_read_verify=132`，index 0，空 payload | 16 字节 MD5 与缓存值忽略大小写比较，一致则跳过下载 |
| 开始 | `file_read_begin=130`，index 1，payload `01 08 00` | `getIntAt()` 以小端最多 4 字节解释文件长度 |
| 按组读取 | `file_read_data=131`，index 为组号低字节，空 payload | 每组最多 8 个小包，包量按 `ceil(length/128)` 计算 |
| 文件校验 | `file_read_verify=132`，index 0，空 payload | 响应 index 0、数据长度 16，与本地 MD5 比较 |

小包 index 应为 `0..min(8,剩余包数)-1`，按顺序追加到文件缓冲；完成一组后记录 `successLen` 并推进组号。索引不符合预期时，`readLen` 回到 `successLen`、小包 index 回到 0，继续当前等待。该行为与任意乱序重组不同。

开始/校验等待配置为 1000 ms，数据组为 4000 ms。通用 `write(retry=true)` 最多 6 次尝试，失败间有 300 ms 等待；数据组显式关闭该内部重试，由外层最多尝试 7 次。MD5 不符时再请求一次校验，仍不符返回 `-3`；超时 `-2`、异常 `-1`。这些是静态代码参数，未测量实际车辆耗时。

**读取流程仍会向 BLE 写入读请求。**本次只分析了请求，没有发送。未来实现需要增加文件长度/缓冲边界、分组重试状态等防御；官方代码在部分组超时时没有显式复位接收游标，不能未经核验照抄。MD5 是完整性比较，不是车辆身份认证。

### 8.2 配置决定通道、编码与尺寸

`ScreenCastConfig` 使用字符串数字选择通道，优先取有效的 `rtpChannel`，再取 `channel`，无法解释时回落 BLE：

| 配置值 | 通道 | 当前 Sender 构建 |
| --- | --- | --- |
| `"0"` | BLE | `NBBluetoothRtpSender` |
| `"1"` | WIFI | `RtpSender(ip,port,false)` |
| `"2"` | WIFI_KCP | `RtpSender(ip,port,true)` |

`ScreenCastRequest.videoConfig()` 优先按 `codecType` 选择 `"0" -> Mpeg2`、`"1" -> H264`、`"2" -> MJPEG`；否则按小写 `codecName` 匹配 `mpeg2/h264/mjpeg`，不支持则抛异常。JADX auto 输出的回落 switch 有结构错误，本次用 fallback DEX 指令核对了这条分支。

宽度按 `codecWidth -> dimensionWidth -> 240`，高度按 `codecHeight -> dimensionHeight -> 320`。`bitRate/gopSize/maxBFrame/frameRate` 分别进入 `VideoConfig` 的码率、GOP、B 帧数量和帧率，帧率转换为 Double；该构建路径的 `scaleBitmap=false`。`max_bit_rate`、`hearBeat` 和 `rtcpChannel` 没有在这条构建链中直接应用，不能仅凭字段名承诺其控制效果。

240×320 是缺省值，不是新车型的已知尺寸；存在 MJPEG 枚举也不证明目标仪表支持 MJPEG。目标新车型仍需实际配置文件。

### 8.3 RTP 格式，以及 Wi-Fi/BLE 的参数差异

Java 编码的基础结构为 12 字节 RTP v2 头、4 字节扩展头、4 字节扩展内容，共 20 字节，再加媒体分片。扩展 ID 为 0，长度为 1 个 32 位字；扩展内容的 picture type 映射为 `frameType==2 -> 1`，其余为 2。最后一个分片置 Marker。时间戳来自 `System.currentTimeMillis()`，在线编码使用低 32 位，不能自行换成标准视频 90 kHz 时间基准。

Wi-Fi 每片媒体最多 1400 字节，RTP PT 显式设置为 32。它按编码器输出字节直接切片并加上述扩展，不是已证明可替换为 RFC 6184 FU-A 的打包器。带扩展的示例：

```text
90 A0 12 34 01 02 03 04 11 22 33 44 00 00 00 01 00 01 00 00 00 00 01 65 7F
| RTP header 12 bytes               | ext hdr 4 | ext data 4| payload 5    |
```

这里 PT=32、Marker=1、seq=`0x1234`、timestamp=`0x01020304`、ssrc=`0x11223344`、picture type=1；这是合成向量，不是实车包。

**需要保留三处参数边界：**

- `Participant.setSSRC(1)` 设置的是 Wi-Fi 对端资料，本地发送 RTP 的 SSRC 由 `RTPSession.generateSsrc()` 随机生成，不能硬编码为 1。
- BLE Java 会话的 PT 保持 `BaseRtpSession` 默认 0；BLE 与 Wi-Fi 可复用头结构，但不应共用 PT=32。经过 native `RtpHandler` 后的实际 BLE wire 字节仍未还原。
- BLE 媒体每片为 `MTU-27`。发送最多 20 次尝试，配置的 2 ms 仅在发送失败且还有尝试次数时等待，不是每个成功包都延时 2 ms。`NBBluetoothRtpSender` 另设队列满计数 3、丢帧阈值 2。

Wi-Fi 在本地绑定 `port` 和 `port+1` 两个 UDP 端口，分别用于媒体及 RTCP；目标 IP/端口来自会话配置。不能把当前手机端口绑定方式推成所有车型都使用同一固定 IP/端口。

### 8.4 RTCP 保活和仪表反馈

`BaseRtcpSession.HEART_BEAT_NAME` 为 `6D 70 76 00`，即 `mpv\0`。心跳为 RTCP APP，PT=204、subtype=5；4 字节内容是小端 `sendIndex`。RTCP 头/SSRC/长度用网络端序，不能把整包都按小端序编码。

当 SSRC 参数为 1、index=0 时，16 字节合成包为：

```text
85 CC 00 03 00 00 00 01 6D 70 76 00 00 00 00 00
```

subtype=5 的反馈至少 4 字节时读取 offset 0 的心跳序号，至少 8 字节时读取 offset 4 的 mode；Wi-Fi `RtpSender` 在至少 12 字节时还读取 offset 8 的 KCP conv。这三个整数字段均用小端 `ByteUtil.bytesToInt()`。其他 subtype：1 报链路差、2 更新帧率、3 更新码率、4 更新 mode。

BLE 的超时判断为 `sendIndex-receiveIndex > heartBeatTimeout`，Wi-Fi 使用连续未收到反馈计数并在收到反馈时清零。两个状态机不应直接合并。

**存在关闭 BLE RTCP 实际发送的调用分支。**`DeviceScreenCastRequest` builder 创建 `ScreenCastDataSender(device,false)`，传到 `NBBleSender.sendRtcp()` 后直接返回 true，不发出数据。因此库中存在心跳线程/包构建不证明这个具体投屏入口一定发送心跳；成功返回也不证明包已经到达车辆。

### 8.5 KCP 已还原到 Java 参数和会话更新

Wi-Fi KCP 传输层次为 `完整 RTP 包 -> Ukcp.kcpSend -> native KCP -> UDP`。`RTPSession.startkcp(false)` 从 conv=0 开始；`Ukcp` 初始化使用 `nodelay(2,100,2,1)`、MTU 1444、收发窗口各 10240。

收到 subtype=5、至少 12 字节的反馈后，将 offset 8 的小端整数保存为 `kcpConv`；`onHeartBeat()` 调用 `restartKcpIfNeeded(kcpConv)`。连续 3 次超时回调后调用 `setSendEnable(false)`。这确认了 conv 更新和发送门控的 Java 调用关系，不能直接推出新车型的实际初始 conv 或恢复成功行为。

`Ukcp` 调用 `libkcp.so` 的 native 方法，本轮未继续反汇编该库；`nodelay` 参数的内部处理和原版 KCP 是否完全一致仍需核验。实现时优先使用成熟 KCP 库，并按实际仪表行为校准参数，避免另写可靠 UDP 核心。

### 8.6 BLE native 入口及本次验证范围

旧 Java BLE 路径：RTP 写 `6e400002-0000-0000-006e-696e65626f74`，RTCP 写 `6e400003-0000-0000-006e-696e65626f74`，RTCP 通知为 `6e400004-0000-0000-006e-696e65626f74`。这是官方代码路径，不是目标车辆的 GATT 枚举结果。

启用 SDK 时走 `NbBluetoothDevice.writeRtp -> BleSdkClient.writeRtpData -> RustBridge.writeRtpData`；Java 检查数据非空并核对当前已连接设备的 MAC，再把同一个 `byte[]` 传给 JNI。native 入口 `0x2578a4` 通过 `GetArrayLength` / `GetByteArrayRegion` 将其复制为 Rust 拥有的缓冲，再安排 `LocalPool` 异步执行。具体 future poll 函数仍未解析。`RtpHandler` Debug 格式器可见 payload/header 尺寸、index、SSRC 和 PT 等字段，但构造器及发送消费者未定位。最终 BLE 改写、分片和加密规则仍未闭合，不能照搬旧 Java 直写方案声明支持 Gen3。补充证据见 [REA/native 续研摘要]（本地来源，未随附原始材料）。

离线验证已完成：提取官方 RTP/RTCP 包类，按 fallback DEX 修复 RTP 扩展的单个反编译方法；独立 Python 编码器与 Java probe 的 **4 个 RTP 向量、3 个 RTCP APP 心跳向量完全一致**。验证不含真实编码器输出、native 会话、网络发送或仪表解码。

当前已能建立独立投屏的控制/配置/媒体模块设计，但主要缺口仍是 V3 认证与凭据消费、native BLE RTP、目标新车型配置和实际会话互通。无需 LSP/ADB 的独立运行方案仍有技术路径，尚不能交付为已验证的 Gen3 投屏功能。认证侧的新证据见 [Gen3 研究第 13 节](九号Gen3与2025后车型蓝牙握手深度研究-20261009.md#13-静态缺口续研native-云端调用与凭据存储)。
