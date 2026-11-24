# 九号半彩屏 BLE 投屏研究证据

主文档：[完整链路与native复核](../../docs/research/九号半彩屏BLE投屏完整链路与Native复核-20261115.md)。本轮延续此前官方6.10.11研究，已连接Java编码/RTP、Rust具体future与Android GATT出口；没有连接车辆、发送控制命令、请求认证材料或运行Android编码器。

## 目录与边界

| 目录/文件 | 内容 |
| --- | --- |
| [input-inventory.json]（本地来源，未随附原始材料） | 冻结APK/SO/源快照与工具的哈希，Python依赖版本 |
| [java-media/notes.md](java-media/notes.md) | 实际FFmpeg入口、AVPacket、帧类型、队列、BLE RTP、心跳与停止 |
| [native-rtp/findings.md](native-rtp/findings.md) | JNI→LocalPool→具体RTP poll、RtpHandler类型/构造与归属；保留原反汇编 |
| [ble-security/notes.md](ble-security/notes.md) | boxed writer→JNI→Android GATT、UUID/写类型、认证准备和回调语义 |
| [control-config/notes.md](control-config/notes.md) | 模式控制、配置前提及原ARM64局部动态验证 |
| 各分支 `cross-review-*.md` | 独立阅读、复跑和修订记录；重复复跑不增加覆盖 |
| [validation-summary.json](validation-summary.json) | 最终输入哈希、离线验证计数、UTF-8与文档链接核验 |
| `raw/`及分支`raw/` | 本机原输入、工具和反编译工作副本，Git忽略 |

APK SHA-256为 `02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`；ARM64 librust_lib.so为 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`。前轮Ninebot Pro独立Wi-Fi APK不是本轮官方BLE输入。

## 离线复跑

需要PowerShell 7、Python 3、Android Studio JBR/JDK。Python需要capstone、pyelftools；原ARM64仿真使用本机已保存于`raw/tools/python`的Unicorn2.1.4。Java方法使用冻结的dex2jar输出与ASM9.5，缺stackmap通过`-Xverify:none`兼容，不运行Android模拟器。

在仓库根目录：

```powershell
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-ble-cast-20261115\run-all.ps1
```

可以传入`-Python`或`-JavaBin`覆盖本机运行时。默认JDK为 `C:\Program Files\Android\Android Studio\jbr\bin`。分开运行：

```powershell
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-ble-cast-20261115\java-media\run-validation.ps1
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-ble-cast-20261115\java-media\run-session-validation.ps1
python -X utf8 .\artifacts\ninebot-ble-cast-20261115\control-config\emulate_rtp_forward.py
python -X utf8 .\artifacts\ninebot-ble-cast-20261115\ble-security\emulate_gatt_boundary.py
python -X utf8 .\artifacts\ninebot-ble-cast-20261115\audit_evidence.py
```

原函数局部验证包括：Java队列/切片570条断言、Java BLE会话15条断言、ARM64 RTP转发14组、handler初始化7组、writer/JNI边界24组分段仿真。断言和场景是不同口径，不合计成同一数量的实车测试。

- 队列/切片：目标方法指令保留，移除无关类初始化/继承，使用Android/Kotlin/下游会话桩。
- BLE会话：未裁剪原classes12转换jar，使用null-sender构造避免RTCP线程，再注入本地sink。
- ARM64转发：只执行原`0x2eccd8`，特征查找和环境克隆为桩，在writer入口截获；0字节是内部边界案例，正常Java公开入口拒绝空数据。
- handler初始化：只执行`0x328b70..0x328ba8`，确认字段写值；不证明handler参与这条Java媒体发送。
- writer/JNI：分段执行原boxed writer、数组转换包装和JNI参数构造，分配器/JVM动作有显式hook；不是完整Android/JNI运行。

静态反汇编入口：

```powershell
python -X utf8 .\artifacts\ninebot-ble-cast-20261115\native-rtp\probe.py manifest
python -X utf8 .\artifacts\ninebot-ble-cast-20261115\native-rtp\probe.py dump 0x2578a4 0x26bfec 0x2eccd8
```

FDE、字符串/xref筛选和线性寄存器注释只是辅助，关键调用关系已单独核对原指令和vtable。探测脚本不证明所有未发现调用均不存在。

## 保存和复现限制

`freeze_inputs.py`用于从本项目已有官方研究材料冻结输入，不是常规复跑的必要步骤；原文件不一致时拒绝覆盖旧快照，不删除文件。`java-media/collect_evidence.py`保存原DEX方法及输入哈希。缺失原输入或工具时不能用不同版本的APK/jar替代后声称复现了本次结果。

`raw/`不随Git提交；换机器复跑须另行保存这些本机输入并按索引校验。JDK、Python基础解释器和capstone/pyelftools仍由调用机器提供，版本见索引。capstone模块常量与安装包metadata版本可能不同，native分支记录了两者，不能据该常量差异认定分析了不同SO。

本轮没有产出已验收的半彩屏适配。车辆Gen2/Gen3认证、仪表真实配置、GATT权限、MPEG2实际输出和显示仍须目标设备证据。
