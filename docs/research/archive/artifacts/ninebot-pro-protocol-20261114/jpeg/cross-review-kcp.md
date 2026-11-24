# JPEG 研究代理对 KCP 分支的独立交叉复核

复核时间：2026-11-14。审阅对象为 `../kcp/notes.md`、`validation.json`、`interop-validation.json`、`KcpHarness.java`、`KcpInterop.java`、`kcp-upstream-peer.c`、两个运行脚本及 APK 的 `ks0/sd/o31` 相关源/指令。

## 独立重复执行

没有修改 KCP 代理的文件。另建本目录 `cross-kcp-jvm`，从 dex2jar jar 再次直接提取原 `ks0/js0/is0.class`，复制其测试源并编译自己的 callback stub，独立运行24项全部通过。三个原类 SHA-256 已记录在 [cross-kcp-validation.json](cross-kcp-validation.json)，防止把手写模拟核误当待测 APK。

另取固定上游 commit `7346e366f1928dd727ecbb53253454fdaecaebb7` 的已有 `ikcp.c/h`，校对源哈希后在自己的目录重新 gcc 编译对端。通过文件交换执行双向 1400 字节 PUSH、两片重组和 ACK：上游7项、APK4项、上游ACK2项都通过。独立结果见 [cross-kcp-interop-validation.json](cross-kcp-interop-validation.json)。这些过程没有车辆、UDP socket或网络丢包环境。

## 复核结论

1. **实际运行原内核**：`run-validation.ps1` 明确仅从 jar 提取三个待测类，`j31.java` 是输出回调桩。测试调用构造器、header encode、input和update/flush；使用 `-Xverify:none` 是 DEX→JVM 缺栈帧的已披露限制。不是将反编译 Java 重写成自己的 KCP 再测试。
2. **conv与窗口**：`ks0.a=3 final`、本地send/receive256、初始remote128、nodelay1/interval10/fastresend2/nocwnd1有原类执行和静态字段的双重证据。收到5013控制反馈不会改conv，不能把该路径等同于官方动态conv。
3. **无ACK队列**：发送窗口只约束进入snd_buf的段数，不能限制snd_queue累计大小。Harness的enqueue镜像调用方入队，未执行原 `sd.l`；它本身绕过的“单条message分片数<rcv_wnd”对实际1..2片RTP不会构成背压。因此24项中的队列积累结论与静态调用方相符，但未运行Android编码主循环，不能作为实际峰值内存或耗尽时间预测。
4. **重传**：200ms超时→段RTO300、两次高SN ACK触发快重传、零窗探测以及30次超时仍在发送，均有原方法注入测试。notes已把“未见dead_link终止分支”与极长会话风险写成静态结论，未声称全部网络状况已经实测。
5. **互通范围**：正常路径的双向PUSH/ACK和片段重组确有独立上游C实现参与。上游互通没有注入丢包、乱序、长期断连或SN回绕；这些边界只在原内核的独立合成输入中验证。也未测试完整 H.264→RTP→KCP→UDP→真实仪表。notes 对此限制描述恰当。
6. **控制与媒体分离**：5012 ACK输入喂给KCP，5013严格20字节心跳识别只计数/日志；与JPEG同样存在16/24字节控制包被拒的问题。控制回应缺失既不修改窗口也不触发stop；窗口更新来自合法KCP报文header的wnd。该边界在notes中清楚。
7. **原始RTP包保留**：KCP中1400字节合成消息拆成1376+24媒体段，对端重组还原原字节。它验证KCP message边界，不代表这些合成字节已经是可解码H.264或真实RTP；完整封包测试由H.264分支另行提供。

## 发现与处理

发现1处方向术语需修正：KCP字段表的UNA应为“**本端期望接收对端的下一个连续SN，作为对对端发送序列的累计确认**”，编码来源为本端 `rcv_nxt`；不是“对端尚未接收到的第一个连续序号”。已直接反馈KCP代理，未修改对方文件。

除该措辞外，未发现需要否定KCP研究结论的证据冲突。最终总文档应保留上述原类执行、上游正常互通、Android调度和仪表实测四个不同证据层级。
