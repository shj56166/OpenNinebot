# 九号连接研究：独立 Android 发包探针

日期：2026-11-15。包名 `com.motexa.ninebotbletest`，版本 `0.1.0`，Android 8.0 / API26 及以上。独立测试工程，不加载九号官方 SO，不联网。蓝牙收发、配置与阶段结果写入应用私有 NDJSON，需手动导出。

这是用于逐段复现与抓 HCI 对照的工程包。**目前没有手机或实车互通结果；构建和离线验证通过不等于全仪表适配成功。**

## 当前覆盖

| 路线 | 本测试包能执行什么 |
| --- | --- |
| 所有可扫描 BLE 设备 | 保存原始广播、识别已校验的九号认证版本提示、连接并枚举服务/特征、协商 MTU |
| 基础协议2 + 广播认证版本2 + NUS | 独立 PRE_COMM、已有密码 AUTH、单条只读请求 |
| 上述路线 + 三份已有官方命令表 | type1 投屏配置读取/完整 MD5、地图导航模式开停 |
| 上述路线 + 本次校验配置为 BLE/MPEG2 | 尺寸与 FPS 匹配时发送有限 MPEG2 测试片段 |
| 真实 V3Auth / Protocol1 / 未分类广播 / 仅 Custom 或 FFE0 服务 | 保留枚举和广播；当前探针不尝试代换成 Gen2 |
| Wi-Fi H264 / Wi-Fi KCP / MJPEG | 显示读取到的路由/编码；此 BLE 工程不实现 Wi-Fi 媒体发送 |

内置目录是官方 App 已采集的 **131 条车型配置快照**，不是131种仪表已适配。当前只有 Mmax2 `14357`、N3 `14101`、Mz `14360` 三份解密命令表能证实 `tft=0x23` 和投屏命令；其余命令未知，不能把有TFT模块等同于可投屏。

## 构建与安装

在仓库根目录执行：

```powershell
pwsh -NoLogo -NoProfile -File tools/ninebot-ble-test/scripts/build.ps1
pwsh -NoLogo -NoProfile -File tools/ninebot-ble-test/scripts/install.ps1 -Serial <ADB序列号>
```

`build.ps1` 使用现有 Gradle wrapper，运行 Debug 构建、单元测试与 Android Lint。安装脚本只安装独立工程包；未指定序列号时要求恰好一个已授权 ADB 设备。APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

## 手机操作顺序

1. 先启用手机开发者选项中的**完整蓝牙 HCI 日志**。准备一份官方 App 从未连接到正常投屏、再停止的成功会话作基线。结束官方连接后再运行探针，避免两个客户端交错操作同一车辆。
2. 打开“九号连接研究”，选车型，点扫描，再从列表选择实际目标。扫描会记录其他附近设备的原始广播，日志仅保留在本机。
3. 点“连接并枚举服务”，等待 `services_discovered` 和 MTU 回调。`gatt_connected` 尚不是认证成功。认证控制只认 NUS 后缀 `b5a3-f393-e0a9-e50e24dcca9e`；媒体后缀 `0000-0000-006e-696e65626f74` 不被当作认证通道。
4. 基础协议2、有效广播版本2、NUS特征属性正确时，可点“Gen2预通信／认证”。没有密码也能先观察一次 PRE_COMM；已有绑定车辆进一步 AUTH 需要**该车已有的16字节蓝牙密码**，不是九号账户登录密码。只在输入框填写32位HEX，不保存、不打入日志；本包不从账户或其他应用提取密码。
5. 标准握手目标是 `0x04`。另有 `0x09` 的显式 VCU 候选，只有历史 PRE_COMM 证据，不能称所有 MMAX90 的 AUTH 已打通。本工具不失败后自动换板地址重试。
6. `authenticated` 后可手动发一条只读请求。输入框的板地址/寄存器是HEX，字节数是十进制；默认 `01/26/2` 是已有部分车型的速度读取候选，不能当成所有车型的统一寄存器。
7. 三份已有命令表对应车型可继续“读取并校验投屏配置”。只有当前连接成功重组、MD5一致、JSON可解析，才保存本次配置；不回退旧缓存。随后显式“开启地图投屏模式”，等待匹配回复。
8. 配置为 BLE/MPEG2、尺寸匹配、版本受支持、实际 ATT MTU大于30、模式已有回复时，才可发送片段。内置片段为**240×320、5fps、10个独立I帧**的标准FFmpeg测试图，尺寸不会拿来覆盖仪表配置。其它尺寸先用下方脚本生成并导入。
9. 本地发完会显示 `media_submitted`；现场记录仪表是否显示、颜色/方向是否正确、图案是否变化。随后点“停止投屏模式”、等待回复，再断开、导出日志。发生协议/GATT错误或超时时会结束会话，不自动继续下一阶段。

