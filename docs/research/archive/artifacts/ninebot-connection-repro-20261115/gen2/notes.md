# 独立 Gen2 连接与投屏控制探针复核

## 1. 可复用能力与缺口

该 engine 没有 type1 文件读取130/131/132，也没有 TFT 模式命令0x71。其 profile 中 Custom 候选 UUID 与本轮已确认的媒体服务/特征集合重叠；UUID 存在本身不能证明它同时承载控制认证。测试工程因此限定**已由广播确认认证版本2、实际发现 NUS 控制写/通知特征**的路线，Custom-only / Gen3 / 未分类广播先保留枚举结果，不能自动尝试 Gen2。

新 `NinebotProbeSession` 复用 `NinebotEncryption2Codec`，没有复制加密算法，亦没有复用原 engine 的自动轮询和 SET_PWD 行为。其作用是把每个复现阶段变成一次显式操作并提供可核对的收发结果。

## 2. API 与阶段

文件：[NinebotProbeSession.java]（本地来源，未随附原始材料）。由宿主统一串行调用，GATT 订阅、ATT 分片、写入完成回调、重连和超时由宿主管理。

```text
new Session(exactAdvertisedName, profileId, existingPasswordOrNull, listener)
  → start()                         一次 PRE_COMM，不重试
  → accept(rawNotificationBytes)    先按应用帧长度重组，再解密和校验
  → wait_precomm
      index=0                      unpaired：停止，不发 SET_PWD
      index!=0，无 password         credential_required：停止
      index!=0，有合法 password     wait_auth：发送 AUTH
  → AUTH cmd=0x5D / index=1 / 来源板匹配
  → authenticated                  仅认证成功，不宣称显示或 prepared
      read(board,register,length)   一次普通寄存器读取
      readCastConfig(0x23)          显式 type1 文件读取
      setNaviMode(0x23,mode)        显式模式开停
  → onTimeout() / close()           终止，不自动继续下一阶段
```

收到 PRE_COMM 之前不需要持久密码。已有绑定车辆的 AUTH 需要该车合法的16字节 password；PRE_COMM 的动态 auth 不是这个密码。新探针收到“未设 password”回复也不会创建或替换凭据。不存在将旧会话 auth、密文或计数器直接重放当作新会话认证的路径。

Session 校验应答源板、命令、需要匹配的索引；认证后拒绝 counter=0 的模式降级。MAC 或计数器校验失败、模式错误、错误分包或超时都会结束会话。`close` 清除自身持有的 password/auth/SN 数组并丢弃 codec；这不等同于宣称 JVM 已擦除所有历史内存副本。

Gen2 普通明文格式：

```text
请求：5A A5 N 3E TARGET CMD INDEX DATA[N]
应答：5A A5 N SOURCE 3E CMD INDEX DATA[N]
```

实际 GATT 写入使用原 codec 加密后的 `N+13` 字节帧。控制 ATT 分片在认证前保持20字节，即使原始 ATT MTU 已协商变大；认证后可使用实际 ATT MTU减3。**这不是 BLE 视频 RTP 的 MTU 计算**，后者见前轮媒体报告。

当前可选择的握手目标来自已有显式 profile；标准为0x04，MMAX90候选为 PRE_COMM/AUTH 0x09。历史 MMAX90 抓包只证明 PRE_COMM 0x09，新的合成测试只能证明该分支产生预期字节，不能补成实车 AUTH 0x09 已成功。

## 3. type1 文件与模式复现

官方证据由另一分支独立提取并执行，见 [official-connect/notes.md](../official-connect/notes.md) 及其 `original-method-validation.json`。目前只有 Mmax2 / N3 / Mz 的三份解密命令表能核实 TFT=0x23 和下面的命令映射；宿主还须按选择的车型表开放操作，不能因为 Session 接受0x23就认定适配全部车型。

| 阶段 | 明文命令字段 | 探针接收条件 |
| --- | --- | --- |
| 文件开始 | target23 / cmd82 / index1 / data010800 | source23 / cmd82 / data恰好4字节，小端文件长度 |
| 数据组请求 | target23 / cmd83 / index=组号 / data空 | source23 / cmd83 / 组内index从0依次增加 |
| 文件校验 | target23 / cmd84 / index0 / data空 | source23 / cmd84 / index0 / data16字节二进制MD5 |
| 模式 | target23 / cmd71 / index0 / data单字节mode | source23 / cmd71 / index0 的匹配应答 |

模式0/1/2/3分别是停止 / 简易导航 / 地图导航 / 巡航。模式应答没有被恢复为显示验收；回调名特意使用 `onModeAcknowledged`，不能据此宣称仪表已显示视频。

配置长度限定1..262144字节，对应8位组号 × 每组8包 × 名义128字节。按 `ceil(fileLen/128)` 计总小包数；实际每包允许1..128字节，累计不得超出声明长度，最后一个预期小包后必须精确到达声明长度。组内索引必须连续；每组完成后再发下一组请求。本探针不重试错序组。

