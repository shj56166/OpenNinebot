# 官方 BLE 投屏：Rust RTP 原生链复核（2026-11-15）

本记录只分析官方 Android 6.10.11 的 ARM64 `librust_lib.so`。它与独立 Wi-Fi `ninebot_pro` APK 的 KCP/H.264 协议不是同一个传输分支。

## 可确定的结论

1. 旧研究止于 `LocalPool` 的断点已补齐。`writeRtpData` 的具体 future poll 是 `0x2eccd8`，其成功路径查找 RTP 专用特征，将输入指针和长度直接交给 boxed BLE writer；此函数内没有 RTP 解析、分片、序号插入或媒体加密。
2. `RtpHandler` 构造及其归属也已恢复。它存在于 `NBBle` 的共享内部状态，默认 `payload_type=32`、标准头 12 字节、专用头 8 字节。这只能证明默认对象状态，不能当作当前 Java 投屏分支实际发包的格式。
3. 没有证明 `RtpHandler` 的封包方法参与这条 `writeRtpData` 调用。其后续 packet consumer 仍未定位。当前已闭合的发送链不经过该对象；不能用一个格式器名字推断 JNI 会再次封包。

## 冻结输入和复现

- 冻结文件：`../raw/native/librust_lib.so`，6,804,560 字节；来源 `tmp/native/lib/arm64-v8a/librust_lib.so`，两者哈希一致。脚本默认读取冻结副本。
- SHA-256：`2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`。
- 官方 APK SHA-256：`02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`。
- Python `capstone` 模块常量 `__version__=5.0.7`、发行包元数据 `importlib.metadata.version=5.0.9`（同一 Python 3.14 runtime 两处版本值不同）、`pyelftools`；函数边界来自 ELF `.eh_frame` 的 17,405 个 FDE。
- `probe.py manifest` 可复算库哈希；`probe.py dump 0x2578a4 0x26bfec 0x2eccd8 0x328434` 可重建重点反汇编。
- `refs.py` 是 ADRP/ADD 候选交叉引用筛选器，允许 160 字节窗口，可能跨寄存器改写产生候选误报；本报告引用的关系均人工核对最终反汇编。`field_refs.py`、`layout_candidates.py` 也只负责筛选，不用于证明某类型没有消费者。

## JNI → LocalPool → 具体 poll

| 地址 | 可核对的行为 | 证据等级 |
|---|---|---|
| `0x2578a4` | 导出 `Java_com_ninebot_blelibrary_RustBridge_writeRtpData`；保存 Java byte array（`x3`）和 callback（`x4`） | 静态确定 |
| `0x257940 → 0x5dec38` | JNI `GetArrayLength`（表偏移 `0x558`）及 `GetByteArrayRegion`（`0x720`）将 Java 数组复制成 Rust owned buffer | 静态确定 |
| `0x257968 → 0x637ad4` | 克隆 owned vector 到 `sp+0x1a8`：该 helper 在 `0x637aec` 取原 ptr/len，按同一 len 分配，在 `0x637b2c` 原长 memcpy，`0x637b34` 保存同一 len；随后把新 vector 地址放入 future 捕获区 `sp+0x208` | 静态确定 |
| `0x2579c8 → 0x27e7e8` | 把 future 状态复制 `0x1d8` 字节至执行器栈上，调用 `0x27e518` | 静态确定 |
| `0x27e540 → 0x26bfec` | `LocalPool` wrapper；其前置 TLS 检查防止嵌套执行器 | 静态确定 |
| `0x26c060..0x26c070` | 将捕获的 client、vector.ptr、vector.len 写入具体 future 的起始三个 qword | 静态确定 |
| `0x26c08c → 0x2eccd8` | **直接 BL** 具体 poll；此前的间接调用 `0x26c010` 只负责执行器支持对象，不能据此把整个 concrete poll 当成未知 | 静态确定 |

`0x26bfec` 在 Pending 时把自身 discriminant 写成 3，等待唤醒后重试。其 `0x26c144/150` 分支是已经完成/非法状态的 panic，不是媒体协议字段。

