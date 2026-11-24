# Gen3 认证分支独立交叉复核

复核对象为同级 `gen3-auth/notes.md`、既有 `docs/research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md` 及同一冻结 ELF。无手机、车辆或云端会话参与。

本轮独立复跑 `gen3-auth/emulate_secret_boundary.py` 的275个用例和 `emulate_hex_key.py` 的12个用例；新增本目录 `cross_validate_gen3.py` 的33个原ARM64分段用例全部通过。结果见 `gen3-cross-validation.json`。这些数量是分段合成测试用例数，不是完整连接次数或实车覆盖数。

续审又独立复跑 `emulate_fast_auth.py` 的47例，全部通过；这是同一47例的交叉复跑，不额外累计覆盖。移除map插入hook后另做7个补充原函数用例，见本目录 `cross_validate_fast_auth.py` 和 `fast-auth-map-cross-validation.json`，细节见第7节。

## 1. next 入库与启动选槽

`0x37e1e4` 的真实版本3分支把非空 `session_key_hex` 以flag=1交给 `0x383b58`。后者使用 `0xd1788` 格式构建 `SN + ".__next__"` 名字；正常检查要求32字节ASCII hex。成功分支 `0x383ca8–0x383cb4` 仍把32字符文本原样交给存储入口。空key会以flag0、flag1分别调用删除helper。此结论在原函数执行中核对的是调用及参数；实际系统keyring的成功与持久性没有执行。

`0x684de8` 非零时 `0x383b58` 在校验和入库前直接返回成功。该配置的运行时来源仍未知；即使观察到updateSecret返回成功，也不能据此推导密钥已经落盘。

启动段 `0x31ca58–0x31cb28` 优先next，其次cur；选择同步写 `used_key_type/cur_tried/next_tried`。275用例里的四组合测试确实执行了原选择段，但测试在getter之后才注入合成Option值，并在hex解码入口停止，所以其证明范围是选槽逻辑，不包含真实安全存储读写、密钥有效期或车辆接受。

12个后续原函数用例已将独立边界推进到 `0x2bdd60 → 0x2a6420 → 0x384da0` 的hex解码：合成hex32确实输出bytes16，奇数长度、非hex、空白被拒绝。allocation/reserve/drop为hook；解码指令未被替换。此前“尚未执行hex32到bytes16”的保留应按这一新结果更新，但仍不能把这16字节直接称为最终无线密码全部参数。

## 2. 提升、清理与日志差异

新增12个promotion分段用例覆盖used_key_type=0/1/2与写/删返回错误组合，起点 `0x2e5c00`，终点 `0x2e5d1c`。文本rendering、日志、存储返回和drop为hook；没有执行前面的回复合法性检查，也没有证明整个握手已经成功。

| 前提 | 原指令实际调用 | 结论 |
| --- | --- | --- |
| used_key_type≠2 | 跳过提升存储和next删除 | 不能把每个成功分支都写成next提升 |
| used_key_type=2，cur写返回错误 | `0x383b58(flag0)` 后跳过删除 | cur写失败不会继续删next |
| used_key_type=2，cur写成功 | 再调 `0x383d20(flag1)` | 按SN主槽写cur，再删next |
| next删除返回错误 | 先记录错误，随后仍打印 `next key saved as cur and next removed` | 该日志不是删除持久化成功的充分证据 |

提升时的源文本来自 `0x2bd884`；本轮测试将它替换成合成hex文本，故只断言flag、调用顺序和条件。`0x2bd884` 的完整转码与其输入来源需由另一路继续跟踪。

## 3. expired / invalid 恢复入口

新增16个分段用例从 `0x2e5d4c` 执行，覆盖状态2/4、used=1/2和两tried标记组合：

- `used_key_type=2` 先进入读取cur的路径，与两tried标记当前取值无关。
- 其他used值只有当cur_tried与next_tried均非零时，才进入“both cur and next keys failed, need authentication”。
- 其余组合走另一key-invalid分支。