没有设置过蓝牙密码的回复会停在 `unpaired`；没有提供已有密码会停在 `credential_required`。这两个阶段均不发送 `SET_PWD`，也不意味着目标仪表永久不支持独立投屏。重新认证从新的连接开始。

## 片段生成

电脑安装标准 FFmpeg / ffprobe 后，按仪表**实际配置**生成：

```powershell
python -X utf8 tools/ninebot-ble-test/scripts/make-probe-clip.py --width 240 --height 320 --fps 5 --seconds 2 --output tools/ninebot-ble-test/captures/probe.json
```

通过手机文件选择器导入JSON。脚本直接分离 MPEG2 video AVPacket，不封装PS/TS。Android 端检查初始序列头尺寸、图像类型、文件和帧上限；这不是完整的手机解码器验收。PC交付验证另使用FFmpeg解码全部内置帧。

媒体按真实 ATT MTU `A` 计算：每片视频 `A-30` 字节，完整RTP至多 `A-10` 字节；例如 ATT247 时217/237。PT0、20字节头、wall-clock毫秒低32位、会话SSRC、I帧 pictureType1，其余2。控制加密包在握手前按20字节ATT值切片，认证后按 `A-3`，两者不能混淆。

每次GATT写入等待本地回调并检查status，超时停止，不复制官方忽略status的行为。无自动重试；片段发送受本地写回调背压，实际FPS可能低于片段FPS。停止会丢弃尚未提交的媒体包，等待已提交的一次写入完成，再发停止模式；独立播放token防止旧定时器进入新一轮播放。

## 日志与 HCI 对照

手机界面可导出本次NDJSON；Debug安装还可用：

```powershell
pwsh -NoLogo -NoProfile -File tools/ninebot-ble-test/scripts/collect.ps1 -Serial <ADB序列号>
```

需要同时导出系统错误报告时追加 `-IncludeBugreport`。Android是否在错误报告中保留完整HCI由设备决定，脚本不能凭空恢复已裁剪的ATT数据。日志含设备地址和原始收发，避免公开发布。

对原始H4 `btsnoop_hci.log` 或只含一份完整btsnoop的错误报告ZIP执行：

```powershell
python -X utf8 tools/ninebot-ble-test/scripts/analyze-hci.py <btsnoop_hci.log或bugreport.zip> --output tools/ninebot-ble-test/captures/hci-analysis
```

输出 `summary.json` 和 `att.ndjson`。保留ATT原始字节、实际MTU协商、服务/特征映射、专用媒体UUID与RTP候选。截断/丢包会清理未决重组和请求上下文；发现缓存导致没有服务发现时，未知handle保持未知。只支持 H4 btsnoop / 固定ATT CID4，不解码EATT动态通道；App层认证密文不会被自动解释成认证成功。

同一脚本也解析普通/扩展LE广播，输出 `authVersionHint` 与manufacturer校验结果。扩展广播只有complete data才尝试识别，其余保留原始值并标未知；不跨隐私地址合并身份。这是广播观测提示，仍不是认证成功。

`scripts/v3_fast_auth.py`另提供不依赖官方SO的 `build_challenge(app_key16, unix_seconds, nonce8)`，生成已核对的34字节快速挑战payload。它只供离线协议复核，无CLI密钥参数、网络或无线操作；Android探针未开放未闭合的Gen3发包。

关键事件依次核对：`advertisement` → `gatt_characteristic` → `cccd` → `precomm`/`auth` 收发 → `authenticated` → `cast_config_md5_verified` → `mode_ack` → `media_frame`/`tx`/`local_write_callback` → `media_submitted`。`mode_ack`只证明收到匹配控制回复，`local_write_callback`只代表本地写入结果，最终显示另行记录。

详细协议、Gen3边界与原函数验证见[研究报告](../../docs/research/九号全仪表连接复现与分阶段发包方案-20261115.md)及[证据目录](../../artifacts/ninebot-connection-repro-20261115/README.md)。
