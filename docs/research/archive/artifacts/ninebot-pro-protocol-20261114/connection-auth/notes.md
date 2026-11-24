# P2P、认证和配置分支复核

研究对象是 SHA-256 `bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f` 的 Ninebot Pro 0.6.1，未运行 Android App、未连接网络仪表。APK/Dex/JADX 快照及哈希见上级 `evidence-inventory.json` 和本地忽略目录 `raw/`。

## 1. 扫描、连接、身份和路由

证据定位：`raw/jadx/x32.java`、`hd1.java`、`l21.java`、`dd1.java`、`vc1.java`、`uc1.java`、`MirrorActivity.java`。

- `x32` 构造函数 trim 名称与 MAC，将 MAC 转小写；名称不得空，MAC 必须完整六段十六进制。扫描匹配 `x32.z` 对完整名称区分大小写、对完整 MAC 忽略大小写，没有前缀匹配。
- `l21.a(target,auto)` 正在投屏或连接中时不切换；连接同一已核对目标时不重建。开始新目标增加连接 generation `hd1.h`，清当前 session，对目标读取本地配置。
- 当前无组：用 `hd1.c` 请求 peers，最多查找 10 秒；首次触发 discovery，800 ms 后轮询 peers。发现所选目标后以 `WifiP2pConfig.deviceAddress`、`wps.setup=0`（WPS PBC）、`groupOwnerIntent=0` 连接。
- 已有组且是所选仪表：复用后重新验证。已有不同组且是自动重连：保留该组、报告手动处理；用户手动选择其他设备时，APK 会 removeGroup 再扫描，并非所有入口都保留外部组。
- 整体连接等待 30 秒，到时若仍 `j=connecting`，重置状态并 cancelConnect。requestGroupInfo/接口就绪等待在连接中按 500 ms 复试。
- `hd1.d` 拒绝手机为 GO、owner 为空、MAC 不匹配；MAC 匹配后，owner.deviceName 或 group.networkName 必须恰好等于所选完整名称。`g` 还检查 WifiP2pInfo.isGroupOwner=false、groupFormed=true，GO 地址为 IPv4。
- 查 group.interface 对应 NetworkInterface 的非回环 IPv4；找同接口名的 ConnectivityManager Network；形成 `gd1(target,iface,localIPv4,goIPv4,network?)`。不是硬编码 `192.168.49.1`，也不是从 BLE 读取 IP。
- `o31.c(port)` 创建未绑定 DatagramSocket，在有 Network 时 bindSocket，随后 bind(localIPv4,port)、connect(goIPv4,port)，SO_TIMEOUT=1 ms，发送缓冲请求 262144。5012/5013 各自绑定同号本地和远端端口。Network 可为空，此时仍依赖本地接口地址绑定。
- 代次号保护晚到回调；P2P 广播断开、通道断开、身份/路由不符会清连接，之前已连接投屏会话随 `l21.l(null)` 停止。未发现无限自动重连；MirrorActivity.k 仅启动时按最后成功核对的目标尝试一次。
- 设备名/MAC 相等是目标一致性核对，不是证书或密钥认证；不能称为密码学车辆身份认证。

## 2. 认证与独立性

- APK Manifest 有 Wi-Fi、附近 Wi-Fi/旧定位、摄像头和前台服务权限，没有 Bluetooth/Bluetooth Scan/Connect 权限。
- APK 包含三个不同 SO 名称、四种 ABI：`libandroidx.graphics.path.so`、`libimage_processing_util_jni.so`、`libsurface_util_jni.so`。可见 loadLibrary 指向 AndroidX 图形/相机库，未发现九号 `librust_lib.so`、`libnbenc_ffi.so`、`libnbcrypto.so` 或官方进程 Hook/动态 APK 加载路径。
- 可见投流路径使用 Wi-Fi Direct、Android 编码、Java 打包和 Java KCP，不包含 PRE_COMM/SET_PWD/AUTH、V3Auth、车辆云密钥请求或 BLE 开停命令。
- README 的外部前提是先在仪表或官方 App 开投屏。APK 未完成“车辆未开投屏 → 自己认证/开启”的流程，所以可得结论是媒体链路独立，不能推成全部车型都无需车辆认证或无需官方 App。
- 之前官方 App 研究中有 BLE 认证、投屏文件读取、setNaviStart、WIFI/WIFI_KCP/BLE 选择，这些能力在该 APK 中由手动预设和外部开启替代。BLE Gen2/Gen3 与媒体通道必须分层判断。

## 3. 本地配置与会话

`d02` 字段顺序为 width,height,fps,bitrate,rotation,scale,codec,jpegQuality,transport。允许宽高为 64..1920 的偶数且像素积<=1920×1080；fps=5..30；bitrate=250000..8000000；rotation=0/90/180/270；quality=30..95；三个枚举不能为空。

`d02.B` 兼容 6/8/9 字段：6 字段回落 H264/quality80/Direct；8 字段显式 codec/quality 但仍 Direct；9 字段再显式 transport。所以旧七寸配置只改尺寸不会切到 KCP。配置以目标 MAC 为本地索引，不等同于车辆授权凭据。JPEG+KCP 可存入配置，`F` 原样返回 KCP，但 `o31.a` 的 JPEG 早返回优先，实际发送器走 `b` 直接 UDP，不能仅看存储配置报告有效传输。

`l21.j:235..259` 还有设备级缺省分支：不存在配置或解析失败时，对一个 APK 内置的精确名称+MAC 测试目标选 `d02.E()`（JPEG），其余目标选 `d02.C()`（五寸 H264）。这只是硬编码目标特例，不是扫描自动识别仪表尺寸，也没有七寸自动能力发现。本文不重复该测试设备标识，原始位置可核验。

`l21.k=true` 默认启用 H264 关键帧重复，经过 `i21.e` 传到 `o31.d`；JPEG 不使用此设置。源枚举是 TEST/IMAGE/VIDEO/CAMERA/SCREEN/SELF_TEST/NONE。真实投流要求有核对后的 gd1；本机自测无 gd1、写本机媒体文件、约3秒结束，不构成仪表成功证据。切源先终止旧 generation，再排新 job；停止只发结束报文和释放媒体不等于断开 P2P，断开按钮另执行 cancelConnect/removeGroup。

## 4. 原方法离线验证

执行 `python run_validation.py`：34 条断言通过。运行的是 APK 经 dex2jar 的 `x32` 构造/matcher、`hd1.a/d` 和 `d02` 构造/解析/preset/level 原方法。覆盖扫描精确名称/MAC、GO角色、owner缺失/错目标、networkName后备、连接generation、6/8/9字段、参数边界、JPEG与KCP配置独立性。

测试提供 Android group/device 值对象，跳过 hd1 构造和 Android系统调用；o10/error helper以及枚举值桩化。原因包括 dex2jar 的 c02 静态初始化会试图实例化 java.lang.Enum；JVM stackmap兼容性还要求 `-Xverify:none`。这些限制已写进 validation.json，测试只能证明被执行方法的局部行为，不能证明 Android 系统 P2P 建组、编码器或实车会话。
