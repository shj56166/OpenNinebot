# BLE投屏控制前提与原ARM64转发验证

研究日期2026-11-15。原始APK为官方九号出行6.10.11，输入哈希见上级`input-inventory.json`；本轮没有连接车辆、发送BLE命令或请求云端凭据。

## 控制与配置

本轮重新读取`DeviceScreenCastManager.prepare`、其prepare协程、`DashNaviDataMessenger$Companion$start$2`、`startScreenCast$2`及`ScreenCastType`。投屏配置和进入仪表导航/投屏模式是控制层，不能用媒体特征已存在来替代。

- `DeviceScreenCastManager.prepare`先读取投屏配置，再按配置选择BLE或Wi-Fi；BLE无需P2P。默认构造`ScreenCastDataSender(device,false)`，因此其RTCP开关实际为false。
- 独立的`setNaviStart`控制payload按枚举为SimpleNavi=1、MapNavi=2、Cruise=3，停止为0。命令存在时发送单字节值并等待回调；回调只以`NbFrame != null`判断成功，不证明仪表有画面。命令不存在的分支直接返回true，不代表自动发现了一条可投屏协议。
- `startScreenCast`还按`startNaviEnableWhenPowerOff`、MotorDevice条件进入准备分支；不能把这一上层流程简化为对所有车型固定写同一个字节。原本的车辆状态/权限处理应保留。
- 既有已解码的Mmax2/N3/Mz车型表中，`setNaviStart`在tft板、cmd0x71/index0；该索引不能推广到其他车型或作为所有半彩屏的身份证明。
- 文件读取仍是既有type1配置流程：130开始、131数据、132校验，开始payload010800，8×128字节分组，MD5一致性校验。配置中的codecType=0/1/2对应MPEG2/H264/MJPEG，rtpChannel优先于channel，不能仅凭“半彩”决定编码、认证代际与UUID。

## 原ARM64函数的局部动态验证

上次研究未把JNI调度的future接到具体消费者。本轮由native代理定位`0x26bfec`在`0x26c08c`调用`0x2eccd8`，该函数使用专用RTP服务/特征并将输入slice传给`0x5cdaf0`。主代理另用Unicorn执行原ELF的`0x2eccd8`指令进行独立复核，工具为[emulate_rtp_forward.py](emulate_rtp_forward.py)，结果为[native-forward-validation.json](native-forward-validation.json)。

| 项 | 验证内容 |
| --- | --- |
| 原函数/库 | `0x2eccd8`；`librust_lib.so` SHA-256 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188` |
| 普通输入 | 0/1/19/20/21/64/128/220/240/244/512/1024/4096字节，共13组 |
| 异步输入 | 另1组先返回Pending再Ready，核对状态3、等待期间未调用writer、恢复后只转发原输入 |
| 路由 | 原指令读取service `6e400001-0000-0000-006e-696e65626f74`，characteristic `6e400002-0000-0000-006e-696e65626f74` |
| writer参数 | 在`0x5cdaf0`入口核对x1=查询所得characteristic，x2=原输入指针，x3=原长度，w4=1 |
| 媒体 | 输入区域无写入；writer收到的全部字节与输入相同，包括无法作为有效RTP解析的合成数据 |

14组均通过。0字节仅用于测内部future是否按长度转发；Java公开入口会拒绝空数据，不应将内部边界测试解释为App支持空RTP。

另执行原`0x328b70..0x328ba8`直线初始化基本块，7组参数验证了默认`RtpHandler`的数据布局：payload_size取参数低16位，header=12、specific header=8、index=0、SSRC=0、PT=32、standard=true。它只确认构造默认值，未执行整个NBBle构造，也不证明Java媒体包经过该对象处理；实际BLE Java会话的PT由另一组原类测试确认，不能拿此处32替代。

hook仅替代`0x2ed394`的特征查找结果和`0x325a60`的环境配置克隆；在writer入口截获并停止，不执行Android、JNI字节数组生成、下游GATT或无线通信。目标函数的机器指令没有改写。配置克隆桩没有读取媒体区域，不以它代替对原加密函数的测试。

此实验能证明受测路径在这两个native边界之间保持指针、长度和媒体字节；整个SDK到Android出口的闭合需结合BLE安全代理对writer future及`Peripheral.write`的独立静态证据，不能将局部仿真扩大为完整App或车端运行成功。
