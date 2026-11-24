# 独立测试工具交叉复核

日期：2026-11-15。范围为 `tools/ninebot-ble-test` 的 HCI 分析脚本、Android GATT 生命周期、Activity 阶段入口和 MPEG2 片段导入。本次没有重复执行已有密码学测试，也没有使用手机、车辆或真实 HCI。

## 1. HCI 原实现反例与修复复验

以实际导入的 `scripts/analyze-hci.py` 执行3个独立合成反例，工具为 [review_hci_boundaries.py](review_hci_boundaries.py)，不是另写一份 decoder。

初稿哈希 `6353238575bb10cfef16f5cfbbefd11211134e9ba15470115ed91cbd694daf78` 的3个问题均已复现并向根代理反馈：

| 反例 | 初稿实际行为 | 应有证据边界 |
| --- | --- | --- |
| 已完成2803特征发现，随后另一请求被截断，再收到0x09响应 | 旧请求类型残留，把没有对应完整请求的响应解析为handle9→UUID1234 | 截断后不建立这条映射 |
| 已完成MTU交换，下一请求被截断，再收到MTU响应 | 使用旧请求再次标记actualAttMtu185 | 无完整请求配对时不宣称协商结果 |
| ACL半包之后出现头中长度不一致的ACL记录，再收到continuation | 拒绝中间记录却保留旧半包，仍拼成一个ATT | 数据缺口后不能继续拼接该半包 |

根代理将 ATT 上下文改为一次性的 pending request，正常响应/错误后消费；截断、drop计数变化、非法ACL/L2CAP使未决请求与半包失效，保留已证实的静态GATT表。

**独立复跑修后3个反例全部通过，0 failures。** 首次修后源码哈希为 `ebb5bd16966dca447bb875b276702eb155d7f60ea1d20ba5e9bdef08da6a0e51`；随后加入广播解析的最终源码再次通过同3个反例，最终哈希与结果见 [hci-review-validation.json](hci-review-validation.json)。根代理再次运行相同反例不增加覆盖数。

静态检查还确认：ACL按handle和方向独立重组；断开与已识别LE连接事件使handle进入新代际；候选媒体包与已发现的UUID映射分开记录；没有根据密文、发送完成或RTP形状自动宣称认证/显示成功。

最终版在原有 H4 btsnoop、ACL/L2CAP、固定CID0x0004的ATT上，加入 LE Advertising Report(sub02) 与 LE Extended Advertising Report(sub0d) 广播观测。原6个HCI单测与新增12个单测共18个全部通过，见 [hci-advertising-validation.json](hci-advertising-validation.json)。

广播输出为 `event=advertisement`，保留原始AD、地址与地址类型、RSSI和扩展报告的PHY/SID等；三种manufacturer封装按与官方5个合成向量相同的原始ID字节序、校验和、TLV边界检查，仅给出 `authVersionHint`。它不是车型识别、凭据校验或认证成功。每批报告先完整校验再输出；缺失报告、HCI事件参数长度不符、报告内长度错误不会留下前半批的有效提示。

Extended dataStatus不是complete时，保留raw和status并始终输出unknown，不拼接跨报告片段。匿名扩展广播地址输出null并保留原始地址槽字节；不同隐私地址不合并身份。标准、enhanced及enhanced v2连接完成事件保留controller报告的peer地址、地址类型与RPA原始字节，handle再次连接建立新代际，亦不据此自动关联广播身份。

EATT、缓存GATT造成的缺失发现、未知句柄、应用层认证密文均不能由这里补成已验证。广播报告被系统过滤掉时也不能从车辆年份或静态车型目录补出认证版本。

## 2. Android 异步与停止边界

此前向根代理反馈的 CCCD settle 窗口重复认证竞态，已静态核实修复：`subscriptionPending` 保持到真正创建Session之前，延迟任务检查当前generation、pending状态和Session空值。

本次发现另一处同GATT内播放重启竞态：一帧完成后已安排下一帧定时器，停止并重新开启模式、重新播放时，旧定时器只检查连接generation；同一连接下generation不变，旧任务会推进新片段并叠加调度。1fps导入片段可将窗口放大到1秒。

根代理已添加独立 `mediaToken`：开始每次播放、停止和断开均更新；帧完成及下一帧定时器同时检查连接generation和本次播放token；发新帧前还要求没有pending或排队写入。静态复核确认旧播放任务不能再推进新会话。**没有在真实Android GATT上动态复现本条修复，不能把静态审查写成实车播放测试。**

其他已核到的边界：

- 单actor串行处理，只有一个pending GATT写；非零提交结果、回调status和写超时使会话失败并清理。
- 旧GATT对象回调被identity检查拒绝，断开使generation和stageToken失效。
- 成功服务枚举使用独立 `servicesDiscovered` 状态，无NUS服务不再被15秒定时器误记成“连接/发现超时”。缺少NUS时不能开启Gen2会话。
- 重复认证输入错误只拒绝该请求，保留已存在连接。
- Session先回到authenticated再交出完整MD5验证文件；host解析UTF-8/JSON失败后关闭Session，不会被后续Session状态覆盖。
- 模式ACK只是匹配板/命令/索引的响应；媒体本地GATT写完成亦不代表仪表显示。

## 3. Activity、广播与片段入口

Activity在连接时固定所选车型，之后移动车型选择器不会改写当前连接的activeModel。投屏配置/模式/媒体入口要求当前车型存在已解码命令表。未知车型可扫描和枚举；广播认证版本3或未知不能进入Gen2 Session。以上是探针范围限制，不能反推未知车型“不支持”。

`NinebotAdvertisement` 校验三种Manufacturer封装的校验和、长度、TLV边界和版本冲突；版本证据与用户选择的车型分别保存。它验证的是广播封装和认证版本字段，不是实际车辆型号、所有固件兼容性或用户身份认证。

`Mpeg2ProbeClip` 以4MiB输入、100包、单包65536字节限制导入，要求codec、尺寸/FPS范围、picture类型与key字段一致，首包含匹配尺寸的sequence及I picture。此类执行**结构和头字段检查**，不包含MPEG2完整解码器；标准FFmpeg生成的片段与任意手写JSON的证据等级不同。媒体发送另受配置承载/编码/尺寸、模式ACK、媒体特征、MTU及512片段队列上限约束。

没有发现以上检查范围内尚未修复、应阻止构建测试工具的缺陷。目标手机的GATT回调习惯、实际车辆密码/认证、配置和显示仍需实测。
