# Ninebot Pro 0.6.1 协议研究证据与复跑

总报告：[完整协议分支、官方链路对照与交叉验证](../../docs/research/NinebotPro完整协议分支与交叉验证-20261114.md)。研究日期2026-11-14；只进行了静态分析、局部原方法执行和合成KCP互通，没有安装APK、运行Android采集编码服务或连接车辆。

## 输入与目录

APK `com.example.ninebotpro`，versionCode 9，2,310,026字节；SHA-256：

```text
bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f
```

原APK经电脑代理 `http://127.0.0.1:7892` 下载。仓库README和文件树固定到 `4216b9fef28750ce6ffe3619114d5e4ca8017ae4`；上游C KCP固定到 `7346e366f1928dd727ecbb53253454fdaecaebb7`。

| 位置 | 内容 |
| --- | --- |
| [evidence-inventory.json]（本地来源，未随附原始材料） | APK/DEX/SO、反编译输入、网络快照和复跑依赖的逐文件哈希；运行时版本 |
| [connection-auth/notes.md](connection-auth/notes.md) | P2P目标、认证边界、配置、状态机与34项局部验证 |
| [h264-rtp/notes.md](h264-rtp/notes.md) | 编码流程、NAL/RTP/RTCP、原DEX指令和5103项断言 |
| [jpeg/notes.md](jpeg/notes.md) | JPEG分片、反馈、SR/BYE和151项断言 |
| [kcp/notes.md](kcp/notes.md) | 原KCP内核24项验证、固定上游C双向互通13项 |
| 各目录 `cross-review-*.md` | 子代理交叉阅读原证据、复跑及其覆盖限制 |
| [validation-summary.json](validation-summary.json) | 最终离线复跑、输入哈希和本地文档链接核验汇总 |
| `raw/` | 本机冻结的APK、转换jar、JADX/fallback源、官方历史源、工具和上游源码；Git忽略 |
| 各目录 `build/` | 可重建的class、测试桩和合成报文；Git忽略 |

原始类在默认包中；`defpackage`只是JADX展示名称。JADX 1.5.6报告96个自动反编译错误，所以关键控制流同时核查fallback、原DEX指令或转换字节码，不能以自动生成的Java独立作为正确性依据。

## 离线复跑

已冻结的本机输入在`raw/`。正式复跑不再依赖TEMP，也不联网下载KCP。需要Python 3、PowerShell 7、JDK及gcc；连接测试另使用Android SDK的`android.jar`作类型链接，不启动模拟器。

本机JDK为Android Studio JBR 21.0.9，gcc为MinGW-W64 16.1.0。具体版本输出见索引。默认JDK路径为 `C:\Program Files\Android\Android Studio\jbr\bin`，Android SDK为 `%LOCALAPPDATA%\Android\Sdk\platforms\android-36\android.jar`。路径可通过参数覆盖。

在仓库根目录运行：

```powershell
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-pro-protocol-20261114\run-all.ps1
```

仅检查现有报告、输入哈希和文档链接：

```powershell
python -X utf8 .\artifacts\ninebot-pro-protocol-20261114\audit_evidence.py
```

独立分支也可运行：

```powershell
python -X utf8 .\artifacts\ninebot-pro-protocol-20261114\connection-auth\run_validation.py
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-pro-protocol-20261114\h264-rtp\run-validation.ps1
python -X utf8 .\artifacts\ninebot-pro-protocol-20261114\jpeg\verify_jpeg.py
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-pro-protocol-20261114\kcp\run-validation.ps1
pwsh -NoLogo -NoProfile -File .\artifacts\ninebot-pro-protocol-20261114\kcp\run-interop.ps1
```

最后一条会先复跑KCP内核，再将原APK方法与独立上游C实现通过本地文件交换合成数据报，验证双向分片/重组/ACK；不会创建车辆网络连接。各工具遇到编译、执行或核验失败会以非零状态退出。

`run-all.ps1`接受 `-Python`、`-JavaBin`、`-AndroidJar`、`-CCompiler`。两个Python分支接受 `--jar`、`--jbr`；JPEG另有`--apk`，连接另有`--android`。H.264脚本接受`-InputJar`、`-AsmJar`；KCP脚本接受`-TranslatedJar`，互通另有`-SourceDir`。互通工具会核查上游C/h的固定哈希。

## 方法和计数限制

- H.264：210组直接封包基准、5103条断言；其中29组同时验证原`sd.l`的KCP入队。两组数字不能相加为239个独立基线。KCP容器/回调为桩，内核由另一组验证。
- 连接：34条断言执行原`x32`、`hd1.a/d`及`d02`，使用P2P值对象/枚举桩并跳过Android构造；定时器、系统建组、重连流程是静态审阅。
- JPEG：151条断言执行原`g71/sd`；图源、输出回调等使用桩，不执行Android JPEG压缩。
- KCP：24项原`ks0/js0/is0`内核状态验证，13项独立C互通；不能推导真实七寸仪表接受conv=3或显示成功。
- 转换jar缺少部分JVM stackmap，因此使用`-Xverify:none`或`-noverify`。H.264用ASM移除无关初始化/接口，目标方法指令不改写；JPEG和KCP加载原转换方法。枚举转换异常在连接测试中单独桩化并记录。
- 跨代理复跑用于独立核对，不重复计覆盖；5325为各分支记录的检查总数，不能理解为5325项实车测试。

## 冻结工具的来源

[collect_evidence.py]（本地来源，未随附原始材料）用于最初收集或补全本机研究输入，不是执行测试的必要步骤。它保留已有网络快照，将原APK、转换jar、选定历史证据、dex2jar 2.4发行包及完整工具目录、ASM 9.5、固定上游`ikcp.c/h`和LICENSE写入Git忽略的`raw/`，并生成逐文件哈希。缺失的网络快照通过上述代理补取，不覆盖已经冻结的快照。

`raw/`未提交到Git，仅复制这份报告或克隆仓库不会自动得到原始APK和工具依赖。换机器复跑需另行保留这些本机输入，并按索引校验；不应把占位文件或重新生成的不同版本jar当作本次已验证输入。
