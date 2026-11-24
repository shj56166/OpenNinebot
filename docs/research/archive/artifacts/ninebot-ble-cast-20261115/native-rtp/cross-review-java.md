# Java 媒体链独立交叉审查

审查者：native/RTP 分支代理；日期：2026-11-15。对象为 `../java-media/notes.md`、冻结 DEX/源快照、两套原方法验证，以及主文档第 7/8 节。结果：**本次审查范围内通过，无阻塞矛盾**。

## 独立复跑和证据核验

```powershell
pwsh -NoLogo -NoProfile -File artifacts/ninebot-ble-cast-20261115/java-media/run-validation.ps1
pwsh -NoLogo -NoProfile -File artifacts/ninebot-ble-cast-20261115/java-media/run-session-validation.ps1
python artifacts/ninebot-ble-cast-20261115/native-rtp/cross_review_java.py
```

- 原队列/分片方法：570 断言，9 组 queue / 60 组 split，通过。
- 原完整 BLE 会话/编码类：15 断言，10 组场景，通过。
- 独立核对冻结 APK、5 个 DEX、源快照、转换 JAR/ASM 的哈希，并以 Python `struct.unpack` 解读原类输出向量；共 49 项完整性/向量检查，通过。细项见 `cross-java-validation.json`。
- 复跑复用同一组场景，不增加协议测试覆盖计数；未新增或执行 Android 编码器、GATT、实车测试。

## 重点结论逐项核对

| 审查项 | 交叉结果与证据边界 |
|---|---|
| 当前 MPEG2 入口 | 原 DEX `Mpeg2CaptureController$1.createSurfaceEncoder` 的 `new-instance` 明确是 `FFmpegImageReaderEncoder`；`createViewEncoder` 明确是 `FFmpegViewEncoder`。结合 `AbstractCaptureController` 的 MediaProjection 分支和两个包装器，实际执行器为 `FFmpegBitmapEncoder`，不能沿用旧 `Mpeg2Encoder` 默认值 |
| 当前编码参数 | 冻结 FFmpegBitmapEncoder 构造和 init：2 Mbps fallback、MPEG2 codec=2、pixel format=0、GOP5、B0、low delay=true、最后设置 bitrate_mode=cbr，均与笔记吻合；仅是请求值/缺省值，没有执行 FFmpeg 来证明实际采纳或码流兼容 |
| 媒体负载边界 | `NbFFmpegFrameRecorder.writePacket` 对视频 `AVPacket.data/size` 复制后直接 listener 回调；当前 MPEG2 listener 原样传 bytes。`.mpg` 文件名不能推出交给 RTP 的是 PS/TS/Matroska；未测真实编码输出 |
| 第一次帧类型映射 | `VideoFrameHelper.getFrameType` 读 `packet.flags & 1`→1，次选 `flags & 4`→3，否则2；不是读取 AVPictureType。`ScreenCastRequest` 把 callback1 映射为 sender2，其他映射 sender3。该上半段依据原 DEX/静态调用链，不算入会话动态测试覆盖 |
| 第二次帧类型映射 | 原 `BluetoothRtpSender.send` 在 60 组向量中确认 sender2→extension1，sender1/3→extension2；主流程的 sender 类型为2/3，兜底类型1不证明编码器实际产生 header frame |
| PT 与私有扩展 | 原 classes12 会话向量独立解析得到 RTPv2、X1、CC0、M1、**PT0**、seq0x1234、timestamp0x01020304、SSRC0x11223344、profile0、words1、extension=`00010000`。总头长20=12+4+4，pictureType 位于扩展数据第二字节 |
| 原始媒体不变 | 同一向量 payload 恰为输入 `000001657f`；这个样例只是任意5字节，不把它当作可解码的 MPEG2 示例。native 透传链与此边界相容，不加额外头来“补齐 MTU 余量” |
| 分片 | 原 send/split 方法按 `maxLength` 对原字节切片，最后一片 marker=true，同帧 currentTimeMillis 时间戳。4种 MTU 的用例均通过；`MTU-27` 是上层规则，实际链路 MTU 仍未实测 |
| 队列 | 原队列物理容量30；full3/drop2不重建物理队列。9组局部行为通过，包括丢帧顺序、clearBuffer 不清 keyCount、take 减 key/total。没有并发压力、真实消费者解码恢复的动态验证 |
| sequence 边界 | 原 DEX `BleRTPSession.getNextSeqNum` 的阈值为65536且比较 `if-le`，允许65536，之后才归零；原类输出低16位 65535,0,0,1 已复跑确认。不是在重写模型里推导出的结果 |
| 提交与重试 | 原类确认20次是总尝试；一片全部失败仍继续下一片，pkt/octet 代码各自++。2片全失败实际调用40次，两个计数都2；不代表车端接收 |
| 测试实现性质 | ASM slicer 保留目标方法体，去接口/无关方法，并把 Bluetooth sender 类父类改成 Object 后用 Unsafe 分配，底层 session 为捕获桩；另一套原 classes12 会话未裁剪，IBleSender 为离线 sink。笔记准确列出了两者的不同边界 |

## 与 native 及主文档第 7/8 节交叉

1. 主文档第 7 节的 `0x2578a4 → 0x27e7e8 → 0x27e518 → 0x26bfec → 0x2eccd8 → 0x5cdaf0` 与本代理恢复的原指令一致。`0x369e74` 被改为 characteristic 缓存更新，已消除旧 stream writer 的误标。
2. RTP JNI 在 Pending 后等唤醒再 poll，Ready 后以 `cset` 返回本地结果。主文档已经写成同步等待 writer Future，保留“本地完成不证明车端显示”边界；不会把通用 Peripheral 无界队列直接说成单线程 RTP 的必然无界积压。
3. 第 8 节清楚分开 `RtpHandler` 默认 PT32/12+8 和当前原 Java 包 PT0/12+8。原构造片段、NBBle 字段归属及 7 组初始化仿真只证明对象状态；没有被写成当前数据包的消费者。
4. 车辆认证、RTCP 独立 spawn 返回、Android callback status、BLE 链路加密由相应分支单独判断。本次 Java 和 RTP 证据没有越界声称“投屏不需要车辆认证”。

本次复核没有找到需要修改 Java 媒体结论的阻塞项。实际编码输出、仪表显示、目标车型配置、无线权限/MTU、并发取消与恢复仍按主笔记列为待验证，不能用这585条离线断言替代。
