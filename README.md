# OpenNinebot

非官方 Ninebot / Segway 仪表投屏工具，支持独立连接车辆、发起投屏和读取车辆数据。Android 7.0+，目前发行 arm64-v8a。

## 可以做什么

- **免九号官方 App 连接与投屏**：导入车辆资料后，由 OpenNinebot 完成蓝牙连接、认证和投屏开启流程。
- **自定义投屏信息**：通过手机屏幕镜像，将自选页面、导航或数据界面投到仪表上。
- **连接并查看车辆数据**：读取车辆上报的功率、实时车速、电量等数据，展示常规界面未显示的可用信息。

## 能力与验证边界

| 能力 | 当前实现 | 验证边界 |
| --- | --- | --- |
| Gen2 | PRE_COMM、已有密码 AUTH、加密帧、只读车辆信息 | 有历史 MMAX90 成功记录；跨车型和固件仍须实测，不自动 SET_PWD |
| Gen3 / V3Auth | cur/next 快连、6001–6007 完整状态机、Provider 接口 | 有原方法对照及合成会话测试；首次授权和刷新需要车主提供的合法车辆授权服务，车辆兼容性须实测 |
| 半彩投屏 | BLE、MPEG2、私有 RTP、按实际 MTU 分片 | 离线与构建验证；目标仪表首帧和持续运行须实测 |
| 五寸／七寸 | H.264、Wi-Fi Direct、UDP／KCP；蓝牙开屏高级流程 | 认证代际与承载由车型配置决定；兼容性须实测 |
| 独立探针 | 发现、枚举、Gen2 逐段发包及有限测试片段 | 用于与 HCI 对照；仪表兼容性须实测 |

安装后即可使用。连接车辆时按协议完成认证，并授予所需的 Android 蓝牙、录屏权限。

## 安装与使用