这是根据原方法执行结果调整后的行为：官方 `BtFileReader.onResponse` 对129字节文件接受64+65两包；128+128则在原 Java `System.arraycopy` 中越界，**没有末包自动截断**。因此不能把“最后包总是128字节填充”当作已确认行为。探针有界拒绝超长，避免复制原方法越界缺陷；其每包上限128是当前测试范围，不能把这个上限写成已证明的全部固件要求。

官方 begin 回复只按命令处理，不要求 index=1；已执行 index42 向量确认。因此探针也不凭猜测强制该索引。官方允许短长度输入，本探针只接受预期4字节长度作为明确的收紧条件。

MD5 对完整原始文件计算，含末尾可能存在的 NUL。只有原始文件校验成功才触发 `onCastConfig(rawBytes)`，回调仍保留 NUL；宿主随后使用严格 UTF-8，至多删除一个末尾 NUL，再解析 JSON。Session 不重复裁剪，也不根据未验证配置自动开启投屏。

## 4. 密码学交叉验证

官方 `NbCrypto.startVerify(PRE_COMM)` 调用 `setSecretKey(nameBytes,null)`；项目 Java 显式使用 `name + firmwareData`。本轮没有只从字符串推断两者等价，而是执行原 `libnbcrypto.so` 的 `_Z10nb_encryptP14crypto_param_tPhhS1_`，地址0x69f80：

- 原 ELF SHA-256：`21e594e8d8dedeb5cc04942a763cab7b88f4686980bcc543be9cf3cf344b9ca2`。
- Unicorn 执行未修改的 ARM64 指令；SHA1、AES、KDF、计数器、nonce、tag和帧封装均由原函数完成。
- 只提供 libc memcpy/memmove/memset/memcmp 和 TLS环境；没有用脚本替换密码学。
- 53份独立 Python `hashlib + cryptography AES` 字面量逐字节一致，覆盖 PRE_COMM、AUTH、普通读取、文件开始/各数据包/MD5、模式和拒绝测试用报文。
- 另外5个 PRE_COMM 类用例令原 native 的第二个 key 指针为 null，结果与显式FW常量完全一致，共58个原函数场景。

该原函数验证的是**发送方加密字节一致性**，没有执行原 native 解密器、JNI、SDK FullConnect、Android BLE 或车端认证器。Java 接收校验由独立wire向量验证，不能把这两类结果合并成实车已连接。

输入和结果见 [probe-vectors.json](probe-vectors.json)、[original-crypto-validation.json](original-crypto-validation.json)。向量的 name/password/auth/SN 全为脚本合成值，不是从车辆或账号提取的材料。

## 5. 本分支验证结果

[session-validation.json](session-validation.json) 记录最终 Session/codec/profile/test源文件哈希及以下结果：

- 27个 JUnit 场景通过：分片、合法认证和一次读取、缺凭据停止、未绑定停止、错误来源、认证拒绝、MAC篡改、counter降级/重放、请求并发限制、超时/关闭、配置组切换、可变小包、完整MD5、错误MD5/错序/越界、匹配模式应答和回调内关闭。
- 53个独立 Python wire 字面量冻结核对通过。
- 58个原 ARM64 发送加密场景通过。

这些检查是同一组证据的不同边界，不应把根代理的 Gradle 再执行计为额外覆盖。

复跑：

```powershell
pwsh -NoLogo -NoProfile -File artifacts/ninebot-connection-repro-20261115/gen2/run-validation.ps1
```

依赖本机 JBR、Gradle缓存中的 JUnit4/Hamcrest、Python cryptography/pyelftools 和前轮证据目录内的 Unicorn。原APK/SO仍在 Git忽略的本机 raw 中；其他机器需要提供同哈希输入。

## 6. 对宿主 GATT 的独立复核

2026-11-15审阅 `ProbeGattClient.java` 初稿：单actor串行、每次只保留一个 pending write、Android GATT status显式检查、旧link对象过滤、断开generation失效、超时与队列清空的结构与 Session 合约一致。WRITE_NO_RESPONSE 本地回调仍不能证明车端接收、解码或显示，日志与UI需保留这个区别。

已向根代理反馈一个具体竞态：CCCD成功时先清 `subscriptionPending`、再延迟settle启动Session，在这段窗口重复点击认证可提交第二次CCCD并安排两个Session。应将订阅/启动pending状态保持到真正启动，并对延迟任务核对generation和状态。根代理负责修复和最终工程验证；本分支未改其宿主文件。

后续实车证据仍须提供实际广播、控制/媒体服务、PRE_COMM/AUTH响应、协商MTU、type1配置和显示结果。没有现成合法password或遇到真正V3Auth时，本Gen2探针不会假装继续连接；对应材料与算法由Gen3分支单独核查。
