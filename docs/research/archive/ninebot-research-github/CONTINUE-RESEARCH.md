# 九号 Gen2 / Gen3 后续深度研究清单

> 整理日期：2026-10-30。依据现有研究归纳，未新增实车、云端或密码学验证。

## 1. 先阅读的材料

1. [MMAX90 连接策略](docs/mmax90-connection-strategy.md)：已存在的 Gen2 成功链路和 SET_PWD/寄存器限制。
2. [Gen3 深度研究](docs/research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md)：重点读第 11、13、14 节，包含 2026-10-10 REA 续研。
3. [Gen3 证据摘要](docs/research/ninebot-gen3-20261009/evidence-summary.json)、[静态缺口摘要](docs/research/ninebot-static-gap-20261009/evidence-summary.json)、[REA 续研摘要](docs/research/ninebot-static-gap-20261009/rea-followup-20261010.json)。
4. 需要研究投屏时再读 [NinebotEnhance 复核第 8 节](docs/research/NinebotEnhance非导入APK投屏可行性复核-20261009.md#8-静态缺口续研投屏控制配置与媒体包)。

## 2. MMAX90 Gen2

已有凭据的 MMAX90 只读连接测试已通过；协议原理与实测字段统一收录于 [MMAX90 测试与协议原理](docs/mmax90-connection-strategy.md)。本清单不重复展开 MMAX90 的阶段性问题或实验实现细节。

## 3. Gen3 / V3Auth：按数据流继续

优先追踪：

```text
V3SessionKeyResponse.appEnc
  → BleKeyStatusManager.saveKeyInfo
  → updateSecret(version=3, sn, session_key_hex)
  → native SecretInfo 检查/转换
  → keyring 存取的实际虚表目标
  → 会话消费位置
  → 加密上下文和线上认证帧
```

- [ ] 定位 keyring 虚表 `+0x20`、`+0x48` 的实际方法与返回值消费；区分保存、读取、清理与密钥转换。
- [ ] 还原 `0x374040 → 0x5a5ff4` 的转换语义。32 个 ASCII hex 字符与显式长度 `0x20` 不能证明输出为 16 字节原始 AES key。
- [ ] 定位 `Protocol::V3_2` 的构造、分派、challenge、确认和密钥分发状态机。不要把 `v2_3` 日志标签直接等同于 V3_2 或 BLE 命令号。
- [ ] 追踪 native 云端请求函数的签名输入、规范化、临时公钥、证书/签名校验，以及与 V3_2 分支的真实关系。
- [ ] 确认 nonce、AAD、tag、计数器端序和密钥派生；仅出现 AES-CCM 字符串不足以确定协议算法。
- [ ] 建立正常、错误凭据、重放、损坏、过期和刷新测试向量；记录输入、输出和所绑定的 APK/库 SHA-256。
- [ ] 获得授权目标车首次配对、已有凭据重连、断网/过期、模块更换的完整对照；核验哪些阶段需要联网。

native 地址仅对应现有研究的固定 arm64 ELF 虚拟地址；不是运行地址或直接文件偏移。哈希见配套证据摘要。原始二进制与反编译材料未附，继续分析需自行准备相同合法来源的材料。

## 4. 新车型取样

先记录具体车型/年款/地区、固件、原始广播、Manufacturer Data 校验、运行时认证版本、GATT 服务/特征及 MTU，再选模块表与寄存器。

`Gen3` 枚举、MAX G3 商品名、2025 后年份、Encryption3/V3Auth 是不同概念。官方基础配置默认 `encrypt=2` 也不能覆盖实车广播版本。

MAX G3/F3/GT3 的官方 VCU 模块表为 `0x16`，不能沿用 MMAX90 握手 `0x09` 或 DIS 读取表。参考速度 `vcu:0x57` 等仍需同车验证。

## 5. 投屏支线

- [ ] 从 `writeRtpData` JNI 入口继续定位 `LocalPool` 调度的具体 future poll 目标，确定 `RtpHandler` 的构造和发送消费者。
- [ ] 确认 BLE native 是否剥除/重写 RTP 头、插入索引、分片或加密，补齐真实媒体向量。
- [ ] 取得仪表配置、投屏开停、RTCP 保活、板端反馈和 Wi-Fi Direct/RTP/KCP 时序。
- [ ] 分别验证全彩 Wi-Fi H.264 与半彩 BLE MPEG2；现有 4 个 RTP/3 个 RTCP 离线向量不等于仪表端互通。

## 6. 本轮继续研究的交付标准

每个新增结论应包含来源版本/哈希、调用链或报文位置、离线或实车验证方式、可复核向量和仍未确认的范围。把官方静态证据、参考实现候选与实车验证分开记录。

完整独立 V3Auth 的完成条件包括真实认证状态机、精确密码学转换、有效报文向量和目标车型互通。静态线索增加本身不能视为完成。
