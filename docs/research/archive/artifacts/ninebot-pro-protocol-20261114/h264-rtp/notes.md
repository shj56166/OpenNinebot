# H.264/RTP 与控制路径独立审计

日期：2026-11-14。范围为发布 APK v0.6.1 的静态实现及离线原方法验证，不含 Android 编码器实机执行或仪表实测。未修改产品代码。

## 原始身份、验证方式

- APK：`%TEMP%/ninebot-pro-v0.6.1.apk`；SHA-256 `bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f`。
- JADX 自动输出 `%TEMP%/ninebot-pro-061-jadx/sources/defpackage`；JADX 的 `defpackage` 是展示目录，DEX 真实类名为 `Lsd;` / `Lo31;`，dex2jar 后在默认包。
- `o31` 自动反编译存在大量类型恢复错误；编码、分支与调度参考 `%TEMP%/ninebot-pro-061-fallback/sources/defpackage/o31.java`。本目录保留 `fallback/sources/defpackage/sd.java` 以核验关键包字段。
- `dex-evidence.json` 记录原 DEX 指令哈希，`dex-instructions.txt` 为 `sd.h/i/k/l`、`v01.l/L/d0` 的原 DEX 反汇编。`o31.a` 单独记录指令哈希，但没有在桌面执行 Android 编码流程。
- 原 APK 经 dex2jar 2.4 转为 `%TEMP%/ninebot-pro-061-dex2jar.jar`；ASM 只保留目标 `sd`、`v01` 方法并移除无关 Compose 静态初始化和接口，目标方法不重写。Java harness 提供环境桩并拦截 DatagramSocket.send，完全不向车辆发包。dex2jar 缺 StackMapTable，JVM 运行使用 `-Xverify:none`。这验证包构造逻辑，不能替代 Android 端或仪表验收。
- `pwsh -NoLogo -NoProfile -File run-validation.ps1` 可重跑；依赖原转换 jar、ASM 9.5、Android Studio JBR。210 组直接包场景、29 组 KCP 入队场景、5103 项断言通过，见 `validation.json`。包括原方法的长度前缀转换、NAL 扫描、心跳生成和识别。KCP 入队测试执行原 `sd.l`，但 `ks0/js0` 是容器/回调桩，只验证发送器入队行为，KCP 算法本身由另一分支验证。

## 分支入口与编码

`o31.a` fallback 233..243：首先检查配置 `a02.T`，为 JPEG 时直接调用 `o31.b()` 并返回，**不会进入 KCP 构建**。`d02.F()` 仅返回 `a0`；JPEG+KCP 即使保存成组合配置，在当前发送流程仍走直接 JPEG。H.264 根据 `d02.F()==c02.T` 创建 `ks0`，否则走直接 UDP。

`o31.a` fallback 314..370 / 395..465：使用系统 MediaCodec `video/avc`，码率来自 V，帧率来自 U；I 帧间隔 1 秒，priority=0、latency=1；Android 29+ max-bframes=0；AVC profile=1（Baseline）。按 `d02.z` 的宏块和每秒宏块数选择 Level 3 / 3.1 / 4（Android 常量256/512/2048）。这些是请求参数，不是编码器实际输出的实测承诺。Surface 路径优先：Bitmap → OpenGL → encoder Surface；初始化失败释放 Surface/编码器，再尝试 flexible YUV420 输入，CPU ARGB→YUV420。如果仍没有匹配编码器或取不到 input Image，失败退出。

输入队列同时在途 PTS 条目少于 2 才尝试取新画面；`od.I(now,next,last,dirty,fps)` 仅当 now>=next 且（dirty 或没有先前输入或距上次输入>=max(100,2*framePeriod)）才送帧。`od.E` 根据是否落后超过半个帧周期选择 `now+period` 或 `next+period`。输入 PTS 为启动以来单调经过毫秒×1000。每轮约 parkNanos(1ms)，并且约每 500ms 在准备取帧时请求同步帧；未到取帧条件时不会专门发关键帧请求。

