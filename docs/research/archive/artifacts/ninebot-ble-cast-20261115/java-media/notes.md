# 半彩 BLE 投屏：Java 编码、RTP 与发送状态机

日期：2026-11-15。范围为已冻结官方 6.10.11 APK 的 Java/DEX 路径，不连接车辆、不运行 Android App，不修改产品代码。此前第8节已经建立配置/通道/基本RTP结构；本文补齐实际编码器、帧编号转换、队列行为、心跳回调缺口与停止边界。

**适用性规则：本文确认的是这份APK中选中相应配置和入口时的行为，不是全部九号车型的承诺。**“半彩=MPEG2、240×320、BLE”仍需目标配置证实；通道与codec分别选取，代码没有把所有BLE目标强制改为MPEG2。接收端解码、吞吐、固件兼容性均未实测。

## 证据与复跑

- 主输入：上级 `raw/official-6.10.11.apk`，SHA-256 `02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`；`evidence.json` 记录classes12/14/20/21/22.dex、每个目标方法原DEX指令、源快照和转换jar的SHA-256。`dex-instructions/` 保存原DEX反汇编。方法哈希针对原指令字节，不是JADX文本。
- 第三方完整反编译源及已转换jar保留在本目录忽略的 `raw/`；下文类名均对应 `raw/sources/<完整类名>.java`，给出的行号是这些冻结文本的行号。编译工具为Android Studio JBR；原方法转换工具是dex2jar 2.4。
- `pwsh -NoLogo -NoProfile -File run-validation.ps1`：原 `NormalSendQueue/Frame`、`BluetoothRtpSender.send`、`ByteUtil.splitArray`，9组队列、60组分片，570条断言通过。ASM去掉无关初始化/接口；仅保留发送方法的Bluetooth类改用Object父类后Unsafe分配，保留方法体未重写。Android Log/Kotlin空值检查以及下游会话为环境桩。测试变量`mtu`的128/185/247/517均为`device.getMtu()`返回值`M`的直接输入，未执行ATT协商，也不能把测试中`M=247`理解成原始ATT MTU为247。它证明分片参数和队列局部行为，不是Android编码或网络验证。
- `pwsh -NoLogo -NoProfile -File run-session-validation.ps1`：**未裁剪的原classes12转换jar**中的 `BleRTPSession/BaseRtpSession/RTP包编码`，10组、15条断言通过。使用原null-sender构造避免RTCP线程，再设置离线IBleSender；未启动Android或BLE。见 `session-validation.json`。两套共585条断言，未将重复复跑算作新增覆盖。
- 两个复跑入口从本目录 `raw/original-classes20.jar`、`raw/original-classes12.jar`、`raw/asm-9.5.jar` 读取，不依赖TEMP。`collect_evidence.py`从冻结APK重取DEX；可选的`extract_sources.ps1`从冻结APK提取classes14，仍需调用者机器上的JADX，默认路径已写明。

关键原方法身份（完整签名/其余方法见evidence.json）：

| 原方法 | 原DEX指令SHA-256 |
| --- | --- |
| VideoFrameHelper.getFrameType(AVPacket) | `5be1cc198d6e0e752ec1242cf95755ad4cce86408cda506957861c7802fd8cd8` |
| BluetoothRtpSender.send(byte[],int) | `38a6ceefdd2697ac55aff96e8c6c50c9ce242a9e8367b6abcd8bd8f62d1cc330` |
| NormalSendQueue.abandonData() | `6e92cb6e8d9234b4c32056e6f8465f397b2a4408aef6e1f73b9cbab40068b5a4` |
| NormalSendQueue.clearBuffer() | `8096fda682c84101b951414be547ac940145251bb4993eeec016be480efa31ae` |
| BleRTPSession.send(byte[],boolean) | `9d4c5130427dcaed89a44e072d4dee858da64b6d78dfe4780b5cc35be01210ec` |
| BleRTPSession.sendData(byte[][],...) | `f06ec5da8646e96200b52438ca135e4d8435b178e2a49b09891372b120eb7b4e` |
| BleRTPSession.getNextSeqNum() | `2612ece3c80d4f87c6f7571e72485b12462ff8b90fd4464cf7412358eefde501` |

`conversion-origin.json`另验证当时送入dex2jar的classes12/20与冻结APK中的DEX逐字节相同，其SHA分别为 `beb5cb710b62e81604abda96c39a58713cb72375551f6fed6154672bfcbd2440`、`3737b8214c096af0113a0bbf6c159baf0cd188d6bf628338365bb816feabea56`。

