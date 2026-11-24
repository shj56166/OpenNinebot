# 半彩屏 BLE：GATT 出口、认证边界与成功语义

研究对象：九号官方 Android 6.10.11，`librust_lib.so` SHA256 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`，RustBridge `2.1.157.1263-a20e5d6`。本文属于 2026-11-15 证据集，仅分析官方 BLE 路线；不将其结论泛化为全部九号仪表。最终 APK/DEX/ELF 输入由同级 `raw/` 冻结。所有地址均为 ARM64 ELF 相对虚址。

## 结论与证据等级

1. **已闭合的 RTP 写入分支**使用九号自定义文件服务 `6e400001-0000-0000-006e-696e65626f74`、RTP 写特征 `6e400002-0000-0000-006e-696e65626f74`。这不是 Nordic UART Service 的相同 UUID。
2. `writeRtpData` 接收 Java 已封装的 RTP 包；native 目前闭合的直接 writer 分支将它作为 opaque byte slice 原样传给 Android `Peripheral.write`，未增加认证头、4 字节前缀、Gen2/Gen3 加密或新的 ATT 分片。不同的上层 native `RtpHandler` 分支仍须单独判断。
3. **不能据此省略连接/车辆认证。** Java 业务层区分 GATT/设备连接、SDK 加密握手完成、准备完成；媒体 writer 本身仅检查非空数据及所连设备 MAC。连接、打开投屏的控制命令、车辆对命令/媒体的接收规则是另外几道门槛。
4. `true` / `onSuccess` 均不能视为仪表收到或显示一帧。RTP JNI 等本地 writer Future 的 Ready 结果后返回 bool；RTCP JNI 在任务 spawn 后返回 true。Android Future 最多到 GATT 回调，并且本 APK 的 `onCharacteristicWrite` 忽略 `status`。

## GATT 服务及选择

| 路线 | 服务 | 写入 | 通知/读取 |
|---|---|---|---|
| HMSoft 控制 | `0000ffe0-0000-1000-8000-00805f9b34fb` | 本表未另列写通道 | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Nordic 控制 | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| 九号文件/媒体 | `6e400001-0000-0000-006e-696e65626f74` | RTP `...0002`、RTCP `...0003`、测速 `...0005` | RTCP `...0004`、测速 `...0006` |

来源：`NbBluetoothAttributes.java:20-25,84-102`；NUS 服务常量已由 classes10.dex 的 `VivoCarManagerKt.java:11` 复核。native RTP poll 在 `0x2ecd2c..0x2ecd50` 明确构造九号媒体服务与 RTP 特征。不能仅凭 Gen2/Gen3 加密协议或扫描广播字段推出控制服务与媒体服务选择。

非 SDK 路线：`NbBluetoothDevice.writeRtp/writeRtcp` 直接用已发现的 `fileService` 取 `...0002/...0003`，调用 `getBleConnector().writeCharacteristic(characteristic,data,true)`。classes19.dex 的 `BleConnector.java:442-485` 和 `BleConnector1.java:635-683` 均已追到 `setValue` 和 `BluetoothGatt.writeCharacteristic`：无 Java 逐包加密，第三 bool 为 true 时 `setWriteType(1)`，即 NO_RESPONSE。

旧 connector 自带超长分片：超过它的 `mMtu` 时按该值切割，片间 sleep 10 ms；`BleConnector1` 正确按剩余长度是否达到 `mMtu` 继续循环。老 `BleConnector` 的反编译循环条件为 `remaining>=20`，但每次复制 `mMtu`，所以在 `mMtu>20` 且末段 `20<=remaining<mMtu` 时可能越界；这里记录为该旧类的静态风险，未实机重现。正常上层 RTP 的包长为 `M-7`，不触发超长分支。两类返回 BluetoothGatt 的本地写启动 bool。

## RTP JNI 到 Android 的闭合调用图

```text
NbBluetoothDevice.writeRtp(byte[])
  -> BleSdkClient.writeRtpData(device, same byte[])
  -> RustBridge.writeRtpData(mac, same byte[], callback)
  -> JNI 0x2578a4
     0x5dec38: Java byte[] -> owned native buffer
  -> 0x27e7e8 -> 0x27e518 -> LocalPool task/poll
  -> 0x26bfec (0x26c08c direct BL)
  -> 0x2eccd8
     service ...0001 + characteristic ...0002
     characteristic lookup 0x2ed394
     clone client/config 0x325a60
  -> 0x5cdaf0(slice ptr, slice len, type discriminant=1)
     Box future size 0x40; vtable 0x679010
     RELATIVE-relocated vtable poll = 0x5bfbc8
  -> poll 0x5bfbc8 -> 0x5b2450
     0x5d4544: UUID -> java.util.UUID
     0x5d6390: same slice -> Java byte[]
       0x5de844 -> JNIEnv.NewByteArray (table +0x580)
       0x5df180 -> JNIEnv.SetByteArrayRegion (table +0x680), offset 0
     0x5c1684: Peripheral.write arguments
  -> 0x5d9f60: JNI call wrapper
  -> com.nonpolynomial.btleplug.android.impl.Peripheral.write(UUID,byte[],int)
  -> BluetoothGattCharacteristic.setValue(same byte[])
     setWriteType(1)
  -> BluetoothGatt.writeCharacteristic(characteristic)
