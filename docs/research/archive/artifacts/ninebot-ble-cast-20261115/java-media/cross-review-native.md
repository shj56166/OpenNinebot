# Java媒体代理对 native RTP 与原指令仿真的交叉审查

日期：2026-11-15。审阅 `../native-rtp/findings.md`、root的 `../control-config/emulate_rtp_forward.py` 与结果JSON，并对照 `../ble-security/notes.md` 的后段writer/JNI/GATT证据。未改动这些文件或主文档。

## 1. 交叉结论

没有发现需要推翻native报告的矛盾。此次补齐了前轮第8节“concrete poll未定位”的真实缺口，不能继续用上轮的未知状态描述已经恢复的writeRtpData分支。

复核使用冻结ARM64 `librust_lib.so`，SHA-256 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`，与Java证据同一个官方APK。独立读取ELF `.text` 的原字节，用Capstone复核以下地址；原指令及其范围哈希保存在 `cross-review-native-validation.json`。

| 原指令位置 | 独立核对 |
| --- | --- |
| 0x2579b8..0x257a2c | JNI调度处把已捕获future交给0x27e7e8，并保存/返回w0；不是凭RtpHandler名称猜测消费者 |
| 0x27e518..0x27e568 | LocalPool wrapper调用0x26bfec并返回其bool |
| 0x26c05c..0x26c094 | 原client/ptr/len进入concrete future的起始字段，0x26c08c直接BL 0x2eccd8 |
| 0x26c094..0x26c134 | Pending -2进入等待/重试；Ready结果与-1比较，cset w0,eq形成返回bool |
| 0x2ecd18..0x2ece00 | 原指针/长度保存到self+0x20/+0x28；lookup成功后0x2ecdd0以ldp x2,x3恢复，w4=1，0x2ecddc直接BL 0x5cdaf0 |
| 0x328b70..0x328ba8 | 构造参数and0xffff，头大小12/8，index/SSRC全0，0x0120小端写入→PT32/standard1 |

后段boxed writer的vtable必须按RELA还原：0x679010的poll slot+0x18对应0x5bfbc8，后经0x5b2450转换原slice为Java byte[]、0x5c1684调用Peripheral.write。安全代理已经把native discriminant1→Android writeType1的映射和Java特征写路径分开核对；RTP入口不是“with response=true”。

## 2. 与Java包格式相互验证

Java原类验证得到了PT0、20字节头及原payload。native当前RTP poll不读包头、不更新sequence、不根据MTU再分片，传给writer的是同一个输入slice。因此没有依据把handler默认PT32或其8字节specific header再加到Java包上。

这个结论只限闭合的 `BleSdkClient.writeRtpData→RustBridge→0x2eccd8→writer`。它不能证明RtpHandler在SDK其他API中永远不用，其运行时消费者仍是另一个问题。RTCP native有不同调度入口，本次RTP结论不能未经追踪直接复制过去。

Java `BleSdkClient.writeRtpData`的非空、当前设备MAC门控与native任意长度边界测试也自洽：0字节能直接在native函数测试中执行，不表示正常Java入口接受空媒体包。4096字节测试同样只用于函数边界，不证明GATT/仪表接受超MTU包。

MTU须按实际getter语义交叉：`BleSdkClient.getMtu`对native值>20减3，旧`BleConnector1`也对成功ATT回调减3。安全代理已追到native正常协商存储/读取原始ATT值的路径。因此正常原始ATT `A=247`时getter `M=244`，Java媒体`M-27=217`、RTP`M-7=237`，距attribute value容量244留7字节。Java离线测试直接给`M`赋值，其`M=247`不是`A=247`。初始化、异常、未协商值不能套正常等式，余量不能凭空解释为媒体加密头。

## 3. root 14组与7组原指令测试的限制

已通过 `review_native.py` 独立重跑root现有14组转发和7组初始化，全部通过，结果写入本目录 `cross-review-native-validation.json`。没有修改父级结果，也没有把这21组重复重跑加到任何新增覆盖数量里。

具体核对过测试的断言强度与边界：

- 原0x2eccd8指令从冻结ELF映射进Unicorn，未重写；特征查询0x2ed394和上下文克隆0x325a60用环境桩替代。
- 在0x5cdaf0入口截获并停止；断言原指针、长度、全部字节、characteristic、写类型不变，并通过内存写hook确认输入区未写入。
- 13种长度均走lookup Ready路径；额外1组只覆盖 **characteristic lookup Pending→Resume**。它不覆盖boxed writer自身Pending/error、异常清理、完整JNI、认证、GATT队列、空口或仪表。
- 7组只执行直线初始化基本块，验证低16位截断和默认字段。它没有验证分配器、完整NBBle构造、20/512参数的产品含义、RtpHandler的封包消费者或与实际协商MTU的关系。

这些限制已在root与native文档中基本如实披露。静态后段writer/JNI/Peripheral链补足“截获点之后为什么仍是同一byte[]”的证据，但不是把局部仿真升级为端到端执行。

## 4. 不把“无逐包媒体AES”写成“无需认证”

没有在恢复的媒体透传路径中发现另加车辆AES密文或认证外壳；这支持自行构造MPEG2/RTP字节。它不说明原生连接没有crypto，更不说明一台新手机可跳过车辆准备。

Java业务入口仍检查已准备的连接，底层writer只做非空/MAC检查，两者是不同层次；车辆开投屏命令、Gen2/Gen3握手、账户凭据和GATT权限要分别判断。BLE配对/链路加密亦属于控制器/系统层，不能从App传明文字节推出无线空口明文。

RTP JNI会同步等本地writer Future，不是仅enqueue。独立核对的原指令表明Pending后等待重试，Ready将-1映射bool并原路返回Java；顺序媒体发送因此有本地完成背压。这个静态返回值追踪没有在JVM/GATT运行，也不属于14+7局部仿真的新覆盖。RTCP JNI使用另一spawn入口，不能照搬RTP同步语义。

JNI布尔true、native future完成或onCharacteristicWrite都不能作为仪表显示成功。安全报告记录了Peripheral回调未检查GATT status以及WRITE_NO_RESPONSE的限制，和本代理验证出的“Java失败仍计数/断连返回true”相互印证。

## 5. 主文档抽查反馈

已审主文档第4/5/6/11/13节，编码参数、帧类型两次映射、PT0、getter `M-27`、失败尝试及585/14/7的覆盖范围与原证据一致；MTU记法进一步按上文校正为原始ATT `A`与getter `M`。已向root反馈两点编辑建议：

1. 自有View可不使用Projection，但官方有Projection时也把同App导航View放入VirtualDisplay/Presentation；避免写成Projection只用于投其他App。
2. 当前Manager/Builder显式sendRtcp=false，与NBBleSender独立缺省构造true分开说明；开启true也不代表当前BLE心跳index/timeout回调已完整接通。

物理队列容量30、clear不清keyCount、直接sender.stop与高层onStop的差别以及无法立即取消在途帧，已通知root纳入其最终稿。没有改动主文档。