用例在这些分支边界停止，没有执行cur读取、锁、cipher初始化、实际重发或云端认证。静态 `0x2e6468` 后确有cur标记更新、hex解码、会话材料更新和 `0x2e6578` flag1清next的路径；删除同样有错误日志。`0x2e65ec` 的fallback日志不能代替上述每一步的成功核验。

## 4. V2_3、V3_2 与产品名

独立执行 `0x38cbc0` formatter核对discriminant0..4，依次输出 `V1_0/V2_2/V2_0/V2_3/V3_2`。discriminant3的构造分支 `0x388f74` 调 `0x38a818` 并返回vtable `0x6582f0`；其Debug槽+0x18指向 `0x38d7e8`，输出 `HandshakeV2_3`。discriminant4构造走另一vtable `0x658280`。因此v2_3.*处理路径与V3_2不能因含数字3而合并。

上面证明native内部枚举与握手对象的关联。外层 `encrypt_protocol_version=3` 如何构造该枚举，还应追真实构造者，不能把“版本3”和“discriminant3”数值相同当作独立证明。官方Java版本3选择 `BleEncryption3Protocol2/FullConnect` 是另一条已知证据。开源gen3、MAX G3车型名、官方V3Auth和native枚举名称应继续分别表述。

## 5. 既有文档应修正的两处

1. 20261009研究第13.2节 `0x383b58 → 0x374040 → 0x5a5ff4 → 0x37d4bc` 的成功数据流不成立：`0x374040` 位于无效hex错误分支；成功分支把原文本直接传给 `0x37d4bc`。原函数275用例和真实分支指令一致。该节关于helper返回值进入存储的文字应被本轮结果取代。
2. 同文第7.3、13.1的V3_2字符串及v2_3云路径线索可以保留为历史定位来源，但不能合成一套已经验证的V3_2状态机。新报告的vtable证据能准确标记V2_3。新hex执行结果可更新“hex32到bytes16未确认”；第7节进一步确认了6005挑战的nonce/tag/AAD和生成结果。认证后通用数据帧、云签名/header和无线报文向量仍未闭合。

## 6. 与投屏测试工具的界线

独立工具可以直接实现已确认的Gen2会话、type1配置MD5校验、匹配模式回复和媒体承载。Gen3如果只实现上述本地key管理及选槽，还不能标记为认证通过，也不能直接给未验证的challenge填常量后发车测试。接收GATT成功、SDK注册回调、存储helper返回成功、模式回复与仪表显示都应保留各自状态。

媒体packetizer是否附加加密是单独证据问题；即使媒体函数不调用加密，也不能逆推控制会话或车辆接入无需认证。最终发包顺序仍需用户授权车辆上的HCI会话核对，并以真实匹配响应及仪表画面验收。