```

`0x5b2670..0x5b267c` 把 native discriminant 非零映射为 Android writeType 1，零映射为 2；Android 常量分别为 `WRITE_TYPE_NO_RESPONSE` 和 `WRITE_TYPE_DEFAULT`。因此 native 的 `1` 不应命名成“with response=true”。JNI 类缓存 `0x5c0c70..0x5c0ca8` 明确查找 `write` 及签名 `(Ljava/util/UUID;[BI)Lio/github/gedgygedgy/rust/future/Future;`，methodID 被放入缓存 +0x40，与 `0x5c1684` 读取一致。

静态闭合不等于完整运行 Android/JNI。原指令仿真分段验证及其 hook 边界列于验证产物；真实无线抓包与仪表接收尚未执行。

## 连接、握手和准备状态不是同义词

`BleSdkClient` 的连接监听器 `onProcess(id=0)` 就赋值 `mConnectedDevice` 并设置设备 `ConnectionState.CONNECTED`（`BleSdkClient.java:510-516,646-651`）。`getConnectionState(mac)` 也仅依赖这两个设备字段（1283-1290）。

`BleSdkStatus.CONNECTED` 在连接 `onSuccess` 或 `BleSdkEncryption2/3Processor.onSuccess`、日志“encryption success”时赋值。随后 processor 仍执行 `readVersions()`；`AbstractPreparatoryProcessor.prepareSuccess()` 才 `setPrepared(true)`。`NbBluetoothDevice.isConnected()` 对 SDK 路线要求连接设备 MAC 相同 **且** `isPrepared`；旧路线要求父类连接 **且** `isPrepared`（1347-1357）。

但 `BleSdkClient.writeRtpData/writeRtcpData`（1559-1613）仅要求非空数据、`mConnectedDevice != null`、MAC 相同，不主动调用 `isConnected/isPrepared` 或检查 `BleSdkStatus`。由此只能说明 writer 自身没有完整准备门控，不能推出仪表接受未经握手的媒体。官方正常业务链仍先连接/认证/准备，并通过控制命令打开投屏。Gen3 的账户/车辆密钥与会话认证应沿项目已有 V3 研究及控制分支核对。

## MTU 和分片

这里必须区分 API 的 `getMtu()` 和原始 ATT MTU。设原始 ATT MTU 为 `A`、`NbBluetoothDevice.getMtu()` 返回值为 `M`：`BleSdkClient.java:1297-1304` 明确返回 `nativeMtu>20 ? nativeMtu-3 : nativeMtu`；旧 `BleConnector1.java:213-217` 也把回调 `i-3` 存入 `mMtu`。正常协商值下 `M=A-3`，已经是 attribute value 的容量。

Java 半彩屏 RTP 的上层 payload size 为 `M-27`，加 20 字节头后为 `M-7=A-10`。例如 `A=247` 时 API 返回 `M=244`，payload 217、包长 237，距 attribute 上限 244 留 **7 字节**。把 `M` 直接当原始 ATT MTU会误算成“留 4 字节”。默认低 MTU、未协商及非法小值不能套用该正常值例子。

**目前没有证据把余量解释为 native 再加的长度、CRC、密钥/加密尾部或帧头。** 此次直接 writer 路径将原长度传到 `NewByteArray/SetByteArrayRegion/Peripheral.write`；`Peripheral` 无分包循环。应保留为实现余量，具体原因未知，不能擅自扩大现有包长。

`NbBluetoothDevice.getMtu()` SDK 转 `BleSdkClient.getMtu` 后作上述减 3，旧路线转 connector；老 `BleConnector` 初始化为 20 且该类没看到协商更新，`BleConnector1` 有减 3 更新。Android `Peripheral.setMtu(i)` 调 `BluetoothGatt.requestMtu(i)`，`onMtuChanged` 用回调值完成 Future（203-242），并非请求多少就一定协商多少。

正常协商后 native 值的含义也已静态闭合：

```text
Peripheral.setMtu(i) -> BluetoothGatt.requestMtu(i)
  onMtuChanged(gatt, negotiatedA, status)
  -> Future.wake(Integer.valueOf(negotiatedA))
  -> native 0x5c011c -> 0x5b30e4
     0x5b3298..0x5b32c4: Integer.intValue()I
     0x5b336c..0x5b337c: u16保存，无减3
  -> 0x2fc2d0 (0x2fc3a8: u16返回)
  -> 0x2edc54 (0x2eddc4: strh value,[clientShared+0x308])
  -> getMtu JNI 0x253e70 -> 0x32957c读同一+0x308
     0x253f08: 保留u16，无减3
  -> BleSdkClient.getMtu: value>20 ? value-3 : value
```

setMtu 的 boxed future vtable 为 `0x679050`，经 RELATIVE 重定位的 poll 是 `0x5c011c`；JNI cache 的 setMtu methodID 位于 `+0x50`（`0x5c193c` 读取），与 `0x5c0acc` 查找 `(I)Lio/github/gedgygedgy/rust/future/Future;` 相符。故**在这条正常协商成功链上**，native getMtu 存的是 Android 回调原始 ATT 值 A。缺设备返回 0、初始化值、其他错误分支仍应分别处理，不能以此宣称任何一次调用都已完成协商。

## 队列、回调和成功语义

`Peripheral.write`（471-511）创建 `SimpleFuture`，经 `queueCommand` 串行执行。`commandQueue` 无显式容量上限；当前命令的 Future 完成/异常/超时后 `runNextCommand` 才推进（1069-1103）。它使用旧 `BluetoothGatt.writeCharacteristic` API；返回 false 抛出 `Unable to write characteristic`。

写操作设置 6000 ms 超时。超时通过 `AtomicBoolean.compareAndSet` 只完成一次，`wakeWithThrowable` 并释放队列；迟到回调被忽略（1113-1147）。

`onCharacteristicWrite(gatt,char,status)`（528-550）**未检查 status**；它只检查 UUID 是否符合预期，然后 `wakeCommand(future,null)`。所以即使 native 等到了此 Future，也不能据此宣称 GATT 状态成功，更不能宣称接收端 RTP 完整、H.264 解码或画面显示。

该处已做第二工具交叉：原 classes11.dex 经 dex2jar 2.4 转换后 `javap -c -p Peripheral$5`，`onCharacteristicWrite(...,int)` 的字节码未加载 `status` 所在 local 3；内部 lambda 也只有 UUID 相等判断和 `wakeCommand`。保存为 `Peripheral-callback5.javap.txt`。此项不依赖 JADX 对条件分支的恢复。

`BleSdkClient` 给 RTP/RTCP 的 `Callback.onSuccess/onError` 都是空实现。其 RTCP 首次写入前懒订阅通知：忽略 `subscribeRtcpNotification` 的 bool 返回后仍把 `isRtcpSubscribed` 置 true（1564-1574）。静态分析不保证订阅已生效。

RTP 的 JNI bool 是同步等待的本地结果：`0x26bfec` 在 `0x26c094` 检测 `-2` 后回到 poll，Ready 后 `0x26c11c..0x26c124` 将 `-1` 映射 bool，再由 `0x27e7e8` 和 JNI 返回。这里由 KCP 交叉追踪；不能写成“只 enqueue”。但等到 Future 也不增强上述 status/渲染保证。

## Crypto 与 RTCP 的证据边界

`crypto-summary.json` 已扫描 ELF `.text` 中已知 FDE 范围的直接 BL/B，映射 `crypto_*` 导入 PLT：reset `0x647860`、start `0x6478a0`、setAuthParam `0x6478b0`、encrypt `0x6478c0`、decrypt `0x6478d0`。encrypt 的直接封装调用者是 `0x38878c/0x388adc`；decrypt 是 `0x3889ac`。它们未出现于上述直接 RTP writer 调用图。间接函数调用另由具体调用图解读，不能用此扫描单独排除全部间接加密。

这不是“整个 SDK 无加密”的证明：控制命令和连接流程使用 crypto 后端；同时也存在未完成归属的高层 `RtpHandler/rtp_send_queue`。本文只对已经闭合的 `writeRtpData` 直接通道作结论。

RTCP Java 特征已明确为 `...0003`，通知为 `...0004`。native JNI `0x257548` 使用另一调度入口 `0x25c734`，在 `0x2576b8` spawn 后 `0x2576c8` 置 bool=true；关闭/取消标志分支可返回 false。这个 bool 是任务受理，不能与 RTP 的同步本地 Future 结果混用。

RTCP native 已闭合到专用 UUID 与通用发送 future：

```text
0x257548 JNI -> 0x25c734 -> 0x25b52c -> 0x268a78
  runtime分支 A: 0x267124 -> 0x262228 -> 0x27c1c4 -> 0x336e20
  runtime分支 B: 0x270884 -> 0x25fde0 -> 0x27c130 -> 0x336cb4
  task vtable 0x6515b0 / 0x651560
  -> 0x34dc6c / 0x34d908 -> 0x2af088 / 0x2aefe0
  -> 0x281510 -> 0x367b2c -> 0x318a18 -> 0x31e420
  -> 明确 ...0001 服务 + ...0003 特征，发送类型字节=1
  -> 0x2ee0f4 通用发送 future / worker / 发送完成通道
```

`0x2ee0f4` 不是 RTP 简单透传函数，含特征查找、客户端配置克隆、缓冲复制、worker 启动与完成信号等待。已保留完整反汇编；本轮尚未闭合该 worker 的最终 consumer、任意长度的 ATT 拆分及全部错误映射。**所以不把 RTP 的“native 不再分片/加密”结论无条件套给 RTCP。** 控制/RTCP/测试通用写入与 RTP 特化直接写入必须分开实现和验证。

## 复现和交叉验证

- `native_trace.py` 从冻结 ELF 重建 FDE 函数范围、应用 `R_AARCH64_RELATIVE` 后解读 vtable、导出选定函数反汇编；字符串注释使用线性寄存器追踪，只是辅助线索，关键参数以具体指令为准。
- `fn-*.asm.txt` 为具体静态证据；`native-inventory.json` 保存输入摘要、原符号与字符串；`btleplug-class-index.json` 定位 classes11.dex 的 Peripheral 与 classes12.dex 的 Future。
- `root-forward-rerun.json` 是对 root 已有 14 场景的独立重跑，不能额外计数成新的 14 场景。详细审查见 `cross-review-root.md`。
- `emulate_gatt_boundary.py` / `gatt-boundary-validation.json`：24 个原 ARM64 分段检查，覆盖 boxed future 参数保持、原 NewByteArray/SetByteArrayRegion 包装与原 Java 三实参构造。实际 JVM 和无线栈没有运行，generic JNI 方法调用只验证到边界。
- `collect_evidence.py` / `security-source-index.json` / `evidence-excerpts.txt` 保存 6 份关键 Java 源摘要、4 个 DEX 在冻结 APK 中的 SHA256 及带行号的定向摘录。本文引用完整反编译源文件的行号，摘录只作导航证据。
- 本文不声称完成车辆实测、BLE 抓包、iOS 实现或完整安全认证算法逆向。
