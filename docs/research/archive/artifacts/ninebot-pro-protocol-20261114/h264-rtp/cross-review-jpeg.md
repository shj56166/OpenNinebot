# H.264/RTP 代理对 JPEG 研究的交叉复核

复核日期：2026-11-14。审阅 `../jpeg/notes.md`、`JPEGVectors.java`、`verify_jpeg.py`、`g71-javap.txt`、`sd-javap.txt`、`validation.json`，并独立对照此前已经读取的原 `g71.java` 与 `o31.b` fallback 指令。

## 结论

没有发现需要推翻 JPEG 主报告的字节级结论。已复核：

1. **心跳请求与识别器不同**：`g71.b` 输出16字节、SSRC1、name=`mpv\0`；`o31.b` 接收却调用共享 `sd.i`，该方法只接受20字节、SSRC1、name零、末尾mode2。来自本代理的原 `sd.h/i` 执行也证明16/24字节拒绝。不能推定仪表回显，因此应描述为识别格式覆盖不足，而不是已证明每台仪表都因此故障。
2. **JPEG与H.264 RTP不能混用**：JPEG媒体切片1400、总1420；随机SSRC，扩展固定`00 01 00 00`。H.264媒体1380、总1400；SSRC0，扩展首/末/key与时间低位。两者PT32、RTP extension1 word和毫秒时基外形相似，不意味着相同负载。
3. **72字节SR/BYE自洽但有互通边界**：原方法向量解析为SR28字节、BYE44字节，BYE reason长度21和实际文本一致，末尾14个零；总72。按reason自然对齐只需2个零，因此应保留多12零和缺SDES/CNAME这一静态事实，不能自行宣称目标仪表不接受。
4. **分支优先级和JPEG的KCP忽略**：`o31.a` 先判断JPEG并调用b后return；`d02.F`未强制DIRECT，但JPEG发送循环没有构建ks0，因此同时保存JPEG+KCP仍走直接UDP。
5. **调度/计数**：`od.z` 确认 `round(1000/FPS)`；无socket仍统计封包；停止/异常消耗序号但不累加失败包SR计数的描述与代码一致。

## 重跑与覆盖边界

直接运行 JPEG 代理已经编译的 harness，目标 `g71/sd` 仍从原 dex2jar jar 加载，151条断言通过。重跑 stdout 保存在本目录 `cross-review-jpeg-output.jsonl`，没有修改对方文件。独立按RTCP长度字段和reason字段解析其原方法输出，得到72=28+44、reason=`jlibrtp says bye bye!`、末尾14字节。

这个重跑是对同一151条用例的复核，不应在总文档中再当作151条新增独立覆盖。它没有执行Android Bitmap.compress、随机数会话创建、画面采集源、实际P2P或真实反馈；这些部分来自静态指令审查。首尾有效但中间不是可解码JPEG的向量有意证明校验器只检查标记，不能作为JPEG编码正确的测试。

仪表4.3寸显示、实际心跳/丢片重组、SR/BYE是否必要和是否接受，仍未得到设备或pcap证据。总文档应把“APK内部逻辑已闭合”和“仪表协议兼容性已验收”分开。
