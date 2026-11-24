# 主代理对GATT/JNI分段验证及关键语义的交叉审查

日期2026-11-15。审阅`../ble-security/emulate_gatt_boundary.py`、`notes.md`及其结果，并通过本轮`run-all.ps1`独立执行24个分段场景。执行全部通过；本文件不新增覆盖计数。

## 原指令与hook边界

- constructor/poll段：执行原`0x5cdaf0`和应用ELF RELA后的vtable poll，检查分配大小0x40/对齐8、vtable0x679010、poll0x5bfbc8，再在`0x5b2450`边界截获。本脚本采用11种长度，构造11组；不能与另一个root工具的13种长度混淆。
- 数组转换段：相同11种长度执行原`0x5d6390`及NewByteArray/SetByteArrayRegion包装，JVM表函数作为显式hook；校验分配长度、复制起点0、原指针和全部原字节。不是实际Java VM数组运行。
- JNI实参段：对Android writeType1/2执行原`0x5c1684`实参构造，在generic JNI调用边界截获UUID、byte[]、int三实参及缓存method位置。不执行真正Java方法或无线栈。

11+11+2=24，不能称为24个完整SDK端到端流程。测试加载原ELF、校验其哈希，目标指令未改写；allocator/JVM调用为hook，并未借环境桩冒充车辆接收。

## 交叉审查中修正的两点

1. RTP返回语义：主代理指出“JNI只表示任务受理”不足以描述LocalPool run_until。native代理再次追Pending/Ready与bool返回确认RTP同步等本地Future，安全代理修订说明；RTCP的Tokio spawn才是另一种受理返回。最终主文档分别记载。
2. MTU：安全代理追查getter发现native值>20时Java先减3，再由sender减27。随后继续闭合Android协商值到native存取链，Java代理和主代理同时复核并修正旧样例。正常ATT247对应getter244、媒体217、完整RTP237；局部split测试直接给getter247不等于ATT247。

`onCharacteristicWrite`忽略GATT status的结论另经classes11.dex→转换jar→javap复核，避免只依赖JADX Java恢复。RTP无应用层再次加密与设备会话准备仍分别陈述；没有将此处字节保持扩大为无线链路明文或无需认证。

本次交审未发现影响主媒体链结论的剩余阻塞项。RTCP通用worker的最终consumer、目标仪表配置/权限、真实MPEG2码流与显示继续保留未知。