**RTP JNI 返回不是仅入队成功。**`0x26c094` 检查 poll 的 Pending 编码 `-2` 并进入等待/重试；Ready 后 `0x26c11c` 将结果与 `-1`（本地成功）比较，`0x26c124` 通过 `cset w0,eq` 生成 bool，再经 `0x27e7e8`、`0x2579cc/0x257a18` 返回 Java。它会同步等待这一 writer Future 的结果，因而 Java 顺序调用媒体写入时存在本地完成背压。该结果与 Android GATT callback 的 status、仪表接收/显示保证仍须分别判断。RTCP JNI 由另一条 spawn 分支提交，不能套用这里的同步返回语义。

## `0x2eccd8` 的数据与 GATT 参数

初态 `x0=self`、`x1=context`、`x8=result`。`self+0` 是 client 引用，`self+8` 是输入指针，`self+0x10` 是输入长度，`self+0x30` 是 future 状态。

| 地址 | 行为 |
|---|---|
| `0x2ecd1c..0x2ecd28` | 将原 client、ptr、len 保存到 `self+0x18/+0x20/+0x28` |
| `0x2ecd2c..0x2ecd50` | 服务字符串 `6e400001-0000-0000-006e-696e65626f74`，特征字符串 `6e400002-0000-0000-006e-696e65626f74`，长度均 36；并设置 characteristic cache 引用 |
| `0x2ecd60 → 0x2ed394` | 异步获取/缓存 characteristic；Pending 返回 `-2`，该 future 状态为 3 |
| `0x2ecdb0..0x2ecdbc` | `-1` 是 lookup 成功分支使用的编码；其他结果进入错误传播 |
| `0x2ecdc8 → 0x325a60` | 克隆 BLE 平台上下文。该方法只复制设备/JNI/共享引用，不接收媒体 ptr/len |
| `0x2ecdd0` | `ldp x2,x3,[x19,#0x20]`，恢复原媒体 ptr/len |
| `0x2ecdd8` | `mov w4,#1`，writer 的 write-type discriminant |
| `0x2ecddc → 0x5cdaf0` | `x0` 为上下文、`x1` 为 characteristic、`x2/x3` 为原媒体 ptr/len、`w4=1`；创建 boxed writer |
| `0x2ecde4..0x2ecdf0` | 从 writer vtable `+0x18` 取 poll 函数并调用；writer Pending 对应本函数状态 4 |

本函数没有读取输入包头，也没有写入媒体缓冲区。它不依据 MTU 分片；实际每个写入 byte array 的长度由上层决定。

`0x2ed394 → 0x369e74` 的用途是把 characteristic 查找结果写入缓存并唤醒等待者。`0x369e74` 不接收 RTP payload 的 ptr/len，不能继续沿用旧候选报告将它称为 RTP 流写入函数。

### 与另外两份独立证据交叉

- root 执行原始 ARM64 `0x2eccd8`，只 hook characteristic lookup、上下文克隆及最终截获点。13 种长度 `0/1/19/20/21/64/128/220/240/244/512/1024/4096` 加一组 Pending→Resume，**14 组通过**；同一输入指针、同一长度、完整原字节、characteristic 及 `w4=1` 均保留，输入内存无写入。见 `../control-config/emulate_rtp_forward.py`、`../control-config/native-forward-validation.json`。
- BLE/security 代理独立恢复 `.rela.dyn` 的 vtable：`0x679010 = {drop:0x5b8648,size:0x40,align:8,poll:0x5bfbc8}`；后续 `0x5bfbc8 → 0x5b2450 → 0x5d4544/0x5d6390 → 0x5c1684 → Peripheral.write`。`w4=1` 映射 Android `WRITE_TYPE_NO_RESPONSE=1`。后半段证据和仿真以 `../ble-security/` 的最终记录为准。
- 上述 0 长度向量是 native 函数边界测试；正常 Java `BleSdkClient.writeRtpData` 会先拒绝空数据。没有将该向量描述为可从应用正常触发。

这些是离线 native 控制流/参数验证，没有执行完整 JNI、Android GATT、无线空口或仪表固件。

## `RtpHandler` 类型、构造、归属

### 类型链（补齐旧搜索遗漏的 thunk）

```text
NBBle Debug 0x390d20
  Arc allocation+0x98  / NBBleInner data+0x88: rtp_handler
  Debug vtable 0x658650 → slot+0x18=0x390c48
  0x390c48: load Arc ptr, add 0x10 → 0x396424 (RwLock Debug)
  RwLock data+0x10; Debug vtable 0x6595d8
  vtable slot+0x18=0x38c150
  0x38c150: load &RtpHandler → 0x385e00 (RtpHandler Debug)
```