运行：`python -X utf8 artifacts/ninebot-connection-repro-20261115/official-connect/cross_validate_gen3.py`。冻结ELF SHA-256为 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`。

## 7. 6005 fast-auth 生成器与TLV续审

### 7.1 原算法与hook边界

`gen3-auth/emulate_fast_auth.py` 的47例含20个原 `0x37cd04` 加密向量、12个完整 `0x383df8` 生成器向量，以及15个原 `0x37ce98` parser向量。加密结果与独立 `cryptography.AESCCM(tag_length=16)` 比较；输入含空/非空AAD、0/1/6/15/16/17/31/32/33/255字节明文，生成器覆盖秒值的32位截断和两种nonce。AES/key schedule、CCM运算和实际拼接均执行冻结ELF的原指令，未被Python参考算法hook替代。

运行时替代了allocation/reallocation、memcpy/memset与drop；完整生成器另替代时钟返回和随机对象。TLV向量把map插入替换成记录hook，不能由这15例直接宣称最终map的重复键结果。脚本准确披露了这些边界。此轮加密仅验证发送生成，不覆盖6006解密/tag拒绝、认证后数据加密或完整会话。

### 7.2 可确认的6005 payload

`0x302fa4` 取得当前会话材料，`0x302fb0` 调 `0x383df8`；`0x30305c–0x303064` 使用字段0x60、5构造发送。原生成器与独立算法一致：

```text
plaintext = 0C 04 || LE32(unix_seconds & 0xFFFFFFFF)      # 6 bytes
blob      = AES-128-CCM(key16, nonce8, plaintext, AAD="", tag16)
payload   = 13 16 || blob(ciphertext6 || tag16) || 02 08 || nonce8
```

总payload为34字节，两个短TLV的顺序为tag0x13、tag0x02，长度分别0x16、0x08。`0x383f60` 传nonce值、`0x383f64/68` 传空AAD长度0，`0x384048/98` 将同一nonce原样存储/复制到第二个TLV。该结果是消息参数payload；外层目标/source/frame封装、该阶段是否另经过会话帧转换及可写GATT完整字节仍须沿发送链核对。12个生成器用例并未运行真实key getter或整套6005发送future。

### 7.3 时间与随机来源的独立静态核对

`0x617830` 先令w0=0再转 `0x612f20`，后者在 `0x612f34` 调导入的 `clock_gettime`。Android/POSIX中clock id0为CLOCK_REALTIME。`0x383e38/3c` 给duration helper的基点为零；`0x6177e8 → 0x612e3c` 作时间差，`0x383e50` 只保存返回秒数低32位，4字节按ARM64小端直接写入明文。Unix秒的解释有真实来源证据，不依赖测试变量名。47例替代了时钟读入，未测试实际系统时钟错误、负时间或车辆允许的时间偏差。

`0x59a148 → 0x599a08` 取得随机对象，生成器根据对象+0x150的buffer索引，从+0x10读取64位值，必要时调用 `0x5975b4` 补充buffer。测试hook注入一个已有buffer并把索引设0，所以验证了8字节nonce的消费与传递，没有执行随机源初始化、buffer刷新或熵来源；不能把两种合成nonce当作随机质量测试，也不能据此指定独立客户端的PRNG算法。

独立提取的精确指令和导入符号见本地复跑文件 `raw/fast-auth-source-boundaries.asm.txt`；该raw目录受忽略，不应成为唯一可移植证据。上述地址均可从同一冻结ELF重新反汇编。

### 7.4 TLV端序、重复与截断

`0x37cf24–30` 对type逐字节左移8位拼入，确认多字节type为大端；`0x37cf68–74` 按8、16位递增移位拼length，确认这里测试的两字节length为小端。15例核对了(1,1)和(2,2)两种参数；不能推广到任意无界width。

这项“长TLV”是通用parser的能力。已定位认证调用 `0x2e5480/0x2e56f8/0x2f2d30/0x2f5d40/0x2e71c8` 均显式传type_width=1、length_width=1；6005生成器也明确写短TLV。独立发包不能把通用长TLV测试解释为本认证流程应改用2字节头。

header不足或value声明长度超出剩余字节时，parser在 `0x37cf00/90` 转正常返回，只保留此前条目，不尝试重新同步。这种宽松行为不等于收到的认证回复已完整、合法；上层仍需要校验所需tag和具体值长度。

最初15例中的重复tag只观察到两次待插入。独立继续复核 `0x355fb8`：命中旧键路径 `0x3560e0–10c` 将新vec写回bucket，返回旧vec；parser丢弃旧值。为消除map hook影响，补充脚本执行了原parser和原HashMap插入，仅保留固定hashseed与内存运行时hook，7例全部通过：后条覆盖、后条空值、穿插不同tag、有效重复后截断、只有重复header、双字节type/length重复，以及损坏尾部不重新同步。最终map中同tag仅一项，值为最后一条完整TLV；不完整的后条不会覆盖已有值。

运行补充审阅：`python -X utf8 artifacts/ninebot-connection-repro-20261115/official-connect/cross_validate_fast_auth.py`。这7例补充的是原map插入语义；不能和复跑的47例重复累计，也不能写成54次认证。
