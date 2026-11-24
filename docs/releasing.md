# 发布与 GitHub 更新

本项目发布仓库为 `shj56166/OpenNinebot`。每个正式 Release 提供签名 APK、`update.json`、SHA-256 清单和 codec 源码重链接包。

更新清单为 ECDSA P-256 / SHA-256 签名信封：`signedPayload` 是 Base64URL 编码的原始 JSON 字节，`signature` 是对应 DER 签名的 Base64URL。公钥随源码提供；签名私钥仅由发布者保管。

payload 版本为 `schemaVersion=1`，包含 `packageName`、`versionCode`、`versionName`、`minSupportedVersionCode`、`bytes`、`sha256`、`certificateSha256`、`url`。APK URL 必须指向本仓库的正式 Release 附件。

当前版本低于 `minSupportedVersionCode` 时，应用允许当前会话完成，随后要求更新才能发起新的连接或投屏；否则新版可选择稍后更新。检查失败使用上次验签通过的缓存。下载允许 GitHub 到官方附件 CDN 的 HTTPS 重定向，安装前再次检查包名、版本、文件哈希和发布证书。

```powershell
python -X utf8 scripts/sign-update.py --apk app/build/outputs/apk/release/app-release.apk --key <私有P256密钥> --certificate-sha256 <APK发布证书摘要> --version-code 2 --version-name 0.1.1 --minimum-version 1 --tag v0.1.1 --output <交付目录>/update.json
```

上例是非强制更新。将最低支持版本设为 2 后，版本码 1 的发行包会要求更新。私钥、APK 签名密钥及本地签名配置均不得提交到仓库。

源码发布前运行 `python scripts/audit-source.py`。使用 `scripts/package-codecs.py` 为精确匹配的 APK 和原生目标目录生成并验证重链接包；不要使用其他应用的原生目标文件。