`o31.a` fallback 935..1208：输出格式变化时读取 csd-0/csd-1，逐项转 Annex B 后拼接成 codecConfig。BUFFER_FLAG_CODEC_CONFIG（2）输出单独缓存；BUFFER_FLAG_PARTIAL_FRAME（8）输出继续拼接，同一 AU 各部分 PTS 不一致报错。完整 AU 的关键帧由 BUFFER_FLAG_KEY_FRAME（1）或 NAL type5 判定。关键帧若缺 type7/8 任一项则前置 codecConfig，合并后仍缺 SPS/PPS 则失败。

`v01.l`：输入为空原样返回；开头是 3/4 字节 Annex B start code 则 clone；否则只接受**四字节大端 NAL 长度**列表，将每个长度转换成 `00 00 00 01`。长度0、越界或末尾长度不足4字节会失败。它不是任意 AVCC（1/2/3字节 NAL 长度）的通用解析器，也不是 avcC 配置记录解析器。`v01.L` 在 Annex B start code 后检查 NAL 首字节低5位。

发送 RTP 时间戳为 `max(0,(outputPTS-firstCompleteFramePTS)/1000)`，单位毫秒，线上低32位；不是90kHz时间基准。它从第一个完整输出帧开始归零。每个完整 AU 按原字节切片，无 FU-A/STAP-A 负载封装。

## H.264 RTP 每字节布局

`sd.l` 自动输出约89..181；fallback 246..448；DEX 指令详见 `dex-instructions.txt`。

| 字节 | 值 / 编码 |
| --- | --- |
| 0 | `90`：RTP v2、extension=1，无 CSRC |
| 1 | `20`（PT32）或尾片 `a0`（Marker=1） |
| 2..3 | 大端 sequence；初值0，每次发出的每片递增，低16位上线 |
| 4..7 | 大端 timestamp 低32位，同一帧及其重复副本一致 |
| 8..11 | 全0，发送 SSRC=0 |
| 12..13 | 全0，扩展 profile ID=0 |
| 14..15 | `00 01`，扩展长度=1个32bit字 |
| 16 | `00` |
| 17 | `(key?1:2) | (first?0x10:0) | (last?0x08:0)` |
| 18..19 | 小端 `0x400 | (timestamp & 0x3ff)`；10位毫秒余数，不是独立帧计数器 |
| 20..末尾 | 直接复制 AU 对应字节，每片最多1380字节 |

**已修正 JADX 类型错误**：自动输出把 byte17 的整数 OR 恢复成 `==true ? 1 : 0`，不能照抄。fallback 指令分别 `or-int`，原方法执行确认单片关键帧 `0x19`，非关键帧 `0x1a`；多片关键帧首/中/尾为 `0x11/0x01/0x09`，非关键为 `0x12/0x02/0x0a`。

媒体最多1380+20=1400字节 UDP payload。空帧不发。`sd.S` 默认为0，不随机化。`l21.k` 默认 true，并由 `i21.e→o31.d` 传递“重复发送关键帧”。重复开关与 key 同为 true 时，**完整帧所有分片按相同 timestamp 连发两遍**，第二遍继续增加 RTP sequence，不重用序号。单帧重复不等于 KCP 重传。

固定原方法向量：payload=`00`，key=true，repeat=false，sequence=`1234`，timestamp=`12345678`：

```text
90 a0 12 34 12 34 56 78 00 00 00 00 00 00 00 01 00 19 78 06 00
```

## 原发送器进入 KCP 队列的交界

同一 `sd.l` 方法在 `k31.b` 非空时不直接调用 UDP，而是把已完整构造的每个 RTP 包按最多1376字节再分成 `js0`，`frg` 从分片数减一递减至0；每个 RTP 包独立成为一个 KCP 消息。它检查的是本包分片数严格小于 `ks0.l`，相等即不入队；无论入队还是被此检查丢弃，该 RTP 序号已被消耗，且仍调用一次 `ks0.h(now)`。此处不更新 direct UDP 计数数组，KCP 的真实线上包统计由输出回调处理。

新增29组原方法入队验证使用相同参数先生成原 direct RTP，再逐包合并队列分片，要求字节完全相等。AU1356→RTP1376→1段；AU1357→RTP1377→1376+1；AU1380→RTP1400→1376+24；AU1381→RTP1400与21→两段加一段。覆盖空AU、key/repeat四组合、序号65535越界、timestamp低32位，以及send window=1/2/3时严格小于条件；关键帧重复副本各自保留独立递增RTP序号并重新分段。没有用这些容器桩来声称执行了真正KCP重传或网络互通。

