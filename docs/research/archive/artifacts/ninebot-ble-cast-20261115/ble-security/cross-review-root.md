# 对 control-config 原 ARM64 仿真的独立交审

审核对象：同级 `control-config/emulate_rtp_forward.py` 与 `native-forward-validation.json`。审核日期 2026-11-15。复跑脚本 `review_root_forward.py` 不覆盖原报告；结果 `root-forward-rerun.json`。

## 结果

- 独立重跑 14 个 `0x2eccd8` 转发场景、7 个 `0x328b70..0x328ba8` 基本块初始化场景，逐项与原报告相等。**这是已有场景的复核，不额外计数为新的 21 个测试。**
- 输入 ELF SHA256 与官方 6.10.11 冻结库一致。
- 现有 scope 准确：验证指定原指令路径，不代表认证、Android/GATT、完整调度器或车端测试。

## 逐项审查

1. `0x2ed394` hook 替代特征查找并返回 native `Poll/Result` 状态。原调用点使用 qword `-2` 表示 Pending，`-1` 表示 Ready/Ok。测试校验服务和特征的真实字符串，不是只校验预设常量。
2. Pending 场景第一次返回时 `OUT=-2`、state=3、未进入 writer，第二次调用从同一 future state 恢复再到 writer；这验证了该函数的局部恢复分支。它不执行线程 park/wake 或 GATT 通知。
3. `0x325a60` 是客户端配置/句柄的共享引用克隆。审核其原指令：输入 `x0` 是 `SELF[0]` 的客户端对象，访问配置标志、Arc 引用与元数据（如 `+0x268..+0x2b0`），未拿到本调用的 `INPUT` slice；正常路径未调用 crypto。hook 不会遮住这条调用边界上的媒体读取或加密。该 hook 只填少量 dummy 句柄，故不能据其证明真实配置、认证或 GATT 可用。
4. writer 截获点 `0x5cdaf0` 前参数为同一指针、同一长度、原内容；指令级写监视无媒体内存写。0 字节场景仅覆盖 native 函数边界，**不**表示 Java `BleSdkClient.writeRtpData` 允许空包；后者会拒绝。
5. 原报告恰当使用 `write_type_discriminant=1`。本分支进一步追到 `0x5b2670..0x5b267c`，非零映射 Android `writeType=1`，即 `WRITE_TYPE_NO_RESPONSE`；不应改称“with-response=true”。分段 JVM 边界仿真还校验了 Java 三实参。
6. RtpHandler 的 7 组初始化测试只执行构造中的直线基本块，恢复默认字段 `header=12,specific=8,index=0,ssrc=0,PT=32,standard=1` 及 payload 参数的 u16 截断。该对象默认 PT=32 不可替代 Java 业务实际 PT=0，也不证明对象参与 JNI `writeRtpData` 直接链。

## 另行纠正的语义

本分支初稿曾写“JNI 只表示本地任务受理”；root 交审指出该句对 RTP 过宽，已修正。KCP 独立追踪确认 `0x26bfec` 在 writer Pending 时循环，在 Ready 时将结果映射 bool，`0x27e7e8` 和 JNI 返回该 bool。RTCP 则走 Tokio spawn 后返回 true。最终文档必须分别描述两者；两者都不能当成车端确认，尤其本 APK 的 `Peripheral.onCharacteristicWrite` 忽略 GATT `status`。
