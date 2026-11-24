# KCP 代理对 H.264/RTP 分支的独立交叉审查

日期：2026-11-14。实际阅读了 `../h264-rtp/notes.md`、`H264OriginalBytecodeHarness.java`、`SliceOriginalMethods.java`、`run-validation.ps1`、sd fallback，并对照原 o31 fallback 的 PTS/心跳/发送分支。把原方法切片和 harness 在独立 TEMP 目录重新编译执行，初版181组/4151项通过；H.264代理按审查建议补齐原sd→KCP入队测试后，再次独立编译执行，最终210组/5103项通过，其中29组覆盖KCP入队；留存 `cross-h264-validation.json`。未改对方研究文件或产品代码。

| 结论 | 独立核查结果 |
| --- | --- |
| byte17 | sd fallback 分别 OR key/type、首片0x10、尾片0x08；原方法执行给单片关键0x19、非关键0x1a。自动JADX的布尔三元式确为类型恢复错误。 |
| RTP timestamp | 原sd.k传入值大端低32位；跨0xffffffff测试通过。来源为o31完整帧PTS减首帧PTS再除1000，需静态跟踪；harness本身仅验证已传入timestamp的序列化，并未执行Android编码主流程。notes对此边界披露准确。 |
| SSRC | 原sd.l只写固定header相应位置，8..11保持0；harness逐字节校验SSRC0和扩展长度，不会把对端SSRC1误当发送SSRC。 |
| 关键帧重复 | 外层循环仅key&&repeat时2次，覆盖空、单片、双片和多片；副本同timestamp、RTP seq继续递增。该外循环发生在transport分支之前，因此DIRECT和KCP都可能重复。 |
| 分片与包数 | 1380媒体+20header，空帧0包，1380为单片、1381为两片；测试涵盖1379/1380/1381/2760/2761。181组的计算为9种长度×2×2×5种timestamp，加固定向量1组。 |
| 心跳反馈 | 20B固定结构、request SSRC0与reply SSRC1、16/24B不匹配，原方法测试通过。失败哨兵-1与合法0xffffffff重叠已披露。 |
| NAL处理 | 原v01.l/L执行覆盖AnnexB保留、4字节大端长度转换、SPS/PPS/IDR扫描与畸形长度；不代表任意AVCC或所有编码器output兼容。 |
| 方法切片 | ASM保留目标方法内容，移除无关接口/静态初始化；Android clock及UDP发送/KCP对象有桩。结论可用于目标方法的字节逻辑，不可提升成整APK或仪表实测。 |

审查时反馈了原sd→KCP入队测试增强建议，现已由H.264代理实现并经本代理独立复跑：1356字节AU→1376B RTP一段、1357→1377B RTP两段、1380→1400B RTP两段；frg和分段重组等于原DIRECT输出，关键帧重复适用于两种承载；还覆盖单消息分段数必须小于窗口1/2/3的边界。这与本KCP分支的手工镜像enqueue和原ks0内核测试形成跨层互证。测试中的ks0/js0仍是桩，实际KCP内核另由本目录验证，不把两者合并称作完整Android链路。

KCP的独立C互通验证只证明1400B合成消息及ACK能双向交换，不能代替原H.264媒体在仪表上解码。编码参数、关键帧恢复、实际延迟、完整P2P/生命周期仍需Android/实车证据。