## 1. 真实入口与启动顺序

`DeviceScreenCastManager.prepare` 的协程先读投屏配置，取通道，BLE分支不连接Wi-Fi；随后 `ScreenCastDataSender(device,false)` 并create/prepare（Manager文本247、291）。`DeviceScreenCastRequest.ScreenCastRequestBuilder.build` 独立入口也在165显式传false。它们复用已准备设备；车辆控制认证和开投屏前提由控制研究另述。

`ScreenCastDataSender.create:145..183` 根据BLE/WIFI/WIFI_KCP创建对应sender，创建前stop旧sender并清其listener；BLE创建 `NBBluetoothRtpSender(device,sendRtcp)`。上层builder给sender挂进 `ScreenCastRequest`，后者挂StatusListener。

`ScreenCastRequest.videoConfig:528..536` 按配置选择尺寸/码率/GOP/B帧/帧率和codec，未填format/videoCodec/pixelFormat/forceLowDelay/videoOption，scaleBitmap=false。**实际MPEG2链**：

```text
CaptureClient.createCapture (487..499)
  → createMpeg2Capture → CaptureService.createMpeg2Capture (89..95)
  → Mpeg2CaptureController.EncoderCreator (24..48)
     有MediaProjection：FFmpegImageReaderEncoder → FFmpegBitmapEncoder
     无MediaProjection：FFmpegViewEncoder → FFmpegBitmapEncoder
```

`AbstractCaptureController:69..88` 根据实际MediaProjection是否存在分流；Surface分支建VirtualDisplay和Presentation，另一分支用ViewEncoder。后者经 `ViewToBitmapConvert` 取得自有View的Bitmap；前者ImageReader为RGBA8888、maxImages5，acquireLatestImage，处理rowStride裁剪，按scaleBitmap选择缩放或复制（ImageReaderEncoder:133..222）。这不是所有场景都要系统录屏授权的证明，也不是完整手机镜像产品验收。

FFmpegBitmapEncoder构造立即init/start recorder；controller.start→LoopBitmapEncoder.start同步通知stateListener.onStart→ScreenCastRequest启动sender→编码线程开始取Bitmap。输出经EncodeListener进入sender.onData队列，另一个发送线程取帧拆成RTP。RTCP线程更早在BluetoothRtpSender构造注册会话时启动，不能假定一定等待首个画面后才有控制活动。

## 2. 当前编码参数与旧类区别

`FFmpegBitmapEncoder` 是当前入口的真实实现；库内旧 `Mpeg2Encoder` 仍存在，但Mpeg2CaptureController未创建它。

| 项目 | 当前FFmpegBitmapEncoder | 旧Mpeg2Encoder（不能套到当前入口） |
| --- | --- | --- |
| 分辨率 | VideoConfig.width/height；高层缺省240×320 | 同样取config |
| codec | MPEG2分支config.videoCodec或2（MPEG2VIDEO） | 同样或2 |
| 像素格式 | config.pixelFormat或0（YUV420P） | 同样或0 |
| 帧率 | config.frameRate转int或10 | config或10 |
| 码率 | config.videoBitrate或2,000,000 | config或200,000 |
| GOP/B帧 | config.gopSize或5；maxBFrames或0 | 同样 |
| low delay | config.forceLowDelay或true | 同样 |
| options | preset=ultrafast、tune=zerolatency；自定义项可覆盖；最后bitrate_mode=cbr | 前两项及自定义项；未见最后cbr设置 |
| format与输出目标 | 不setFormat；文件目标test.mpg | config.format或matroska；ByteArrayOutputStream目标 |
| 自适应码率上下限 | 初始min1,000,000/max2,000,000 | 初始min1,000/max200,000 |

证据：当前类构造35..52、init69..149、restart160..224；旧类构造60..103、startRecord190..252。这些是请求参数及fallback，不证明某台仪表采用它们，也不保证FFmpeg接受或实际采用所有option。

**不能因文件名或旧类matroska就认定BLE负载是PS/TS/Matroska。** `NbFFmpegFrameRecorder.writePacket:1640..1647` 对视频i==0直接把AVPacket.data按size复制为byte[]，回调后unref；这里没有调用muxer把PS/TS包送入listener。当前MPEG2回调直接保留这些字节。因此已闭合的是“原始视频AVPacket负载→RTP”，不是已执行验证的PS流。是否每个输出包带完整sequence header、具体GOP与码流兼容性仍须运行实际编码器确认。

