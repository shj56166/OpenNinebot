# segway-ninebot-ble-app 开源实现文档

> 九号研究公开整理版（2026-10-30）：已去除自有 App 内部接入说明和本机路径；保留原研究日期、协议结论与证据限制。

本文记录开源项目 `segway-ninebot-ble-app` 的实现结构和协议行为，仅描述参考项目的机制。参考固定提交为 `d26b181c1d7eda8e11248af53a6a622334746d9a`。

> 2026-10-09 续接：[Gen3 与 2025 后车型研究](research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md) 已用官方 6.10.11 静态代码和 HT2 运行时配置确认广播认证版本选择、V3 密钥流程及 AA55 扩展命令。下文开源 `Gen3/5AB5` 命名仍应按该项目自身实现理解，不代表完整官方 BLE V3Auth。

## 1. 项目定位

上游项目：[segway-ninebot-ble-app](https://codeberg.org/NootNooot/segway-ninebot-ble-app)

该项目是 Flutter 客户端，依赖配套协议库实现 Ninebot / Segway BLE 连接、认证、实时仪表、骑行记录、设置页和部分车辆控制。

项目 README 描述的能力包括：

- Live telemetry：速度、电量、功率、剩余里程
- Vehicle control：开关电源、开座桶
- Pairing / authentication
- Ride sessions：记录骑行数据并导出 CSV
- Settings UI：限速、警告音、骑行模式等

## 2. 关键文件

| 文件 | 职责 |
| --- | --- |
| `lib/providers/connection_provider.dart` | BLE 连接、握手、认证、统一收发队列、重连、session reuse |
| `lib/providers/vehicle_provider.dart` | 实时车辆数据模型、分层轮询、寄存器读取、状态缓存 |
| `lib/core/protocol/nb_protocol.dart` | 协议常量、target / register 定义、frame builder、frame parser |
| `lib/core/crypto/nb_crypto.dart` | Ninebot AES 加解密、counter、SN 模式 |
| `lib/core/ble/handshake.dart` | PRE_COMM / SET_PWD / AUTH 握手流程 |
| `lib/core/ble/ble_transport.dart` | BLE characteristic I/O、分片、接收完整帧、服务发现 |
| `lib/data/device_registry.dart` | 根据 BLE 名称匹配设备配置 |
| `lib/data/device_config.dart` | 解析 device config JSON，建立 command / module 映射 |
| `assets/device_configs/registry.json` | 设备注册表 |
| `assets/device_configs/81.json` | Segway eScooter E 配置 |
| `assets/device_configs/820.json` | Segway eScooter N 配置 |
| `assets/device_configs/105.json` | Segway eMoped C 配置 |
| `assets/device_configs/110.json` / `111.json` | Segway eMoped B 配置 |
| `assets/device_configs/14102.json` | SEGWAY E150S / E250S 配置 |

## 3. 支持设备配置

`assets/device_configs/registry.json` 中包含多类车型配置，其中与电动车 / 电摩最接近的是：

| serverId | 名称 | model | hardwareId | commandCount |
| ---: | --- | --- | --- | ---: |
| 81 | Segway eScooter E | motor | 66 | 512 |
| 105 | Segway eMoped C | motor | 67 | 511 |
| 110 | Segway eMoped B | motor | 73 | 511 |
| 111 | Segway eMoped B | motor | 86 | 511 |
| 820 | Segway eScooter N | motor | 89 | 512 |
| 14102 | SEGWAY E150S / SEGWAY E250S | motor | 4102 | 512 |

本地搜索没有发现 MMAX90 / MMAX / MAX90 专用配置项。MMAX90 只能参考 motor 类设备配置和实际 HCI / 日志验证。

## 4. GATT 与服务发现

`lib/core/ble/ble_transport.dart` 定义了两套服务 UUID：

| 名称 | UUID |
| --- | --- |
| `nbService` | `6e400001-0000-0000-006e-696e65626f74` |
| `nordicService` | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |

其中 `nordicService` 对应当前 MMAX90 实测通道。

`discoverNinebotCharacteristics(...)` 会扫描服务和特征，找到可写 characteristic 和 notify characteristic。代码会记录发现到的 services / characteristics，用于调试设备差异。

## 5. 协议常量

`lib/core/protocol/nb_protocol.dart` 是开源项目的协议核心。

### 5.1 协议代际

| 代际 | sync2 | vehicleTarget | vehicleCmd |
| --- | --- | ---: | ---: |
| Gen2 | `0xA5` | `0x09` | `0x64` |
| Gen3 | `0xB5` | `0x04` | `0x03` |

frame 固定以 `0x5A` 开头，Gen2 使用 `5A A5`，Gen3 使用 `5A B5`。

### 5.1.1 Encryption3 / V3Auth 的范围与冲突

开源项目代码把 `Gen3` 写为 `5A B5`，但远端协议说明的 [Protocol versions](https://nootnooot.codeberg.page/segway-ninebot-ble/protocol/#protocol-versions) 明确：BLE 的 Protocol2、Encryption2、Encryption3 都使用 `5A A5`；Encryption3 是 `Protocol2 + V3Auth`。`5A B5` 专属于 WiFi v2（两字节长度、两字节寄存器索引），不是 BLE。两份来源存在直接冲突，不能把开源代码里的 `Gen3` 常量直接当作可用的 BLE Encryption3 实现。

远端 [Authentication](https://nootnooot.codeberg.page/segway-ninebot-ble/authentication/) 页面完整描述的仅是 Encryption2：PRE_COMM (`0x5B`)、SET_PWD (`0x5C`) 和 AUTH (`0x5D`) 均发送到 BLE board `0x04`，并定义 SHA-1/AES、password PRNG、计数器与重连逻辑。它没有公开 V3Auth 的 challenge、密钥派生、签名/证书、状态或重试细节，只说明 V3 使用不同的 `connectV3()`。

独立客户端应将 `Encryption3 / V3Auth` 标记为尚未验证。实现它至少需要目标车辆的 GATT 与 V3Auth 成功交互记录，或可审查的完整实现；在此之前不能复用 Encryption2 的认证流程。

### 5.2 命令

| 命令 | 值 |
| --- | ---: |
| `cmdRead` | `0x01` |
| `cmdWrite` | `0x02` |
| `cmdWriteNr` | `0x03` |
| `cmdReadResp` | `0x04` |
| `cmdWriteResp` | `0x05` |
| `cmdPreComm` | `0x5B` |
| `cmdSetPwd` | `0x5C` |
| `cmdAuth` | `0x5D` |

### 5.3 Board IDs

协议层中 motor 类常用 target：

| 常量 | 值 | 说明 |
| --- | ---: | --- |
| `boardDis` | `0x01` | Display / Dashboard |
| `boardBle` | `0x04` | BLE module |
| `boardVcu` | `0x09` | Vehicle Control Unit |
| `boardMcu` | `0x20` | MCU / main controller |
| `boardBms1` | `0x22` | BMS1 |
| `boardBms2` | `0x23` | BMS2 |
| `boardHep` | `0x28` | HEP |

注意：`lib/data/device_config.dart` 的默认 module 映射里 `mcu=0x02`、`bms1=0x07`、`bms2=0x06`，但 `nb_protocol.dart` 和 `vehicle_provider.dart` 对 motor 实时实现直接使用 `boardMcu=0x20`、`boardBms1=0x22`、`boardBms2=0x23`。因此运行时代码比 JSON 默认映射更关键。

## 6. Frame build / parse

### 6.1 buildFrame

`buildFrame(target, cmd, index, data, gen)` 输出明文 frame：

```text
[0] sync1 = 0x5A
[1] sync2 = 0xA5 或 0xB5
[2] data length
[3] btId = 0x3E
[4] target
[5] cmd
[6] index
[7..] data
```

### 6.2 parseFrame

`parseFrame(decrypted)` 校验：

- 长度至少 7。
- `decrypted[0] == 0x5A`。
- `decrypted[1]` 是 Gen2 / Gen3 合法 sync2。
- `decrypted[4] == btId`。
- 读取：
  - `boardId = decrypted[3]`
  - `cmd = decrypted[5]`
  - `index = decrypted[6]`
  - `data = decrypted[7 .. 7+length]`

开源项目的 parse 视角是“设备到 App”的明文帧，因此 `btId` 位置和 app 发送方向的 target 位置相反。

## 7. 加密实现

`lib/core/crypto/nb_crypto.dart` 中 `NbCrypto` 是有状态加密上下文。

### 7.1 setKey

`setKey(key1, key2)`：

- 根据 `key1` 和 `key2` 派生 AES key。
- `key2` 可为空。
- handshake 不同阶段会重新 setKey。

### 7.2 非 SN 模式

PRE_COMM 前处于 non-SN 模式：

- 加密时 header 保留。
- body 使用派生 key 的 keystream 处理。
- 用于 PRE_COMM 和部分握手早期流程。

### 7.3 SN 模式

PRE_COMM 成功后启用 SN 模式：

- `setAuthParam(auth)`
- `startSn()`
- counter 从 SN 模式开始递增。
- 加密输出包含 counter 相关 tail。
- 解密时校验完整性和 counter 前进。
- 如果收到旧帧，会返回非 0 rc；上层可以丢弃 stale frame 后继续等待。

## 8. 握手实现

`lib/core/ble/handshake.dart` 实现 `performHandshake(...)`。

### 8.1 PRE_COMM

流程：

1. `crypto.setKey(btName.codeUnits, initialKey2)`
2. `buildPreComm(...)`
3. `crypto.encrypt(preCommFrame)`
4. transport send。
5. recv PRE_COMM response。
6. decrypt。
7. 从 response 中解析：
   - 16 字节 auth
   - SN
   - 车端是否已有 password

### 8.2 SET_PWD

当车辆没有 password 且需要新配对时：

1. 生成或传入 16 字节 password。
2. `crypto.setKey(btName.codeUnits, auth)`
3. `buildSetPwd(password)`
4. 加密发送。
5. 等 SET_PWD response。
6. 如果需要车辆确认，继续等 confirm response。

README 中说明，新配对需要按车辆按钮确认。

### 8.3 AUTH

当已有 password 时：

1. `crypto.setKey(password, auth)`
2. `buildAuth(sn)`
3. 加密发送 AUTH。
4. 解密 AUTH response。
5. AUTH 成功后连接进入已认证状态。
6. 后续读写寄存器使用 password + auth 派生出的加密上下文。

## 9. ConnectionProvider 实现

`lib/providers/connection_provider.dart` 是开源 App 的连接中枢。

### 9.1 状态模型

`ConnectionPhase` 包含：

- disconnected
- connecting
- connected

`BleConnectionState` 中保存：

- phase
- message / error
- device
- protocol generation
- serialNumber
- auth
- password
- command ident
- counter
- 是否 connected

### 9.2 统一 BLE 队列

开源项目的一个关键实现是统一串行收发队列：

```dart
Future<void> _txChain = Future<void>.value();
static const _minTxGap = Duration(milliseconds: 5);
```

所有触达 BLE transport 的操作都通过 `_serialize(...)`：

- `sendAndRecv`
- `sendOnly`
- `readRegister`
- `writeRegister`
- 设置页读写
- 轮询读

这样可以保证：

- 不会有两个读请求交错。
- 不会有写命令插进读请求和对应回包之间。
- 多个页面同时触发读写时也按 FIFO 执行。
- 每次操作前至少留 5ms 间隔，减少车端 RX buffer 压力。

### 9.3 sendAndRecv

`sendAndRecv(plaintext, timeout)`：

1. 进入 `_serialize`。
2. 检查当前 transport / crypto 是否可用。
3. `crypto.encrypt(plaintext)`。
4. `transport.send(encrypted)`。
5. 循环等待 response：
   - `transport.recv(timeout)`
   - `crypto.decrypt(respEnc)`
   - 如果 `rc==0`，parseFrame 并返回。
   - 如果 `rc!=0`，视为 stale frame，丢弃并重试。
6. 默认最多重试 stale frame 3 次。
7. 超时返回 null。

### 9.4 readRegister / readU16 / readU32

- `readRegister(target, register, readLen, timeout)` 构建 READ frame。
- `readU16` 要求 response data 至少 2 字节。
- `readU32` 要求 response data 至少 4 字节。
- register 读取不会裸发，都会经过加密和统一队列。

### 9.5 连接与 MTU

`_connectOnce(...)` 中：

1. `device.connect(timeout: 15s)`
2. 监听 connectionState。
3. 请求 MTU 247。
4. discovery services。
5. 关闭旧 notify。
6. 开启 notify。
7. 创建 `FlutterBleIO` 和 `BleTransport`。
8. 留出 settle delay。
9. 尝试 session reuse 或完整 handshake。

### 9.6 session reuse

开源项目支持缓存上一次成功握手后的：

- auth
- SN
- counter
- password
- command ident

连接时如果有缓存，可能跳过完整 PRE_COMM，尝试 SN mode 直接 probe。

实现中会扫描 counter：

- 使用保存的 counter 附近值发 read speed probe。
- 如果回包可解密且 register 正确，则 session reuse 成功。
- 如果出现 echo / stale / timeout，则回退到完整 handshake。

### 9.7 自动重连

异常断开时：

- 如果用户主动断开，不重连。
- 否则进入 auto reconnect。
- 最大重连次数：3。
- delay：2s、4s、8s。
- iOS 场景下会对“看似断开但数据连接还活着”的情况做 session probe；probe speed 成功则忽略断开事件。

## 10. BleTransport 实现

`lib/core/ble/ble_transport.dart` 负责 BLE characteristic I/O。

### 10.1 FlutterBleIO

`FlutterBleIO` 包装 flutter_blue_plus 的 characteristic：

- `write(data, withoutResponse: true)`
- `subscribe()`
- `unsubscribe()`
- notify stream 接入 transport。

### 10.2 send

`BleTransport.send(data)`：

- 写入完整加密帧。
- 依据 BLE 写入能力和 MTU 处理分片。
- 默认走 write without response。
- 支持日志输出完整帧，便于离线 decrypt tooling 使用。

### 10.3 recv

`BleTransport.recv(timeout)`：

- 等待 notify 数据。
- 将 chunk 拼成完整加密帧。
- 通过 frame length 推断完整帧大小。
- 超时抛出 TimeoutException。

## 11. VehicleProvider 数据模型

`lib/providers/vehicle_provider.dart` 定义 `VehicleData`。

### 11.1 实时字段

| 字段 | 说明 |
| --- | --- |
| `battery` | 电量百分比 |
| `speedRaw` | 速度原始值，0.1 km/h |
| `leftMileageRaw` | 剩余里程原始值，0.1 km |
| `singleMileageRaw` | 单次里程，10m 单位 |
| `runningTime` | 运行时间，秒 |
| `aveSpeedRaw` | 平均速度，0.1 km/h |
| `sigMaxSpeedRaw` | 标牌 / 显示最高速度，0.1 km/h |
| `powerRaw` | 功率原始值，W |
| `mainPower` | 主电源状态 |
| `rBool` | 车辆状态 flags |
| `alarm` | 报警状态 |
| `timeFull` | 满电时间 |
| `preciseMileage` | 精确里程 |
| `mileageRaw` | 总里程，米 |
| `ecuPn` | ECU PN |

### 11.2 设置字段

| 字段 | 说明 |
| --- | --- |
| `funBool` | DIS 功能 flags |
| `funAppBool` | App 功能 flags |
| `funAppBool2` | App 功能 flags 2 |
| `ctlBool2` | 控制 flags |
| `limitSpeedRaw` | 限速，km/h |
| `ratedSpeedRaw` | 额定最高速度，0.1 km/h |
| `warn` | 声音 / 提示相关 |
| `autoLock` | 自动锁车 |
| `alarmVolume` | 报警音量 |
| `alarmLevel` | 报警灵敏度 |
| `speedIntensity` | 加速 / 速度强度 |
| `speedSafeLock` | 安全限速 |
| `tcs` | TCS |
| `slope` | 坡道 / 模式 |
| `eleBrake` | 电子刹车 |
| `activeGearMask` | 当前骑行模式 bitmask |
| `gearTopSpeed` | 当前档位最高速度 |
| `gearAccSpeed` | 当前档位加速速度 |
| `gearAccSens` | 当前档位加速灵敏度 |
| `gearTcs` | 当前档位 TCS |
| `gearNitro` | 当前档位 nitro |
| `gearEnergy` | 当前档位能量设置 |

### 11.3 缩放

开源项目在 `VehicleData` 中定义：

| 数据 | 缩放 |
| --- | --- |
| speed | `speedRaw × 0.1 km/h` |
| leftMileage | `leftMileageRaw × 0.1 km` |
| singleMileage | `singleMileageRaw × 0.01 km` |
| aveSpeed | `aveSpeedRaw × 0.1 km/h` |
| sigMaxSpeed | `sigMaxSpeedRaw × 0.1 km/h` |
| limitSpeed | `limitSpeedRaw` km/h |
| ratedSpeed | `ratedSpeedRaw × 0.1 km/h` |
| mileage | `mileageRaw × 0.001 km` |
| power | `powerRaw` 转 int16；大于 `0x7FFF` 时减 `0x10000` |

代码注释注明这些缩放参考 `device_commands.json` 并在 E125S 仪表上验证。

## 12. 分层轮询实现

`VehicleNotifier` 实现分层轮询。

### 12.1 PollTier

```dart
enum PollTier { fast, medium, slow, settings }
```

### 12.2 Poll claim stack

开源项目不是所有页面都一直轮询全部数据，而是通过 claim stack 控制当前页面需要哪些轮询层。

- 页面 mount 时调用 `resumePolling(tiers, by: screenName)`。
- 页面 dispose 时调用 `pausePolling(by: screenName)`。
- `_stack.last` 是当前生效 claim。
- 只有最上层页面声明的 tiers 会执行。
- 下层页面的轮询需求会被暂时抢占。

这个设计用于避免后台页面继续抢 BLE 队列。

### 12.3 定时器

`startPolling()` 中创建四个 timer：

| Timer | 周期 | 方法 | 说明 |
| --- | ---: | --- | --- |
| fast | 250ms | `_pollFast()` | 实时速度 |
| medium | 1000ms | `_pollMedium()` | 功率、运行时间、显示最高速度 |
| slow | 2000ms | `_pollSlow()` | 电量、里程、状态 |
| settings | 5000ms | `_pollSettings()` | 低频设置同步 |

代码中旧注释曾写 fast timer 500ms speed/power，但实际实现和后续注释是 250ms 且只读 speed。

### 12.4 睡眠板子跳过

开源项目定义：

```dart
_sleepsWhenOff = {
  0x20, // MCU
  0x25, // ECU / VCU 相关
  0x22, // BMS1
  0x23, // BMS2
}
```

`_boardAwake(target)`：

- 如果 target 不在睡眠集合，直接允许读。
- 如果 target 在睡眠集合，必须 `state.isPoweredOn` 为 true 才读。

`_safeU16` / `_safeU32` 会在读前调用 `_boardAwake`，避免车辆未上电时读取睡眠板子导致固定超时。

### 12.5 safe read

`_safeU16(target, register, timeout: 2s)`：

- 如果板子睡眠，返回 null。
- 调 `_conn.readU16(...)`。
- 任意异常返回 null。

`_safeU32` 同理。

## 13. 各轮询层细节

### 13.1 初始一次性读取

`_readOnce()` 会读：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| VCU `0x09` | `0x03` | `regMainPower` | 主电源状态 |
| DIS `0x01` | `0x8A` | `regFunAppBool` | App 功能 flags |
| DIS `0x01` | `0x8C` | `regFunAppBool2` | App 功能 flags 2 |
| DIS `0x01` | `0x48` | `regRatedSpeed` | 额定速度 |
| DIS `0x01` | `0x93` | `regLimitSpeed` | 限速 |
| DIS `0x01` | `0x7D` | `regFunBool` | 功能 flags |
| VCU `0x09` | `0x28` | `regEcuPn` | ECU PN，readLen 14 |

### 13.2 Settings 一次性读取

`_readSettingsOnce()` 会读：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| DIS | `0x75` | `regWarn` | 提示音 / 声音预设 |
| DIS | `0x7C` | `regCtlBool2` | 控制 flags |
| DIS | `0x85` | `regAutoLock` | 自动锁车 |
| DIS | `0x76` | `regAlarmVolume` | 报警音量 |
| DIS | `0x77` | `regAlarmLevel` | 报警等级 |
| DIS | `0xAA` | `regBool2` | 当前骑行模式 |
| MCU | `0x55` | `regTcs` | TCS |
| MCU | `0x56` | `regSlope` | 坡度 / 模式 |
| MCU | `0x57` | `regEleBrake` | 电子刹车 |
| MCU | `0x52` | `regSpeedIntensity` | 加速强度 |
| MCU | `0x53` | `regSpeedSafeLock` | 安全限速 |
| MCU | `0x31` | `regGearTopSpeed` | 档位最高速度 |
| MCU | `0x2D` | `regGearAccSpeed` | 档位加速 |
| MCU | `0x2E` | `regGearAccSens` | 档位加速灵敏度 |
| MCU | `0x2F` | `regGearTcs` | 档位 TCS |
| MCU | `0x30` | `regGearNitro` | 档位 Nitro |
| MCU | `0x2C` | `regGearEnergy` | 档位能量 |

### 13.3 Fast

`_pollFast()`：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| DIS | `0x26` | `regSpeed` | 当前速度 |

开源项目注释明确说明：fast tier 只读一个寄存器，避免速度计排在电池 / 里程等慢读后面。

### 13.4 Medium

`_pollMedium()`：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| DIS | `0xBD` | `regPower` | 当前功率 |
| DIS | `0xBA` | `regRunningTime` | 运行时间 |
| DIS | `0x24` | `regSigMaxSpeed` | 标牌 / 显示最高速度 |

代码注释强调 `regRunningTime` 是 u16，不能按 u32 读取，否则会把邻近寄存器的 2 字节读进来，导致异常大值。

### 13.5 Slow

`_pollSlow()`：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| DIS | `0xB5` | `regBattery` | 电量 |
| DIS | `0x25` | `regLeftMileage` | 剩余里程 |
| DIS | `0xB9` | `regSingleMileage` | 单次里程 |
| DIS | `0x27` | `regAveSpeed` | 平均速度 |
| DIS | `0xB7` | `regMileage` | 总里程，u32 |
| DIS | `0xB2` | `regBool` | 车辆状态 flags |
| DIS | `0x1C` | `regAlarm` | 报警状态 |
| VCU | `0x03` | `regMainPower` | 主电源状态 |

代码注释强调 `regSingleMileage` 在 E-series firmware 上是 u16，不能按 u32 读，否则会串到邻近寄存器并产生上万 km 的错误单次里程。

### 13.6 Settings 周期刷新

`_pollSettings()` 每 5 秒读：

| Target | Register | 常量 | 说明 |
| --- | ---: | --- | --- |
| DIS | `0xAA` | `regBool2` | 当前骑行模式 |
| DIS | `0x93` | `regLimitSpeed` | 限速 |
| MCU | `0x31` | `regGearTopSpeed` | 档位最高速度 |
| MCU | `0x2C` | `regGearEnergy` | 档位能量 |

用途是当用户在车机、官方 App 或其他页面改设置时，当前 UI 能低频同步。

## 14. eScooter E / N 配置中的仪表寄存器

`assets/device_configs/81.json` 和 `820.json` 对 eScooter E / N 的仪表寄存器很接近。以 `81.json` 为例：

| name | module | s_index | s_data |
| --- | --- | ---: | ---: |
| `rLeftMileage` | dis | `0x25` | 2 |
| `rBattery` | dis | `0xB5` | 2 |
| `rMileage` | dis | `0xB7` | 4 |
| `rTimeFull` | dis | `0x39` | 2 |
| `rSpeed` | dis | `0x26` | 2 |
| `rChargePower` | dis | `0x91` | 2 |
| `rVoltage` | bms1 | `0x1A` | 2 |
| `rAlarm` | dis | `0x1C` | 2 |
| `rMaxPower` | dis | `0x49` | 2 |
| `rMiniPower` | dis | `0x4F` | 2 |
| `rPower` | dis | `0xBD` | 2 |
| `rSingleMileage` | dis | `0xB9` | 2 |
| `rRunningTime` | dis | `0xBA` | 2 |
| `rMaxSpeed` | dis | `0x48` | 2 |
| `rSigMaxSpeed` | dis | `0x24` | 2 |
| `rAveSpeed` | dis | `0x27` | 2 |
| `rNos` | dis | `0x5E` | 2 |
| `rPowerLastIndex` | dis | `0xBF` | 2 |
| `rPowerData` | dis | cmd `0x0D` | 特殊功率数据命令 |
| `rPreciseMileage` | dis | `0xAF` | 2 |

其中 `rNos` 是当前 MMAX90 实验中氮气值 `0x5E` 的开源命名依据。

## 15. 设置 / 档位相关寄存器

`81.json` 和 `vehicle_provider.dart` 中可见的设置类寄存器包括：

| name | target / module | register | 说明 |
| --- | --- | ---: | --- |
| `rBool2` | DIS | `0xAA` | 骑行模式 bit field |
| `rGearData1` | MCU | `0x22` | M1 32 字节档位配置 |
| `rGearData2` | MCU | `0x3A` | M2 32 字节档位配置 |
| `rGearEnergy` | MCU | `0x2C` | 档位能量 |
| `rGearAccSpeed` | MCU | `0x2D` | 档位加速速度 |
| `rGearAccSensitivity` | MCU | `0x2E` | 档位加速灵敏度 |
| `rGearTcs` | MCU | `0x2F` | 档位 TCS |
| `rGearNitroSpeed` | MCU | `0x30` | 档位 Nitro |
| `rGearTopSpeed` | MCU | `0x31` | 档位最高速度 |
| `rSlope` | MCU | `0x56` | 坡道 / 模式 |
| `rEleBrake` | MCU | `0x57` | 电子刹车 |
| `rTCS` | MCU | `0x55` | TCS |
| `rSpeedIntensity` | MCU | `0x52` | 加速强度 |
| `rSpeedSafeLock` | MCU | `0x53` | 速度安全锁 |

开源项目已有对应的 read builder 和部分 write builder，但 UI 使用时会通过统一队列串行化。

## 16. BMS 相关读取

`nb_protocol.dart` 定义了基础 BMS 寄存器：

| 常量 | register | 说明 |
| --- | ---: | --- |
| `regVoltage` | `0x1A` | 电压 |
| `regBmsCycleCount` | `0x1B` | 循环次数 |
| `regBmsSoc` | `0x32` | BMS SOC |

builder：

- `buildReadVoltage({int bms = boardBms1})`
- `buildReadBmsSoc({int bms = boardBms1})`
- `buildReadCycleCount({int bms = boardBms1})`

在 motor target 常量中，BMS1 / BMS2 分别是 `0x22` / `0x23`。

## 17. 控制操作

`nb_protocol.dart` 中定义车辆动作寄存器：

| 常量 | register | 说明 |
| --- | ---: | --- |
| `regOpenAcc` | `0x4E` | 打开 ACC / 上电 |
| `regCloseAcc` | `0x46` | 关闭 ACC / 下电 |
| `regOpenTrunk` | `0x73` | 开座桶 |

Gen2 vehicle action 使用 `vehicleTarget=0x09`、`vehicleCmd=0x64`，payload 需要 command ident。README 中说明：新配对只读实时数据可以工作，但开机、开座桶等控制操作还需要九号云端签发的 16 字节 `ident`，否则车辆会拒绝控制类操作。

## 18. 设置页面与快照页面

开源项目不只轮询仪表，也有设置页面和配置快照页面。

`lib/screens/settings/config_snapshot_screen.dart` 的快照读取实现：

- 不使用 VehicleNotifier 的周期 timer。
- 自己按分组读每个寄存器一次。
- 读取时显示进度。
- 每个 read 使用较短 timeout：1200ms。
- 如果车辆未上电，会跳过睡眠板子。
- 大于 4 字节的读取按 raw / ASCII 处理。
- 4 字节读 u32。
- 其余读 u16。
- 单个读取耗时超过 200ms 会写 slow read 日志。

这说明开源项目区分了“实时仪表轮询”和“配置快照读取”：仪表追求低延迟，配置读取追求完整性和可中断。

## 19. 骑行记录

开源项目的 session provider / session screen 使用 VehicleProvider 数据记录骑行：

- BLE speed
- GPS speed
- power
- battery
- latitude / longitude
- max BLE speed
- max GPS speed
- avg BLE speed
- distance

CSV 导出 header：

```text
timestamp,ble_speed_kmh,gps_speed_kmh,power_w,battery_pct,lat,lng
```

实时速度由 BLE 速度为主，GPS 速度作为辅助线展示。

## 20. 与 MMAX90 相关的事实对应

开源实现中已存在并与当前 MMAX90 实测相符的部分：

| MMAX90 实测字段 | 开源名称 | target | register | 缩放 / 说明 |
| --- | --- | ---: | ---: | --- |
| 当前速度 | `rSpeed` | DIS `0x01` | `0x26` | `uint16 × 0.1 km/h` |
| 当前功率 | `rPower` | DIS `0x01` | `0xBD` | signed int16 W |
| 氮气 | `rNos` | DIS `0x01` | `0x5E` | raw u16 |
| 电量 | `rBattery` | DIS `0x01` | `0xB5` | 百分比 |
| 剩余里程 | `rLeftMileage` | DIS `0x01` | `0x25` | `uint16 × 0.1 km` |
| 总里程 | `rMileage` | DIS `0x01` | `0xB7` | `uint32 × 0.001 km` |
| 单次里程 | `rSingleMileage` | DIS `0x01` | `0xB9` | 开源 E125S 注释为 `×0.01 km` |
| 运行时间 | `rRunningTime` | DIS `0x01` | `0xBA` | 秒 |

当前 MMAX90 专用差异需要以本车日志继续校验，尤其是 `0xB9` 缩放。

## 21. 开源实现的当前边界

以下是开源项目自身实现边界：

- 本地 registry 没有 MMAX90 专用配置。
- motor 类配置丰富，但车型间 register 缩放和 target 仍可能不同。
- 读错长度会导致邻近寄存器串入，项目注释已明确提醒 `rRunningTime` 和 `rSingleMileage` 的宽度问题。
- 控制类操作不仅需要 password AUTH，还需要 command ident。
- session reuse 和 counter 扫描是开源 App 的完整连接体验组成部分；单独移植某个寄存器表并不等于完整连接链路。
