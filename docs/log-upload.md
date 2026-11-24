# 手动诊断上传接口

`POST https://api.motexa.top/ninebot-lab/v1/logs`

请求为 `application/zip`，请求头包含 `X-Install-Id` 和 `X-Upload-Id`，均为 32 位小写十六进制标识。不需要账号、试用租约、静态客户端密钥或 Authorization。安装标识只用于分组和限流。

归档使用版本 4，仅允许 `manifest.json`、`metadata.json`、`events.ndjson`、`metrics.ndjson`、`stream.ndjson`、`trace.bin`。媒体轨迹与文本批次分别上传。`metadata.batchId` 与上传编号相同；manifest 包名为 `com.motexa.openninebot`、`consentVersion` 为 `ninebot-manual-v1`，明确记录本次手动同意。轨迹包含偏移、长度和 SHA-256 校验。

服务成功写入并持久化配额账本后返回 `201 {"uploadId":"..."}`；相同内容重试返回 `200`，相同编号不同内容返回 `409`。只有匹配的成功确认才能清理本地对应批次。

默认每安装 6 批/分钟、每 IP 30 批/分钟、全服务 120 批/分钟；每安装每日 256 MiB、每 IP 每日 1 GiB、全服务每日 2 GiB。IPv6 按 /64 合并；公网限流与应用内节流共同生效。单批压缩不超过 3 MiB，展开不超过 8 MiB。总存储配额同时预留反馈附件副本。

`429 rate_limit` 或 `upload_busy` 携带 `Retry-After`；客户端顺序发送、按延迟等待并最多自动重试两次。`daily_quota`、`archive_quota` 不自动重试；超过两分钟的等待交给用户稍后重试。`400` 表示归档不符合契约，`413` 表示体积超限，`503` 表示服务或存储暂不可用。

记录只用于诊断，不会自动公开到项目仓库。完整媒体轨迹可能包含屏幕内容；上传前在应用内明确确认，未确认记录保留在本机。
