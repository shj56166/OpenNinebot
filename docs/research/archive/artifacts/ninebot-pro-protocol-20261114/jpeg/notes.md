# Ninebot Pro v0.6.1：4.3 寸 JPEG 分支完整静态研究

研究日期：2026-11-14。研究对象 APK 的 SHA-256 为 `bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f`。本记录区分 APK 实际构造的字节、APK 对响应的处理和仪表端尚未得到的证据。

## 证据与验证方式

- 自动反编译目录：`%TEMP%\ninebot-pro-061-jadx\sources\defpackage`。JADX 的 `defpackage` 是合成包名；DEX 的真实混淆类位于默认包。
- `o31.b` 自动反编译失败，使用 `%TEMP%\ninebot-pro-061-fallback\sources\defpackage\o31.java` 的指令级输出核对完整主循环。`g71` 另生成本目录 `fallback/sources/defpackage/g71.java`。
- 原 APK 的 DEX 经 dex2jar 转换后，对**未修改的 `g71.class` 与 `sd.class`**执行 JVM 测试。仅 `o31/j31/fe` 被最小桩替代：提供停止标志、捕获数据报的 inert socket、统计数组和非法输入异常。没有绑定端口或发网络包。
- `JPEGVectors.java` 实际调用上述两个原始类；`validation.json` 记录加载来源、类哈希、报文字节与 151 条断言结果。验证覆盖 6 种分片边界、头字段、回绕、非法首尾、心跳识别、SR/BYE、停止/发送失败/无 socket 分支。
- dex2jar 生成的 class 缺少 JVM 要求的 stackmap，使用 `-noverify` 执行；这是 DEX 转换运行的技术条件，不是 Android 平台测试。没有改写两种待测类的方法。`g71-javap.txt`、`sd-javap.txt` 可复核执行字节码；fallback 又提供独立的 DEX 指令顺序对照。
- `evidence-excerpts.json` 保存所有主要证据的文件哈希和带原始行号的摘录。运行入口：`python artifacts/ninebot-pro-protocol-20261114/jpeg/verify_jpeg.py`，支持 `--jar`、`--apk`、`--jbr` 指定输入。

## 1. 分支选择、配置和画面来源

`o31.a` 的最前置编码分支为 `e.A()==a02.T → b() → return`（auto `o31.java:4321`）。即 JPEG 优先于后面的 H.264、KCP、编码器选择；即使手动配置同时设为 JPEG 和 KCP，`o31.b` 仍直接发 UDP，并不会构造 `ks0`。

`d02.E` 默认：480×272、10 FPS、bitrate=1500000、rotation=0、默认缩放、JPEG、quality=80、DIRECT。JPEG 主循环只用画面尺寸、帧率、画面转换配置和 JPEG quality，不读取 bitrate 作速率控制，也没有 H.264 关键帧重复逻辑。不要把 1.5 Mbps 标为 JPEG 的限速或保证带宽。

`d02` 通用校验：宽高均为 64..1920 偶数、总像素不超过 2073600；FPS 5..30；码率250000..8000000；角度0/90/180/270；JPEG quality 30..95。通用配置允许两种编码与两种传输枚举组合，但 JPEG 实际忽略 KCP 选择。

会话在 `l21.h:158..210` 创建：TEST/SELF_TEST 使用默认测试图源（`n10`）；其他源使用 `l31`。枚举 `j21` 包括图片、视频、摄像头、手机屏幕。`l31.e` 从 `f3` 的最新帧槽取 Bitmap，按预设旋转和缩放画到黑底 Canvas；已有 Bitmap 而没有新帧时仍返回 true。JPEG 主循环会按配置频率重新压缩这个保留画面。这里说明 JPEG 编码的直接输入统一为 Canvas/Bitmap，不证明每种上游采集已实测，也不能把输出帧数等同于新采集帧数。

## 2. 建立 JPEG 发送循环

以下行号来自 fallback `o31.java`：

