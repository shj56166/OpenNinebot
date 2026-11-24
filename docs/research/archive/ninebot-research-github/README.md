# 九号 BLE 与投屏认证研究 · Ninebot BLE & Screen-Casting Authentication Research

本项目基于 [NootNooot/segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble) 的公开协议资料开展研究，面向**脱离九号官方 App 读取车辆蓝牙数据，以及独立投屏所需的身份认证与握手**，整理 Ninebot / Segway 车辆的 Gen2、Gen3 / V3Auth、只读遥测与投屏会话研究记录。

This project builds on the public protocol resources in [NootNooot/segway-ninebot-ble](https://codeberg.org/NootNooot/segway-ninebot-ble). It investigates **reading vehicle data over Bluetooth without the official Ninebot app, and the identity authentication and handshakes required for independent screen casting**, covering Ninebot / Segway Gen2, Gen3 / V3Auth, read-only telemetry, and casting sessions.

[中文介绍](#中文介绍) · [English](#english) · [文档目录 / Documents](#文档目录--documents)

## 中文介绍

本仓库汇集九号车辆协议研究文档和证据摘要，为独立客户端的车辆发现、连接认证、数据读取及投屏握手提供参考。主要用例是：在车主授权和持有有效车辆凭据的条件下，不运行九号官方 App，建立 BLE 会话并读取车辆数据；进一步研究投屏前的身份认证、会话建立、配置协商与媒体传输。

研究重点包括：

- **MMAX90 / Gen2 BLE 连接**：NUS 服务、PRE_COMM / AUTH、凭据与会话密钥、帧校验、只读寄存器和重连。
- **Gen3 / V3Auth 身份握手**：广播认证版本识别、云端密钥字段、native 调用链、密钥存取及尚未还原的认证状态机。
- **九号仪表投屏**：投屏配置与开停、身份认证与会话的关系，以及 BLE / Wi-Fi / RTP / RTCP / KCP 的传输边界。
- **后续深度研究**：区分已验证结果、静态证据和候选推断，记录下一步需要的调用链、报文向量与实车对照。

### 当前进度

MMAX90 Gen2 已有使用既有 password 完成 PRE_COMM / AUTH 并读取遥测的成功实验记录；首次 SET_PWD 配对、部分寄存器缩放和跨固件兼容性仍待验证。

Gen3 / V3Auth 已定位广播选择、官方 Java/native 调用链、密钥资料与部分会话流程，**完整独立身份握手仍未实现和验证**。投屏研究同样尚未形成可脱离官方 App 使用的完整认证与媒体客户端。NinebotEnhance 等依赖官方进程的路线作为对照材料收录，不能视为独立投屏已经完成。

车型名称中的“Gen3 / G3”、上市年份、帧头与认证版本应分别核验，不能据此推定某辆车已经兼容。

### 资料范围

本仓库提供研究文档、来源哈希、配置摘要与合成测试向量，不包含可直接安装运行的独立客户端。未附官方 APK、native 库、反编译原件、原始私人抓包或实际车辆凭据。文中的协议常量、示例和字段名称不代表真实车辆密钥。

首页更新：2026-10-31。研究文档保留各自日期和来源版本，包含截至 2026-10-10 的静态续研；本次首页更新未新增实车验证。非官方研究项目，与 Ninebot / Segway 无隶属关系。参考项目与第三方材料遵循各自原有许可。

## English

This repository collects protocol research notes and evidence summaries for independent Ninebot / Segway clients. The intended use cases are to establish BLE sessions and read vehicle data without running the official Ninebot app, using valid credentials with the vehicle owner's authorization, and to investigate the authentication, session setup, configuration negotiation, and media transport needed for independent screen casting.

The research covers:

- **MMAX90 / Gen2 BLE connections**: NUS services, PRE_COMM / AUTH, credentials and session keys, frame integrity, read-only registers, and reconnection.
- **Gen3 / V3Auth handshakes**: advertised authentication versions, cloud key fields, native call chains, credential storage and consumption, and the unresolved authentication state machine.
- **Instrument screen casting**: casting configuration and start/stop control, authentication and session requirements, and BLE / Wi-Fi / RTP / RTCP / KCP transport behavior.
- **Further investigation**: verified results, static evidence, candidate interpretations, and the call traces, packet vectors, and vehicle tests needed to close remaining gaps.

### Current status

MMAX90 Gen2 experiments have successfully completed PRE_COMM / AUTH with an existing password and read telemetry. First-time SET_PWD pairing, some register scaling, and compatibility across firmware versions still require validation.

For Gen3 / V3Auth, research has identified advertisement-based protocol selection, official Java/native call chains, key metadata, and parts of the session flow. **A complete independent authentication handshake has not yet been implemented and validated.** The casting research also does not yet provide a complete authentication and media client that works independently of the official app. Official-process-dependent approaches such as NinebotEnhance are included as reference material, rather than evidence of completed independent casting.

A model name containing “Gen3 / G3,” its release year, its frame header, and its authentication version are separate properties. None alone establishes compatibility.

### Repository scope

The repository contains research documents, source hashes, configuration summaries, and synthetic test vectors. It does not provide an installable standalone client. Official APKs, native libraries, decompiled source artifacts, raw private captures, and actual vehicle credentials are not included. Protocol constants, examples, and field names are not real vehicle keys.

Homepage updated: 2026-10-31. Research notes retain their original dates and source versions, including static follow-up work through 2026-10-10. This homepage update adds no new vehicle validation. This is an unofficial research project, unaffiliated with Ninebot / Segway. Referenced projects and third-party materials retain their respective licenses.

## 文档目录 / Documents

文档正文主要为中文，部分协议分析与证据摘要为英文。 / Most research notes are in Chinese; some protocol analyses and evidence summaries are in English.

| 文档 / Document | 内容 / Coverage |
| --- | --- |
| [MMAX90 连接策略 / Connection strategy](docs/mmax90-connection-strategy.md) | NUS、已有凭据握手与实测边界 / Existing-credential authentication and experimental limits |
| [MMAX90 Gen2 协议分析 / Protocol analysis](docs/ble/ninebot-mmax90-gen2-protocol-analysis.md) | HCI、帧格式、加密和寄存器 / HCI, framing, encryption, and registers |
| [Gen2 / Gen3 续研清单 / Research roadmap](CONTINUE-RESEARCH.md) | 密钥消费、状态机与验证要求 / Key consumption, state machines, and validation |
| [Gen3 与新车型握手 / Gen3 and newer-model handshakes](docs/research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md) | 广播选择、V3Auth、native/keyring 与 AA55 / Advertisement selection, V3Auth, native/keyring, and AA55 |
| [开源 BLE 客户端分析 / Reference BLE client](docs/segway-ninebot-ble-app-implementation.md) | 上游握手、车型寄存器与命名冲突 / Upstream handshakes, registers, and naming conflicts |
| [投屏与加密研究 / Casting and encryption](docs/research/九号投屏链路与加密研究汇总-20260922.md) | 官方链路、NinebotEnhance 与传输边界 / Official-app paths, NinebotEnhance, and transport boundaries |
| [NinebotEnhance 投屏复核 / Casting feasibility review](docs/research/NinebotEnhance非导入APK投屏可行性复核-20261009.md) | 独立投屏缺口、控制与媒体传输 / Independent-casting gaps, control, and media transport |
| [iPhone 连接教程 / iPhone connection guide](docs/九号连接视频教程流程.md) | 公开客户端连接、凭据二维码与排错 / Public-client connections, credential QR codes, and troubleshooting |

## 证据摘要 / Evidence summaries

- [Gen3 配置与广播 / Configuration and advertisement evidence](docs/research/ninebot-gen3-20261009/evidence-summary.json)
- [Native/投屏静态证据与向量 / Static evidence and offline vectors](docs/research/ninebot-static-gap-20261009/evidence-summary.json)
- [REA 续研摘要 / Follow-up evidence · 2026-10-10](docs/research/ninebot-static-gap-20261009/rea-followup-20261010.json)
- [文件校验清单 / File checksums]（本地来源，未随附原始材料）

摘要中的文件名与哈希用于识别研究来源，不表示原始材料已随附，也不构成完整复现环境。

File names and hashes identify the materials analyzed. They do not indicate that the original artifacts are included or that this repository provides a complete reproduction environment.
