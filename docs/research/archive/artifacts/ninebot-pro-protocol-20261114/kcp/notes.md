# 七寸 KCP：完整静态分支与离线互通验证

研究日期：2026-11-14。对象为 Ninebot Pro v0.6.1，APK SHA-256 `bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f`。这里的协议事实来自固定 APK；仪表是否接受及显示仍未经实机验证。

## 核心结论

七寸是 **H.264 access unit → 私有 RTP → KCP message → UDP 5012**。KCP 是 APK 中的 Java 实现 `ks0`，没有调用九号 `libkcp.so`。控制心跳另走 UDP 5013，不放进 KCP。

`conv` 是 `ks0.a` 的 final 常量 3，不是会话协商结果。输入包 conv 不为 3 会立即返回；5013 回复只进入 `sd.i` 严格 20 字节匹配及统计，不会改变 conv、发送窗口、帧率或码率。APK 没有实现此前官方 App 的“控制反馈带新 conv → 重建 KCP”分支。作者自述依据 iOS 抓包调整参数，公开仓库没有该原始抓包、iOS 源码或可核验的仪表反馈，因此不能将 `conv=3` 当作所有七寸仪表的通用参数。

## 证据与执行方法

- APK 经 dex2jar 2.4 转成 `TEMP/ninebot-pro-061-dex2jar.jar`。从中直接提取原 `ks0.class`、`js0.class`、`is0.class`，替换 `j31` 为只收集数据的输出回调桩。原三个类的字节码不修改；实际类位于默认包，JADX 的 `defpackage` 是合成名称。
- `run-validation.ps1` 在 JVM 执行原 KCP 方法；`KcpHarness.java` 构造输入包与入队节点。入队节点逻辑依据 `sd` 的 fallback 指令镜像编写，因此这部分测试不等于原 `sd.l` 或 Android `o31.a` 主循环已运行。原 KCP 的构造器、header encoder、input、update/flush/重传路径实际执行。
- dex2jar 输出缺少 JVM StackMap frame，执行需 `-Xverify:none`；这是宿主兼容性处理，不是 Android 运行验证。整 APK 存在不能直接在 JVM 跑的 Android/枚举类；本验证不对此作保证。
- `validation.json`：24 个断言通过。含超时、快重传、零窗口、坏包、无界入队、序号回绕等分支。
- `run-interop.ps1`：固定上游 [KCP commit 7346e366f1928dd727ecbb53253454fdaecaebb7](https://github.com/skywind3000/kcp/commit/7346e366f1928dd727ecbb53253454fdaecaebb7)，由 gcc 编译 `ikcp.c/h`，与原 APK KCP 通过文件交换生成报文。13 个断言通过，详细原始报文 hex、上游文件 SHA-256 见 `interop-validation.json`。验证了双向 PUSH/ACK 和 1400 字节消息的片段重组，只证明这些用例的 KCP wire 兼容，不证明整个实现和上游等价或仪表兼容。

## 参数和内部状态

JADX `ks0.java`：构造器/字段 26–42 行，`f()` 313–319 行，`g()` 321–324 行；fallback 同名类 `f`/`g` 与原 JVM 执行确认。

| 参数 | APK 生效值 | 解释 |
| --- | --- | --- |
| conv | 3 | final；每次建立 sender 新建实例，未见动态协商 |
| MTU | 1400 字节 | 一整个 KCP UDP payload 的最大值 |
| overhead / MSS | 24 / 1376 | 与标准 KCP header 一致 |
| nodelay | 1 | 最小 RTO 改为 30 ms |
| interval | 10 ms | update 中允许 flush 的逻辑间隔；不保证 Android 主线程每 10 ms 调度 |
| fastresend | 2 | 两次更高序号的确认可触发快重传；超过 5 次传输后快重传受限，超时重传仍继续 |
| nocwnd | true | 发送上限取本地 send window 与远端 advertised window，忽略拥塞窗口 |
| 本地发送/接收窗口 | 256 / 256 段 | `k` / `l` |
| 初始远端窗口 | 128 段 | `m`，收到合法报文后使用对端的 wnd 字段 |
| 初始 RTO | 200 ms | `i`；有效 RTT 后在 30..60000 ms 内估计基础 RTO |
| 超时重传 | segment RTO × 1.5 | 原方法 200 → 300 ms 断言通过；每段超时退避与基础 RTO 不是同一变量 |
| 零窗口探测 | 初次等 7000 ms，以后 ×1.5，最多 120000 ms | WASK/WINS；不是 RTCP 心跳 |
| dead_link | 未实现相应终止状态 | 原方法历经 30 次超时仍保留并重发 |

混淆字段：`c=snd_una`，`d=snd_nxt`，`e=rcv_nxt`，`f=ssthresh`，`g=rx_rttval`，`h=rx_srtt`，`i=rx_rto`，`j=rx_minrto`，`k=snd_wnd`，`l=rcv_wnd`，`m=rmt_wnd`，`n=cwnd`，`o=probe flags`，`p=current`，`q=interval`，`r=ts_flush`，`s=nodelay`，`t=updated`，`u=ts_probe`，`v=probe_wait`，`w=incr`，`x=fastresend`，`y=nocwnd`。

队列：`z=snd_queue`，`A=snd_buf`，`B=rcv_queue`，`C=rcv_buf`，`D=待发送 ACK`；`E` 是 4272 字节临时输出缓冲（实际每包按 1400 限制 flush）。

## KCP 报文逐字段

`ks0.c` 57–68 行，原方法和独立 little-endian 向量比对通过。所有多字节整数为小端。

| 偏移 | 长度 | 字段 | 取值/含义 |
| --- | --- | --- | --- |
| 0 | 4 | conv | 3：`03 00 00 00` |
| 4 | 1 | cmd | 81 PUSH / 82 ACK / 83 WASK / 84 WINS |
| 5 | 1 | frg | 当前消息还剩多少段；单段或最后一段为 0 |
| 6 | 2 | wnd | 本地接收窗口剩余可用段数 |
| 8 | 4 | ts | update 当前单调毫秒的低 32 位；ACK 回显接收到的 ts |
| 12 | 4 | sn | KCP 段序号；与内层 RTP 序号独立 |
| 16 | 4 | una | 本端期望接收对端的下一个连续序号（对对端发包累计确认） |
| 20 | 4 | len | 本段 payload 字节数；ACK/探测通常为 0 |
| 24 | len | data | 原始完整 RTP 消息的一部分 |

一个 UDP datagram 可以拼接多个 KCP segment，不可默认每个 datagram 只有一个 header。1400 字节 RTP 消息会拆成两段：第一段 `24+1376=1400`，`frg=1`；第二段 `24+24=48`，`frg=0`。接收端应先按 KCP message 重组，再解析 RTP。不能把每个 KCP segment 直接当作 RTP。

独立 header 向量（conv3 / PUSH / frg1 / wnd256 / ts0x01020304 / sn7 / una5 / len2）：

```text
03 00 00 00 51 01 00 01 04 03 02 01 07 00 00 00 05 00 00 00 02 00 00 00
```

## 全部分支和调度

1. `o31.a` 只在 `d02.F()==KCP` 时创建 `ks0` 并调用 `f/g`，否则媒体数据直接发 UDP。JPEG 走 `o31.b`，没有 KCP 分支。
2. `sd.l` 把每个 RTP message 按 1376 字节拆段，设置 `frg=count-index-1`，加入 `snd_queue`，立即调用 `h(elapsedRealtime())`。判断的是单条消息分段数 `< rcv_wnd`，并没有检查累计待发送队列长度；在正常 RTP 大小下每条只有 1 或 2 段。
3. 主循环在编码输出处理后，如果 KCP 存在且媒体 socket 可用，则最多从 5012 接收 **16 个、缓冲 2048 字节**的 datagram，对每个调用 `d`；socket timeout 为 1 ms，超时后退出本次接收 drain，再调用一次 `h(now)`。依据 `o31` fallback `L7e4..L839`（约 1354–1405 行）。JADX auto 在此处显示了错误的条件结构，不能按 auto 的倒置条件读协议。
4. `h` 只在到达 flush 时间时发包；同一个编码主循环还处理 5013 每秒心跳并在空闲时 `parkNanos(1000000)`。所以 10 ms 是协议 update interval，编码/采集或 socket 调度阻塞会影响实际发包时机。
5. `d` 验证 header 至少 24 字节、conv、已声明 len 不越界、cmd 为 81..84；失败直接返回，没有详细错误码。包尾不足 24 字节不会构成另一个完整 segment。合法报文都更新 rmt_wnd 和 una；ACK 更新 RTT、移除确认段、增加丢失段 fastack；PUSH 排序去重并排队 ACK；WASK 置 WINS 待发送标志；WINS 只刷新窗口/una。
6. `h` 先输出累计 ACK 和窗口探测，再在 `min(snd_wnd,rmt_wnd)` 内把 z 移到 A；首次发送、deadline 超时和 fastack 足够时重发。nodelay1 超时后使用 1.5 倍段 RTO。KCP 发送异常经 `j31.a` 抛出，`ks0.e` 包为 RuntimeException，进入 `o31` 整个发送会话的异常清理。
7. 无 ACK 时发送窗口会耗尽，A 留下未确认段；新画面继续把分段加到 z。没有发现整体队列上限、背压丢旧帧或等待关键帧恢复。离线例：入队 300 个单段，首次发出 128，z172；再入队 1000 后 A128、z1172。**KCP 窗口上限不等于累计内存有上限。**
8. 控制心跳没有收到回复或收到不匹配的 24 字节回复只影响日志，不会停止媒体或改 conv。用户停止/异常会跳出发送循环、尝试 H.264 结束包并关闭 UDP/编码器；KCP 没有独立 FIN/RESET 报文，也没有等待 A/z 确认完毕的 flush/close 握手。
9. 收到反向 PUSH 会存入 B/C 并 ACK；类中没有 recv/peek API 消费 B。当前媒体发送用途主要需要 ACK；若接收端发大量应用 PUSH，B 最终可能填满 256 段。这不能替代一套双向应用消息 API。

## 边界问题

- KCP receive `rcv_nxt` 用 Java long 递增但与解码 unsigned32 sn 作直接相等比较。将 rcv_nxt 置 `0xffffffff`，接收 sn `ffffffff` 后再接收 sn0，原方法停在 `0x100000000`，sn0 留在 C，不能继续推进；原方法离线断言已复现。
- 发送侧 sn 超过 32 位后，单独选择性 ACK 的相等比较也可能漏删；累计 una 的 int 差值比较仍能清除。此为极长会话/边界注入结果，未证明普通短时投屏会触发。
- 原 `ks0.d/h` JADX auto 有重复/错误分支结构；本研究优先以 fallback、原 JVM 执行及独立 C 互通解释，不能直接复制 auto Java 当作可编译参考实现。

## 与官方 6.10.11 路径交叉对照

证据：项目 `tmp/ninebot-static-gap-20261009/jlibrtp.kcp.Ukcp.java`、`jlibrtp.RTPSession.java`、`cn.ninebot.library.screencast.RtpSender.java`，以及此前研究文档第 8.5 节。

| 事项 | Ninebot Pro | 官方静态路径 |
| --- | --- | --- |
| KCP 内核 | APK Java `ks0` | JNI `libkcp.so` / `Ukcp` |
| 初始 conv | 3 固定 | `new Ukcp(0,...)` |
| conv 更新 | 无 | subtype5 控制数据 len≥12 读 offset8，onHeartBeat 时 restartIfNeeded |
| nodelay/interval/fastresend/nocwnd | 1 / 10 / 2 / 1 | Java 调用 2 / 100 / 2 / 1，native 内部具体含义未动态确认 |
| update 调度 | 复用编码循环、即时 flush 调用 | HandlerThread 每 10 ms 调用 update，内部 interval 100 ms 与外部调度不是一回事 |
| MTU/MSS | 1400/1376 | Java setMtu1444；若使用标准24字节header则MSS1420，native本轮未验证 |
| 窗口 | 256/256，初始远端128 | 10240/10240 |
| 累计队列门控 | 未见 | waitSend>20480 标记 busy、<10240 恢复，busy时拒绝新send |
| 心跳失败 | 只统计 | 连续3次timeout setSendEnable(false) |
| 加密 | 该路径明文封装，无额外加密层调用 | KCP本身不加密；官方是否另有上层处理仍按之前证据边界 |

这些差异说明“完整 RTP 进 KCP”这一层级是共同点，但 APK 参数不能替代所有车型的官方会话协议。七寸实车还需确认控制反馈、conv、数据窗口、收到 ACK 与实际显示、停止/重连、丢包及码率恢复。

## 证据位置

原 JADX 目录在 `<external-evidence>`；KCP fallback 在 `.../ninebot-pro-061-kcp-fallback/sources/defpackage/`；o31 fallback 在 `.../ninebot-pro-061-fallback/sources/defpackage/o31.java`。文件级哈希见 `evidence-index.json`。

复现本目录测试：先用 dex2jar2.4 转换固定 APK，然后运行 `pwsh -NoLogo -NoProfile -File run-validation.ps1` 和 `run-interop.ps1`。后者按固定 commit 下载上游 C 源码，仅本机文件交换，无仪表网络访问。