## 20字节心跳、反馈与停止

`sd.h` 生成 H.264 控制请求：

```text
85 cc 00 04 00 00 00 00 00 00 00 00 II II II II 02 00 00 00
```

II 为小端 index；请求 SSRC=0，APP name位置全0，mode=2。这是 RTCP APP 形状的私有报文，不能称作标准 `mpv\0` 心跳。

`sd.i` 严格要求回复**长度恰为20**、头 `85 cc 00 04`、SSRC=`00000001`、name位置全0、尾部=`02000000`，才返回 offset12 的小端 index；否则返回 -1。自身请求不能被当回复，16或24字节包也不会匹配。注意 index=0xffffffff 本身也被解释为-1，与失败哨兵同值；在实际32位序号溢出前未影响一般测试，但不宜复制这种API设计。

`o31.a` fallback 1405..1490：会话开始约1秒后首次发 heartbeat，其后按循环时间每1000ms发送，index首值1；维护最近最多8个发送时间；每轮最多接收8个5013包（2048字节缓冲，socket timeout 1ms），合法且存在待匹配 index 才计回复和RTT，其余只记录数据长度/前48字节。

**没有发现“无心跳回复便停止视频”、动态修改帧率/码率、解析mode或conv的分支。**心跳只用于统计与日志。KCP的数据确认在UDP5012，独立接入 `ks0.d`；不应把5013统计缺失判断为KCP没有ACK。

`o31.a` 正常结束/异常清理均尝试向5013发 `80 cb 00 00`，关闭控制与媒体socket，停止/释放编码器，关闭EGL/Surface、Bitmap、文件、画面源。它是PT203且SC=0的4字节包，没有SSRC列表，不能当作所有接收端认可的标准BYE保证。没有等待停止确认，也没有看见等待KCP队列全部确认后才关闭的流程。

循环终止条件是 `o31.g`，或没有P2P route的离线自测满3秒；编码/来源/网络异常也进入失败清理。静态代码未验证某款仪表实际显示，也不能由“已发送”或 `SELF_TEST_OK` 推出显示成功。

## 与此前官方九号路径交叉对照

采用 `tmp/ninebot-static-gap-20261009` 的固定证据；`docs/research/ninebot-static-gap-20261009/evidence-summary.json` 保留了各类哈希与此前原Java编码向量。不能把 Ninebot Pro 的实现视为官方协议的完全等价还原。

| 项 | Ninebot Pro H.264 | 官方App此前固定静态证据 |
| --- | --- | --- |
| 媒体切片 | 1380+20=1400 | RtpSender每片媒体1400，再加20字节头 |
| SSRC / sequence | SSRC0，sequence0起 | 媒体SSRC随机；Participant SSRC1是对端资料 |
| 扩展 | 首末标志、帧类型、低10位时间字段 | pictureType (`frameType==2→1`，其余2)，其余扩展字节为0 |
| timestamp | 相对编码PTS毫秒 | System.currentTimeMillis低32位 |
| 控制请求 | 20B，name零，mode2 | 16B，`mpv\0`，序号LE |
| 控制反馈 | 只严格20B模板并统计 | APP subtype1..5可链路质量/帧率/码率/mode；5的data>=12读conv |
| 心跳失联 | 不停止发送 | 连续3次超时回调令sendEnable=false |
| KCP会话 | 固定conv3 | startkcp(false) conv0开始、反馈可更新conv |

JPEG另有g71 1400字节媒体切片和随机SSRC，不得把H.264表应用到JPEG。BLE半彩/MPEG2走官方native BLE路径，Ninebot Pro这三条Wi-Fi预设没有覆盖它；控制/认证细节由总报告另述。

## 待实车/后续实现验证

这些字段已经以原 DEX 和原方法离线输出闭合，但接收端是否要求某一版本扩展、心跳、SSRC、时间基准仍需实车。七寸、4.3寸待实测；五寸成功仅作者自述，未有本轮原始pcap。实现时优先复用现有编码画面源，独立协议适配，逐协议建立故障、超时、队列/关键帧恢复和停止行为，不应把APK缺失的超时流控当作产品完成状态。