1. `2089..2115`：创建 `g71(SecureRandom.nextInt())`，其 `a` 为每次会话随机 32 位 SSRC；另一个随机整数转为无符号32位作 timestamp 初值 `T0`。序号 `g71.b` 默认 0。
2. `2117..2126`：存在 P2P 对端时分别创建 5012/5013 socket；无对端为本机自检/探测模式，不创建网络 socket。
3. `2130..2178`：可选创建输出 `.mjpg` 文件；按预设尺寸创建 ARGB_8888 Bitmap、Canvas、Paint 和复用压缩 ByteArrayOutputStream；记录单调起点 `S0=elapsedRealtime()`。
4. `2234..2310`：未停止时，在预定时间调用图源 `e(canvas,paint,encodedFrameCount)`。有图像则 `Bitmap.compress(JPEG, quality, stream)`；失败抛异常；成功结果写入可选本地文件并交给 `g71.c` 分片。编码后调用图源 `j()`。
5. 同一帧所有分片 timestamp 为 `(T0 + elapsedRealtime_at_frame - S0) & 0xffffffff`，是毫秒时间基准，不能替换成标准视频90kHz。最终 SR 使用最后一个该 timestamp。
6. `od.E:91..94`：帧间隔为 `round(1000/FPS)` ms；如果比预定点迟超过半个间隔，下次从当前时刻加间隔，否则沿原预定点加间隔。没有无限追赶积压帧。循环末 `parkNanos(1000000)`（fallback `2559..2567`），但实际压缩和发送时间仍可能限制 FPS。
7. 无对端本机模式运行约 3000 ms 后正常结束。`JPEG_SELF_TEST_OK` 是该路径正常结束时的日志字符串；不是仪表验收，也不是 JPEG 解码器验证。无 socket 时 `g71.c` 仍累计封包数及字节数，JVM 已验证。

## 3. JPEG/RTP 精确封包

`g71.c` 先检查总长度>=4，起始 `ff d8` 和末尾 `ff d9`，不遍历 JPEG 标记、量化表或熵编码，不验证内容可解码。JVM 中仅四个字节 `ff d8 ff d9` 也可通过；这只是首尾检查的范围。

完整 JPEG 字节（包括 SOI/EOI）按每片最多 **1400 字节**切分。每包在媒体前加20字节，故 UDP datagram payload 最大 **1420 字节**；该值不含 UDP/IP/Wi-Fi 头。它既没有标准 RFC2435 JPEG payload header，也不是标准 JPEG RTP PT=26。

| 偏移 | 长度 | 内容与端序 |
| --- | --- | --- |
| 0 | 1 | `90`，RTP v2、extension=1、padding=0、CSRC count=0 |
| 1 | 1 | 通常 `20`（PT32）；该 JPEG 最后一个分片为 `a0`（Marker=1） |
| 2 | 2 | 包序号，无符号16位大端；初值0，每包加1，65535后回到0 |
| 4 | 4 | 同一帧 timestamp，低32位大端，见上节 |
| 8 | 4 | 会话随机 SSRC，大端；同一会话所有帧相同 |
| 12 | 2 | RTP extension profile=`0000` |
| 14 | 2 | extension length=`0001`，1个32位字 |
| 16 | 4 | 固定 `00 01 00 00`，不能套用 H.264 分支的首末片位标志 |
| 20 | <=1400 | 原始 JPEG 分片字节 |

分片顺序连续；没有 JPEG 重传、KCP、FEC、关键帧复制、单独frame-id或片序号字段。接收端至少需要按 SSRC/timestamp/序号/Marker 重组，但仪表的丢片容错与丢帧策略未知，不能替它推定。

实测合成头（SSRC=11223344，timestamp低32位01020304，第一包seq0、非末片）：

```text
90 20 00 00 01 02 03 04 11 22 33 44 00 00 00 01 00 01 00 00
```

JVM 边界：4/1399/1400字节分别1包；1401/2800分别2包；2801为3包。每个向量都实际执行 APK 类并将分片重新拼接，与输入逐字节比较。

`g71.c/d` 是 SR 的媒体包数/媒体字节数（不含20字节头）；`j31` 数组统计包数/完整datagram字节数。两者数值含义不同。停止检查在包构造且序号增加以后、发包以前：取消或发送异常可能消耗一个序号，但不会增加该失败包的 SR 统计；JVM 已覆盖。

## 4. 5013 心跳与响应识别缺口

JPEG 请求由 `g71.b` 生成，16字节：

```text
85 cc 00 03 00 00 00 01 6d 70 76 00 ss ss ss ss
│  │  │     SSRC=1     │ mpv\0    │ seq little endian
│  │  RTCP length=3    APP name
│  PT204
v2 / subtype5
```

fallback `2357..2413` 显示首次在会话起点+1000ms发送，首个index=0，以后每约1000ms index++。历史记录只保留最新8个待回应序号；每轮最多收8个控制datagram，buffer2048，socket read timeout=1ms。代码用 `sd.i` 解析，得到的序号只有命中历史才计作回应和计算 RTT；否则只记原始HEX。