从 [GitHub Releases](https://github.com/shj56166/OpenNinebot/releases) 获取 APK。密码和密钥仅在本机加密保存。

应用包名为 `com.motexa.openninebot`。旧九号包 `com.motexa.ninebotlab` 与本应用独立安装，本地车辆资料需重新导入。

### Gen2 蓝牙 Key 如何获取

这里的 Key 指同一辆车已有的 **16 字节蓝牙 password**，导入时写为 **32 位十六进制** `credentials.passwordHex`。Gen3 使用单独的 V3Auth 授权材料。本工具需手动导入凭据。

现有公开资料记录了从**本人 iPhone 的九号官方 App 本地备份**读取 Gen2 凭据的方法；是否存在这些字段取决于 App 版本及备份内容，尚未在所有版本验证：

1. 先用官方 App 正常连接本人车辆，确认车辆 SN。用 Finder、Apple Devices 或 iTunes 创建本地备份。公开方法针对未加密备份；已有加密备份应使用自己掌握的备份密码和支持解密的本地工具读取。
2. 在备份中找到九号 App 的 `Library/Preferences/com.ninebot.segway.plist`。历史资料对应的备份文件为 `7f/7fff5a30d7d67fe00bf012e2dc3a61f63c8661bd`；实际文件位置以备份的 `Manifest.db` 中该 App 的路径映射为准。
3. 用本地 plist 工具读取 `{车辆SN}_decrypt`。核对 SN，按字段实际类型取得原始 password；若是二进制 Data，应转为十六进制，不能把 Base64 文本直接当 Key。只有确认得到完整 16 字节后，才作为 32 位 HEX 导入。
4. 把该值填入九号车辆资料的 `credentials.passwordHex`，并显式设置 `authVersion: 2`、与车型匹配的 `profileId`。在 App 中点“导入九号资料”。导入入口接收包含车型及投屏配置的完整 JSON，不直接接收其他工具的凭据二维码。

Android 端暂无本项目已验证的通用提取教程；官方 App 重新配对后，旧 Key 可能失效，需更新车辆资料。

备份和 Key 都应只在本地处理，不要上传到在线解析或二维码网站，不要提交到源码仓库或诊断附件。历史资料见[研究目录](docs/research/README.md)中的《九号连接视频教程流程》，其中第三方 App 的扫码步骤不适用于本工具。

### 拿到 Key 后能做什么

- 完成 Gen2 的 PRE_COMM → AUTH，建立该车的加密 BLE 会话，并读取当前实现支持的只读车辆信息。
- 对已有车型配置支持的仪表，读取投屏配置、通过蓝牙请求开启投屏，再发送动态示例画面或手机镜像。半彩使用 BLE 媒体通道；五寸／七寸通常使用 Wi-Fi 媒体通道，具体由车型配置决定。
- 用诊断记录对照连接、认证、开屏和媒体阶段，排查兼容性问题。

当前不自动创建或重置车辆密码（SET_PWD），不提供解锁、改限速等功能。

### 简单连接流程

1. 打开蓝牙，允许 App 请求的蓝牙／附近设备权限；先结束官方 App 与车辆的连接，避免争用。
2. 选择车辆信息或对应投屏功能，导入同车资料。Gen2 使用已有 Key，Gen3 使用其专用授权材料。
3. 点“扫描蓝牙”并“选择蓝牙目标”，核对本人车辆，再点“连接并认证”；投屏页面中对应按钮为“连接并读取配置”。
4. 等待认证完成；只读信息以实际返回为准，投屏还须成功读取并校验配置。失败时先核对凭据、认证代际和车型配置。

### 投屏须先通过蓝牙下发开启

对需要蓝牙开屏的五寸／七寸仪表，请选择“蓝牙开启投屏 → Wi-Fi”，按以下顺序操作：

1. 完成上述蓝牙连接、认证和投屏配置读取。
2. 点“蓝牙开启仪表投屏”，通过已认证的 BLE 会话下发车型对应的投屏模式请求；等待仪表应答。
3. 点“发现仪表”“选择仪表”，找到本车开放的 Wi-Fi Direct 目标。
4. 先用“动态示例画面”检查链路，或点“手机屏幕镜像”并授予系统录屏权限；App 建立 Wi-Fi 通道后才发送画面。
5. 使用结束点“停止投屏”，再断开连接。

“仪表已开启投屏 · Wi-Fi 直连”适用于仪表已由官方 App 等途径开启投屏的情况；该入口处理后续 Wi-Fi 媒体传输。半彩 BLE 投屏不经过 Wi-Fi，仍须先完成蓝牙认证及对应模式准备。

已恢复的部分车型使用 `setNaviStart` 请求投屏模式，具体地址和模式由车型配置决定。真实指令须使用当前认证会话完成加密、计数和校验。当前蓝牙开屏配置白名单为 14101／14357／14360；其他车型需补齐配置并实测，应按车型选择配置。详见[研究目录](docs/research/README.md)中的《九号5英寸BLE开屏指令与投屏顺序-20261120》。

GitHub 更新支持可选更新和最低支持版本限制。发现强制更新后，当前会话可正常结束；发起新的连接或投屏前需要更新。更新失败保留此前有效策略，没有有效策略时可离线使用。日志上传和退出不受更新限制。

## 构建

需要 JDK 17+、Android SDK 36、NDK 28.2.13676358、Python 3，以及构建 FFmpeg 所需的 Bash、Make 和 C 编译环境。Windows 优先使用 PowerShell 7。

```powershell
python -X utf8 scripts/prepare-native.py
python -X utf8 scripts/prepare-codecs.py --ndk <Android-SDK>/ndk/28.2.13676358
./gradlew.bat --max-workers=1 :app:testDebugUnitTest :ninebot-java:test :probe:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

依赖具有固定版本及 SHA-256。缓存位于 `.deps/`；可用 `-PcastDepsDir=<目录>` 指定已有缓存。Release 使用自己的 `signing.properties`，格式见示例。

原生测试见 `modules/ninebot-cast-android/src/test/cpp/`。Java 参考实现和独立探针也包含在仓库内。研究入口见 [研究目录](docs/research/README.md)，上传接口见 [日志说明](docs/log-upload.md)，发布更新见 [发布说明](docs/releasing.md)。

## 日志与隐私

只在用户主动选择上传并确认后，将诊断记录发送至 `https://api.motexa.top/ninebot-lab/v1/logs`，用于连接和投屏问题排查。完整媒体轨迹可能包含本次镜像的屏幕内容，请在确认内容可以提交后上传。日志不会自动提交到 GitHub，不公开实际车辆凭据或用户上传附件。

上传按稳定批次编号确认；网络失败、限流及程序重启后保留未确认记录。服务端执行 IP、安装标识、并发、日流量、归档大小及存储配额限制。

## 许可

自有代码、研究文档使用 [MIT](LICENSE)。KCP、cJSON、mbed TLS 和 FFmpeg 保留原许可，见 [第三方声明](THIRD_PARTY_NOTICES.md)。FFmpeg 使用 LGPL-2.1-or-later；发行 APK 同时提供对应源码、配置及重链接材料。旧文档保留研究日期和当时边界，最新能力以本页和实际验证记录为准。

## 致谢与协议来源

本项目的 Gen2 蓝牙协议实现基于 [NootNooot / segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble) 进行改进开发，感谢原项目作者及贡献者的协议研究与开源工作。相关第三方代码保留原有版权声明及许可，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 免责声明

本项目为非官方独立研究，与九号、Segway-Ninebot 及其关联公司无隶属、合作或背书关系。相关商标归原权利人所有。软件按现状提供，车型和固件兼容性须实测；请仅在安全环境中用于自有或已获授权的车辆。

This is an independent, unofficial research project, with no affiliation, partnership, sponsorship or endorsement from Ninebot, Segway-Ninebot or their affiliates. Trademarks belong to their respective owners. The software is provided as-is, without warranty. Use only with vehicles you own or are authorized to test, in a safe environment.
