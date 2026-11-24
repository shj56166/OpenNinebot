# 九号连接复现证据

研究日期2026-11-15，原始官方 Android App 6.10.11。没有手机/车辆/云账号收发，没有实际认证或显示验收。

- [主报告](../../docs/research/九号全仪表连接复现与分阶段发包方案-20261115.md)
- [独立 Android 测试工程](../../tools/ninebot-ble-test/README.md)
- [Gen2会话与原加密函数](gen2/notes.md)
- [官方扫描、协议选择、准备、配置与控制](official-connect/notes.md)
- [Gen3密钥槽、认证与fast路径](gen3-auth/notes.md)
- [Gen3独立交审](official-connect/cross-review-gen3.md)
- [工具异步与HCI交审](gen2/cross-review-tool.md)

官方APK SHA256 `02ecd91b74e00df76cfe00eda4c4a9a5bc332231562a171e48d64f45bd418705`；`librust_lib.so` SHA256 `2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`。原输入复用[前轮固定证据](../ninebot-ble-cast-20261115/README.md)，原APK/SO/转换jar仍在本机Git忽略目录。

复跑分别见各分支 `run-validation.ps1` / Python脚本。`validate-delivery.py`校验交付APK、当前源码/结果、内置MPEG2解码、HCI测试与文档引用，写 `validation-summary.json` 和 `input-inventory.json`。根代理重跑和子代理交审不重复计算覆盖数量。