**关键缺口：共享 `sd.i` 并非通用 RTCP APP parser。**它只接受长度恰20、头 `85 cc 00 04`、SSRC=1、name四字节全零、末尾 `02 00 00 00` 的回复，再从12..15取小端序号。故16字节 `mpv` 回复会被拒绝；24字节回复同样会被拒绝。不能推定仪表一定回显请求；这里证实的是请求生成器和接收识别器使用不同报文约束。必须抓到实际仪表反馈才知道哪些回复受影响。

JVM 调用原 `g71.b` 和原 `sd.i` 已确认：16字节请求/回显→-1；满足该20字节格式→原index；额外延长到24字节→-1。JPEG没有把“没有匹配回复”作为停发门槛，也没有根据反馈调整帧率、码率、模式或conv；主要是统计和原始日志。因此日志显示零回应不能单独证明无显示，也不能用于宣布连接成功。

## 5. 停止、错误和 RTCP SR/BYE

外层 `l21.p:349..378` 将发送器停止标志 `g=true` 并中断线程。P2P失效由 `l21.l` 调用该停止过程。每包发送前检查 `g`，主循环也检查；中断本身不等同于远端停止确认。

JPEG正常停止、自检3秒结束、异常退出和 finally 异常清理，都在5013 socket仍存在时尝试 `g71.a(lastRtpTimestamp,System.currentTimeMillis())` 的72字节复合报文；发送失败被忽略，然后关闭5013/5012、回收Bitmap、关闭可选文件和图源、回调结束。证据 fallback `2619..2654`、`2738..2804`。

`g71.a` 输出：

| 偏移 | 内容 |
| --- | --- |
| 0..3 | `80 c8 00 06`，RTCP Sender Report，无接收报告，28字节 |
| 4..7 | 同媒体随机SSRC，大端 |
| 8..11 | NTP秒=`unixMillis/1000+2208988800`，大端低32位 |
| 12..15 | NTP fraction=`(unixMillis%1000)*2^32/1000`，大端 |
| 16..19 | 最后媒体 RTP timestamp，大端低32位 |
| 20..23 | g71媒体packet count，低32位大端 |
| 24..27 | g71媒体payload octet count，低32位大端 |
| 28..31 | `81 cb 00 0a`，BYE含1个SSRC，44字节 |
| 32..35 | 同SSRC，大端 |
| 36 | 原因长度21 |
| 37..57 | ASCII `jlibrtp says bye bye!` |
| 58..71 | 14字节全零 |

报文两段长度28+44与总长72自洽。但原因长度21只需要2字节对齐，实际14个零；且复合包没有 SDES/CNAME。这些是与通用 RTCP 互通需复核的边界，不应将它描述为“完整标准RTCP会话”。没有证据表明目标仪表要求、接受或忽略这些额外零，不能仅凭标准差异判定实际不兼容。

## 6. 与此前官方 App 的对照和未覆盖分支

官方静态配置 `ScreenCastRequest.videoConfig` 有 MPEG2/H264/MJPEG 三种枚举，`RtpSender.send:227..240` 是对编码器给出的字节流按1400切片、PT32、扩展picture type、毫秒时间戳。它说明官方框架具备 MJPEG 配置选择，不证明全部4.3寸仪表接受 Ninebot Pro 的格式，更不证明官方特定车型最终运行MJPEG。

Ninebot Pro JPEG沿用与官方静态格式相似的20字节RTP外形、1400媒体切片、16字节mpv心跳；但这里JPEG时间戳采用随机偏移后的单调毫秒。H.264分支则有不同片长、SSRC、扩展标志和20字节心跳。不能把三种配置简单做成统一分辨率表。

BLE/MPEG2 是此前官方 SDK 的另一承载与编码组合。此 APK 的 JPEG 实现没有BLE、MPEG2编码、V3Auth或官方SO发送入口，不会因为分辨率小就自动覆盖BLE半彩屏。iOS成功投屏抓包仅是七寸README自述；APK/仓库没有公开原始pcap或iOS源码给本分支交叉验证。

## 7. 可交付程度与建议

已闭合：APK内部编码分支、参数读取、循环调度、JPEG/RTP每字节、分片边界、统计、心跳生成/严格识别、停止和SR/BYE构造。纯封包与识别方法已运行原APK转换字节码。

未闭合：真实JPEG内容在Android编码的表现、全部图源的性能与权限、仪表是否接受该私有RTP、实际心跳回复、4.3寸车型/固件范围、丢片/停止/重连行为、仪表实际显示。作者仅声称手机JPEG编码验证，不应标为车辆投屏成功。