LoopBitmapEncoder.getMInterval为整数1000/fps（91..92）。每轮记录nanoTime，取Bitmap/编码，剩余预算>0才awaitNanos；超时则直接下一轮。它不是固定每包2ms节流，也没有对外部fps<=0进行完整校验。ImageReader暂无新图时可继续返回上次mRecordBitmap，未看到内容去重判断。

## 3. 帧类型经过两次转换

不要将sender参数2直接解释成AVPictureType2。完整证据链如下：

| 阶段 | 原始判定/转换 | 语义边界 |
| --- | --- | --- |
| VideoFrameHelper.getFrameType:18..23 | AVPacket.flags&1非零→1；否则flags&4非零→3；否则2 | 读取packet flags，不调用pict_type；不要把这里的2/3直接标为P/B帧 |
| NbFFmpegFrameRecorder.writePacket:1645 | 上述值与AVPacket字节一起回调 | 原编码回调编号 |
| FFmpegBitmapEncoder.init内部listener:111..146 | MPEG2分支原样传frameType/bytes | H264另有SPS/PPS处理，不适用于MPEG2 |
| ScreenCastRequest.setListener:424..436；创建监听器290..300 | callback1→sender.onData(data,2)，所有其他→3 | 这里2是队列的关键帧分类，3是非关键帧分类 |
| FrameSender.onData | 包装Frame(data,packetType1,frameType) | 不增加编码或加密 |
| BluetoothRtpSender.send | sender frameType==2→扩展pictureType1；否则2 | 输出RTP的picture type字段 |

MPEG2高层正常流程只产生队列类型2/3；队列对类型1的兜底规则不代表正常编码器会送“header frame=1”。RTP示例中任意媒体字节也不证明它们是可解码MPEG2。

## 4. 队列与帧流控的真实含义

`NormalSendQueue:36..43` 初始物理ArrayBlockingQueue容量30且fair=true。NBBluetoothRtpSender构造设置fullQueueCount=3、dropFrameThreshold=2，却**不重建物理队列**；`isNeedScanQueue=false`关闭ScanThread。fullQueueCount是put时abandonData的门槛（当前帧数>=3），threshold只影响ScanThread每300ms取样/10次统计的good/bad判断，此BLE子类没有启用它。

putFrame先对传入关键帧增加keyCount，再abandonData，最后put新帧和增加总数。删除逻辑在遇到类型3后删除非关键帧，并按keyCount决定是否越过/删除后面的关键帧；若没删到类型3，再尝试删一个2，再尝试一个1。它不是简单只保留最后两帧，也不构成可靠传输。

原方法样例，括号是输入顺序中的id：

| 输入队列类型（连续put，无消费者） | 最终保留 | 删除数 |
| --- | --- | --- |
| 2(0),3(1),3(2),3(3) | 2(0),3(3) | 2 |
| 3(0),3(1),2(2),3(3) | 2(2),3(3) | 2 |
| 3(0),2(1),3(2),2(3) | 2(3) | 3 |
| 2(0),2(1),2(2),2(3) | 2(1),2(2),2(3) | 1 |

`clearBuffer:205..212`清队列与in/out/total/giveUp计数，**不清mKeyFrameCount**；已用原方法确认。`takeFrame`阻塞取队头，并相应减key/total。不能据这种丢帧策略证明接收端在缺失参考帧后仍可连续解码；本轮没有运行MPEG2解码器或仪表验证恢复。

## 5. BLE Java RTP与提交/重试

设`M=device.getMtu()`返回值，NBBluetoothRtpSender将maxLength设为`M-27`；BluetoothRtpSender按此媒体长度拆分，最后一片marker=true，同一帧System.currentTimeMillis时间戳，CSRC和显式seq数组均null。BleRTPSession本地SSRC和初始seq随机生成；Java PT保持BaseRtpSession默认0。基础RTP及私有扩展总20字节，扩展profile0/words1，pictureType在扩展内容的第二字节，其余0；没有H264 FU-A/MPEG PS额外负载头。

**`M`不是原始ATT MTU。** `BleSdkClient.getMtu:1297..1304`先取native值，`>20`时减3；旧`BleConnector1`在成功`onMtuChanged`回调中也保存`ATT_MTU-3`（安全代理冻结源213..217）。安全代理进一步闭合了正常协商的native存储链：`Peripheral.onMtuChanged`返回原始回调值，经`Integer.intValue`与u16返回，在`0x2eddc4`保存到shared+0x308，`getMtu`的`0x32957c`读取该字段。因此正常协商原始ATT MTU为`A`时，`M=A-3`，媒体上限`A-30`，Java RTP包长上限`M-7=A-10`。例如`A=247→M=244→媒体217→RTP237`；`A=185→M=182→媒体155→RTP175`。这比attribute value上限`A-3`少**7字节**，没有证据把该余量解释为加密或私有头。

