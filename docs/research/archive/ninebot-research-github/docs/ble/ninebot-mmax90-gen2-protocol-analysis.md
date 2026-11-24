# Ninebot MMAX90 Gen2 BLE：帧与加密协议

本页以 MMAX90 既有凭据直连中验证的 Gen2 帧、会话加密与校验规则为准，不按早期分析阶段拆分，也不以参考项目的 `Gen3/5A B5` 命名覆盖 MMAX90 的实测 Gen2 流程。已通过测试的连接顺序与遥测结果见 [MMAX90 测试与协议原理](../mmax90-connection-strategy.md)。参考协议实现：[segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble)，固定对照版本 `d26b181c1d7eda8e11248af53a6a622334746d9a`。

## 帧格式

```text
明文请求：
5A A5 LEN 3E TARGET CMD INDEX DATA...

Gen2 加密帧：
5A A5 LEN | encrypted body | encrypted CBC-MAC (4 bytes) | counter (big-endian u16)
```

响应中命令前含源 board 字节。BLE 通知可在 ATT MTU 边界分片，必须按协议长度累计完整帧，再交给解密器。

## 引导与会话密钥

```text
FW_DATA = 97CFB802844143DE56002B3B34780A5D
K0 = SHA1(pad16(deviceName) || FW_DATA)[0:16]
sessionKey = SHA1(pad16(password) || pad16(auth))[0:16]
```

PRE_COMM 使用设备名称与公开 `FW_DATA` 常量构造引导上下文。成功响应给出当次会话的动态 `auth` 和 SN。`auth` 不是持久凭据，下一次连接必须重新获取。

有效 password 为 16 字节。通过 PRE_COMM 取得新 `auth` 后，使用 `password + auth` 派生认证会话密钥并执行 AUTH。MMAX90 已通过的连接使用 PRE_COMM 和 AUTH 目标 `0x09`；通用参考项目的 BLE board `0x04` 不能替代该车型实测值。

## 加密与完整性

认证帧体使用 AES-CTR 加密，并携带 4 字节 CBC-MAC。线上帧尾存储大端 u16 计数器；加密内部将该值编码为大端 u32，并构造 13 字节 nonce：

```text
nonce = counter_BE32 || auth[0:8] || 0x00
```

CBC-MAC 按完整明文帧计算；载荷使用 AES-CTR，4 字节 tag 使用相同 nonce 下的 CTR 首块掩码处理。线上计数器宽度与 nonce 内部计数器宽度不同，不应混为一谈。接收顺序为：

1. 按 LEN 重组完整 BLE 帧。
2. 读取计数器并按当前会话上下文解密。
3. 校验 CBC-MAC 与计数器状态。
4. 校验成功后才解析 board、命令、寄存器和 payload。

MAC 无效或计数器异常意味着当前帧不可采信；丢弃会话并重新 PRE_COMM/AUTH，不从未认证密文恢复 password。

## MMAX90 只读连接

```text
NUS 通知订阅
  → PRE_COMM(target=0x09)
  → 获取 SN 与新 auth
  → 使用同车既有 password 派生 sessionKey
  → AUTH(target=0x09)
  → 校验认证响应
  → DIS board (`0x01`) 只读寄存器
```

每次连接建立独立会话。password 是跨会话凭据；auth、计数器及会话密钥属于单次连接状态。未经验证的首次 SET_PWD 与车辆控制不属于此已通过流程。
