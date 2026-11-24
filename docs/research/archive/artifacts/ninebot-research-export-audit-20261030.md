# 九号研究导出包：主 App 内容隔离审查

审查日期：2026-10-31。审查对象：`ninebot-research-github/` 当前快照。

结论：修订后的当前版本通过严格范围审查。独立子代理完整读取全部 13 个文件，对 3 个 JSON 逐项展开审查，并复读修订内容。2026-10-31 又将 MMAX90 文档整理为“已通过测试与协议原理”；主代理重新检查私有内容标记、编码、链接与哈希。

本报告位于上传文件夹之外，不是导出包的一部分。

## 发现并修正的遗漏

初版 MMAX90 策略仍转述了原实验后端的内部日志字段、写入间隔、重试次数与间隔、明文实验存储方式、固定队列与轮询调度，以及导入接口所支持的格式。这些内容已去除，替换为九号协议字段、通用凭据表示和外部实测结果。

续研清单同步去除了主 App 的轮询模型。Gen3 JSON 中 3 个指向本地上游检出的路径已简化为公开参考仓库的相对位置。

## 逐文件结论

| 文件 | 结论与内容归属 |
| --- | --- |
| `README.md` | 通过。目录、研究结论、材料范围及上游说明。 |
| `CONTINUE-RESEARCH.md` | 修订后通过。九号协议研究任务、官方调用链与证据要求。 |
| `SHA256SUMS.txt` | 通过。12 个资料文件的路径和 SHA-256，全部匹配。 |
| `docs/mmax90-connection-strategy.md` | 修订后通过。仅已通过测试和 Gen2 协议原理；无主 App 内部日志、存储或调度实现。 |
| `docs/ble/ninebot-mmax90-gen2-protocol-analysis.md` | 通过。NUS、Gen2 帧与加密原理，明确区分线上的 u16 计数器和 nonce 内部 u32。 |
| `docs/segway-ninebot-ble-app-implementation.md` | 通过。类名、Dart 片段、队列与轮询机制归属公开上游；子代理已对照本地上游源码和 remote。 |
| `docs/九号连接视频教程流程.md` | 通过。公开 iOS 客户端教程；备份位置为通用路径，凭据二维码使用占位符。 |
| `docs/research/九号投屏链路与加密研究汇总-20260922.md` | 通过。九号官方 App 与公开 NinebotEnhance 的研究、通用方案和验证计划。 |
| `docs/research/NinebotEnhance非导入APK投屏可行性复核-20261009.md` | 通过。类名、路径及媒体参数归属公开上游或九号官方；通用桥接建议不含项目主 App 的现有实现。 |
| `docs/research/九号Gen3与2025后车型蓝牙握手深度研究-20261009.md` | 通过。接口、类、字段和 native 地址归属九号官方协议研究。 |
| `docs/research/ninebot-gen3-20261009/evidence-summary.json` | 通过。车型目录、模块表、命令摘要、来源哈希及合成广播；SN 仅为 null 或合成 TEST。 |
| `docs/research/ninebot-static-gap-20261009/evidence-summary.json` | 通过。官方字段、合成 RTP/RTCP 向量、native 线索和来源哈希；密码寄存器名称不是实际密码。 |
| `docs/research/ninebot-static-gap-20261009/rea-followup-20261010.json` | 通过。REA 元数据和官方 Java/native 摘要；SN、密钥字段值为描述性占位文本。 |

## 最终核验

- 13 个文件：9 个 Markdown、3 个 JSON、1 份校验清单。
- 未发现主 App 名称、私有包路径、已知内部类、内部日志标识、主 App 源码文件、私有 API 或业务数据流。
- 未发现实际车辆 SN/MAC、password、auth、ident、账号令牌或私钥。公开 FW_DATA 属于协议常量，车型目录 ID 不是个人车辆或账号标识。
- 未发现混入的隐藏目录、Git 目录或软链接。
- 3 个 JSON 均可解析；文本为 UTF-8，无替换乱码字符。
- 45 个本地文档链接及标题引用全部有效。
- 12 条文件 SHA-256 全部匹配。原项目文档未改动。

校验清单 `SHA256SUMS.txt` 自身 SHA-256：

```text
ef313cfe464465acd87aaf01de15978d4089f0d9e58629a516fe7f75b491a381
```

本结论仅绑定上述当前快照。审查针对导出包与项目主 App 内容的隔离，不代表新增实车兼容性验证或第三方代码许可认证。后续添加或修改导出文件，应重新审查并生成校验清单。