初始化/未协商/异常返回值不能套用上述正常协商等式；老`BleConnector`初始为20且未见该类协商更新。构造路径没有校验`M>27`。离线分片测试只覆盖给定getter输入下的局部算法；其`M=247`用例仍得媒体220/RTP240，但不是实际`A=247`的链路样例。MTU原指令与Android回调证据详见`../ble-security/notes.md`。

原完整classes12方法固定向量：payload=`000001657f`（只作任意字节），PT0，marker1，seq1234，timestamp01020304，SSRC11223344，pictureType1：

```text
90 80 12 34 01 02 03 04 11 22 33 44 00 00 00 01 00 01 00 00 00 00 01 65 7f
```

`BleRTPSession.send`最多20次**总尝试**；仅返回false且还剩次数时等2ms，成功包无此等待；InterruptedException只打印后继续。SDK的RTP JNI会同步等待writer Future的Ready结果后返回本地bool（详见第7节），所以一次尝试并非固定耗时或仅入队；成功包仍受该本地完成过程背压。BleRTPSession本身不向上返回交付结果。sendData不因某片20次失败中断后面的片，仍增加sentPktCount；sentOctetCount代码也是++，不是累加payload长度。原方法确认2片全失败调用40次，两个计数均2。不能用这些计数证明仪表收到了包。

另已原方法确认getNextSeqNum的`>65536`回绕导致在线低16位序列在边界呈65535,0,0,1。sendData自身不检查endSession标志，已开始发送的一帧不因上层stop立即取消剩余片/重试。

`FrameSender`先过滤空帧；直接调用BluetoothRtpSender.send(empty)会在marker数组[-1]失败。本轮验证明确测试了这个边界，没有声称所有直接调用都安全。

## 6. RTCP能力、当前禁发与高层超时分层

库的BLE RTCP心跳模式3/subtype5、默认interval1000ms/timeout5；thread初始休10ms，然后按delay构建SSRC1的APP心跳、先check timeout再发送。BleRTCPSession的公式是sendIndex-receiveIndex>timeout。这个“库有状态机”与“当前入口闭合保活”必须分开。

当前两个主入口显式sendRtcp=false，NBBleSender.sendRtcp立即true返回，**不写车**。NBBleSender独立使用的缺省构造为true；用库独立创建sender时能走写RTCP分支，但仍不能自动推得反馈和超时均接好。

进一步DEX核对：BluetoothRtpSender没有覆盖receiveHeartBeatData，继承FrameSender的return-void空方法；其构造也没有安装BleRTCPSession.socketConnectListener。收到APP subtype5可进入FrameSender.onAPPPktReceived，但心跳index交给空方法，并未传到session.receiveHeartBeatData。故这条当前BLE媒体Java链不能据库类存在就宣称“心跳超时停流”。APP subtype1/2/3/4以及5的mode回调仍可能分别触发链路质量、fps、bitrate、mode更新。

高层也审查过：`ScreenCastDataSender`转发StatusListener的heartbeat/timeout→`ScreenCastRequest`构造listener103..114→controller→`AbstractCaptureController:266..282`→encoder.stateListener→`DeviceScreenCastRequest`回调966..988或1154..1176。后者仅计数、清prompt或每5次提示`onWifiHeartBeatTimeout`，没有另起媒体心跳定时器。存在这些计数器不能修补上述BLE回调源缺口。

**这不是“车辆完全无超时/无BLE保活”的结论。**车辆连接状态、控制命令队列、认证会话及native管理可能另有机制，超出本媒体Java链；native路线以独立报告为准。所有车型适用性仍由实际通道/固件决定。

码率反馈由ScreenCastRequest在capturing时传给controller.restart。当前FFmpegBitmapEncoder.restart(bitRate,fps)对正码率只设下限1Mbps、不设max上限；restart(good)按/0.9或×0.9并限制min/max。于是外部配置原始码率低于1Mbps时，后续此反馈路径可能升到至少1Mbps；因为BLE扫描线程被关闭，不能将本地队列阈值2解释成必然自动调码率。

