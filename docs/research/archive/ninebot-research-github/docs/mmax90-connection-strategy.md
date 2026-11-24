# Ninebot MMAX90 Gen2 BLE：已通过测试与协议原理

本文只记录 MMAX90 已通过的连接/读取测试和对应协议原理，不按研究阶段或日期拆分。协议细节以已完成的 Gen2 会话验证和对应加密协议分析为主。

## 已通过测试

- MMAX90 Nordic UART Service（NUS）直连：发现服务与读写特征，启用 Notify 后收发协议帧。
- 使用本人车辆已有的 16 字节 password 完成 PRE_COMM、取得本次会话的动态 `auth`、完成 AUTH，并进入只读遥测。
- AUTH 成功目标为 `0x09`；PRE_COMM 目标为 `0x09`。不能照搬通用参考客户端的 `0x04` 握手目标。
- 单次连接稳定运行约 13 分钟，持续收到遥测响应，未观察到会话完整性错误。
- 在已通过连接中读取到 DIS 只读字段，包括速度 `0x26`、功率 `0xBD`、电量 `0xB5`、里程/续航 `0x25`、`0xB7`、`0xB9`、运行时间 `0xBA` 与 NOS 候选 `0x5E`。

以上结果证明既有凭据的 MMAX90 Gen2 只读链路可工作；不表示首次配对、车辆控制、所有字段缩放或其他固件已经验证。

## 协议原理

### GATT 与帧传输

| 项目 | UUID |
| --- | --- |
| NUS Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Write | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| Notify | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

协议帧使用 Gen2 同步字节 `5A A5`。BLE ATT 会按 MTU 分片；应先按帧长度重组完整数据，再解密和校验。不能假设一次通知就是一帧。

### Gen2 加密模型

```text
明文请求：5A A5 LEN 3E TARGET CMD INDEX DATA...
加密帧：  5A A5 LEN | encrypted body | encrypted CBC-MAC (4) | counter (BE u16)

FW_DATA = 97CFB802844143DE56002B3B34780A5D
K0 = SHA1(pad16(deviceName) || FW_DATA)[0:16]
sessionKey = SHA1(pad16(password) || pad16(auth))[0:16]
```

PRE_COMM 使用设备名称和公开 `FW_DATA` 常量对应的引导加密上下文。成功返回包含 16 字节动态 `auth` 与车辆 SN。`auth` 每个会话变化，不是持久 password。

AUTH 使用车辆已有的 16 字节 password 和当次 `auth` 派生会话密钥。认证后的帧体使用 AES-CTR，并带 4 字节 CBC-MAC。线上的帧尾计数器是大端 u16；构造 13 字节 nonce 时，参考实现将计数器编码为大端 u32，再拼接 `auth[0:8]` 和末尾 `0x00`。CBC-MAC 对完整明文帧计算，载荷使用 CTR 加密，tag 也以 CTR 的首块掩码处理。收到的帧必须先重组、解密并校验 MAC，之后才解析命令和遥测值。

### 已通过的连接顺序

```text
连接 NUS → 发现服务/特征 → 启用 Notify
  → PRE_COMM(target=0x09)
  → 取得 SN 与本次 auth
  → 使用同车已有 password 派生会话密钥
  → AUTH(target=0x09)
  → 校验 AUTH 成功响应
  → 读取 DIS 只读遥测
```

每次新连接都重新执行 PRE_COMM 和 AUTH。动态 `auth` 与持久 password 用途不同，不能跨会话复用。此处描述的是已通过的既有凭据路径。

### 已读取字段

| DIS 寄存器 | 字段 | 测试记录中的解码 |
| --- | --- | --- |
| `0x26` | 速度 | `uint16 LE × 0.1 km/h` |
| `0xBD` | 功率 | `int16 LE W` |
| `0xB5` | 电量 | 首字节作为百分比 |
| `0x25` | 剩余里程 | `uint16 LE × 0.1 km` |
| `0xB7` | 总里程 | `uint32 LE × 0.001 km` |
| `0xB9` | 单次里程 | 实验解码为 `uint16 LE × 0.1 km` |
| `0xBA` | 运行时间 | `uint16 LE 秒` |
| `0x5E` | NOS | 读取到原始 u16 值；单位/缩放未确认 |

测试记录中单字段约每 4.8–5 秒更新。该数值是当时连接条件下的实测刷新结果，不是车辆协议规定的刷新率。`0xB9` 单位及 `0xB5` 跨固件语义仍需谨慎解释。

本文范围为已通过的 Gen2 只读连接。不要把未验证的首次 SET_PWD、控制命令、NOS 缩放或 Gen3/V3Auth 当作本测试结果。

## 协议参考

- [Gen2 帧与加密协议说明](ble/ninebot-mmax90-gen2-protocol-analysis.md)
- [公开 BLE 客户端实现分析](segway-ninebot-ble-app-implementation.md)
- [Gen3/V3Auth 深度研究](research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md)