只搜索指向 `0x385e00` 的 relocation 会漏掉 `0x38c150` thunk。`refs-6595d8-6595e0-6595e8-6595f0-679010.json` 与有关 `fn-*.asm.txt` 保存了原始证据。

### 构造已证实，消费者尚未证实

`0x328434` 是包含 `RtpHandler` 的 `NBBle` 构造路径。`0x328b68` 分配 0x48 字节，前 16 字节为 Arc counts，后接 16 字节 RwLock 元数据，`RtpHandler` data 从分配地址 `+0x20` 开始。`0x328b70..0x328ba4` 写入：

| `RtpHandler` 相对偏移 | 字段 | 构造值 |
|---|---|---|
| `+0x00` | `payload_size` | 构造函数 `x3 & 0xffff` |
| `+0x08` | `rtp_header_size` | 12 |
| `+0x10` | `rtp_specific_header_size` | 8 |
| `+0x18` | `index`（u32） | 0 |
| `+0x1c` | `ssrc`（u32） | 0 |
| `+0x20` | `payload_type`（u8） | 32 |
| `+0x21` | `rtp_standard`（u8/bool） | 1 |

其中 `stp x8,xzr,[x0,#0x30]` 将 `index` 和 `ssrc` 一并归零；`strh w8,[x0,#0x40]` 写 little-endian `0x0120`，所以两个 u8 分别为 32、1。`0x328d04` 再把该对象指针 `x25` 写到 NBBleInner data `+0x88`，与上面的 Debug 类型链一致。

构造函数唯一直接 caller 是 `0x3046e4` 的 `0x305e78`。该 caller 在 `0x305df4..0x305e10` 依据 `sp+0x428` 的 bool 在 **20 / 512** 之间选择 `w21`，然后 `0x305e70` 传给 `w3`。尚未追出该 bool 的产品语义，不能把 20/512 直接宣称为协商成功的 ATT MTU。

root 另以 Unicorn 执行原始初始化基本块 `0x328b70..0x328ba8`，参数 `0/244/247/512/65535/65536/65537` 共 **7 组通过**，独立验证低 16 位截取、12/8 字节头、零 index/SSRC、PT32、standard1。结果为同一 `native-forward-validation.json` 的 `handler_initializer`。这段仿真只验证初始化基本块，没有执行完整构造函数或 handler 的媒体消费者。

扫描单字段、配对成员及 bool 操作是查找候选的方法，不构成全程序不可达性证明。当前未恢复：从这个 handler 生成一个线包的原函数、对 index/SSRC 的运行时更新者、rtp_send_queue 的媒体消费者。也未证明它永远不用。**默认对象状态与 APK 当前 Java 发包的 PT/扩展字段必须分别记载。**

## 对接和认证结论的边界

- 媒体发送的本地链路已经没有“可能在未知 native poll 中再加一层 RTP/加密”的旧缺口；此结论限已恢复的 `writeRtpData` 路径。
- 原生函数透传不等于车辆无需认证。查找到设备、建立连接、订阅 RTCP、完成 BLE 握手、打开仪表投屏模式等控制前置条件，需要控制和认证分支的独立证据。
- RTP JNI 同步等待本地 writer Future；不能把这个本地结果或 Android 异步完成当作车辆已接受、已解码或已显示，也不能据通用 Peripheral 队列无界就断言逐次同步 RTP 提交会无界积压。
- 独立替换 `.so` 的可行性可参考这一明确出口，但跨车型成功仍需以合法设备抓包和仪表回读补齐。

## 本轮对旧结论的修订

| 旧记录 | 本轮状态 |
|---|---|
| concrete poll 未定位 | 已定位：`0x26bfec` 直接调用 `0x2eccd8` |
| `0x2eccd8` 只是候选 | 已闭合 JNI → executor → concrete poll，并有原函数离线仿真 |
| `0x369e74` 可能是 stream write | 已更正：characteristic result cache 更新/唤醒 |
| `RtpHandler` 只有字段名 | 已补完整 Debug thunk/type chain、构造地址、对象归属、默认值 |
| native 是否改 RTP 未知 | 具体发送路径已证 ptr/len/bytes 透传；handler 的其他消费路径仍未知 |