## 7. 旧GATT直写、SDK提交与停止

`NbBluetoothDevice.useBleSdk`由bleSdkDevice是否非空设置（380），不能只凭车型年份认定分流。getMtu在1213..1214也按此分支；SDK与旧`BleConnector1`的正常协商返回值均已扣除3字节ATT开销，见第5节。

- 旧路径writeRtp/writeRtcp（2261..2267、2252..2258）从fileService取RTP/RTCP特征，并调用bleConnector.writeCharacteristic(...,data,true)。RTP UUID为`6e400002-0000-0000-006e-696e65626f74`；RTCP写003，通知004。安全代理已复核旧`BleConnector:442..485`及`BleConnector1:635..683`：此true映射`setWriteType(1)`即WRITE_TYPE_NO_RESPONSE，返回值是本地GATT写启动bool，不是写响应或显示确认；超长值另有connector分片循环，正常`M-7`包长不触发它。
- SDK路径转BleSdkClient.writeRtpData/writeRtcpData；后者验证非空字节、当前已连接设备及MAC一致，再把同一byte[]传RustBridge，回调onSuccess/onError为空。**RTP与RTCP的JNI返回语义不同**：RTP在`0x26c094`检测Pending（-2）后等待/重试，Ready后`0x26c11c..0x26c124`将-1映射为bool，经`0x27e7e8`和`0x2579cc/0x257a18`返回Java，因而同步等待本地writer Future完成；RTCP由另一条spawn路径受理后返回。即便等到writer Future，也不能作远端显示确认：安全代理确认Android的`onCharacteristicWrite`没有检查status。RTCP写入分支还会按需订阅native通知；主入口false会在上游直接跳过它。
- NBBleSender构造同时注册旧CharacteristicListener和BleSdkRtpListener。004通知和SDK onRtspNotification回调交给RTCP解析；006/onTestNotification交给测速回调。NBBleSender.sendRtp在device.isConnected=false时返回true并不发送，这是避免上游重试的吞包分支，不能记录成成功传输。

停止分层：ScreenCastRequest state.onStop先clearQueen再sender.stop；NBBluetoothRtpSender.stop→FrameSender置isRunning=false→BleRTPSession.endSession→NBBleSender移除两种listener。因为isNeedScanQueue=false，**直接sender.stop本身不会调用队列stop/clear**，需区别高层onStop已先清队列的情况。阻塞take没有对应interrupt/sentinel/join；endSession对客户端null app/recv线程的interrupt位于捕获Exception的块中，先置结束标志，但不能因此承诺发送线程已经退出。

BLE端未见stop发送Wi-Fi式RTCP BYE；BleRTCPSenderThread结束后休200ms再退出。外部车辆setNaviStart(0)、断开GATT是另一层操作，不等于媒体stop自动完成。SDK native队列是否另有cancel机制以native证据为准。

本轮已追加审核native报告：RTP专用路径从JNI具体future到boxed writer，再到Android Peripheral.write按原ptr/len/bytes传递；writer的1映射WRITE_TYPE_NO_RESPONSE。默认PT32的RtpHandler未被用于该Java RTP路径。这个新证据支持媒体字节独立实现，不能被解释为车辆无需握手认证，也没有把RTP出口结论直接套到另一条RTCP native调度路径。详见本目录 `cross-review-native.md`。

编码stop方面：AbstractCaptureController释放Projection/VirtualDisplay/Presentation并停止encoder；FFmpegBitmapEncoder停止Loop后resume唤醒并stop recorder。当前ImageReaderEncoder.stop只委托bitmapEncoder，未显式close ImageReader或quit capture HandlerThread；旧Mpeg2Encoder.stop却显式close reader。这是可见资源清理差异，未做Android内存/线程实测，不写成已观察到的泄漏。

## 8. 已闭合与仍待验证

本轮新增闭合：实际MPEG2双画面源、当前编码参数、AVPacket到listener的边界、完整帧类型转换、BLE队列阈值/丢帧/清空状态、RTP原方法字节、提交失败及回绕行为、Java心跳回调范围、旧写入与SDK提交分流。每项都限定在当前APK对应分支。

尚未证明：真实FFmpeg MPEG2输出与仪表接受情况、所有车型尺寸/码率、实际MTU与连接参数、过载丢帧后的解码恢复、端到端显示时延、Android资源退出、native成功回调与真实GATT/车辆收包的时间关系。没有逐包媒体AES的证据也不能代替车辆认证分析；媒体提交与控制授权必须分别验收。
