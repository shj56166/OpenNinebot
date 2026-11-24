# KCP 代理对总文档的最终交叉审查

日期：2026-11-14。审查对象：`docs/research/NinebotPro完整协议分支与交叉验证-20261114.md`。不修改总文档；以下问题和建议已发主代理。

## 协议内容核对

第9节与本目录原KCP方法24项验证及固定上游C互通13项一致：conv3为final、MTU1400/MSS1376、窗口256/256且初始远端128、nodelay1/interval10ms/fastresend2/nocwnd=true。第9.3节正确区分5012 KCP确认与5013控制统计，未把心跳未识别错误等同于没有KCP ACK。

第9.2节UNA写“累计确认界限”不错误，但建议改为“本端期望接收对端的下一个连续SN（对其发送流累计确认）”，避免与本端snd_una混淆。本目录notes已按JPEG审查意见采用准确方向表述。

第9.4节SN回绕表述克制且准确：专项测试将long计数置于32位边界，复现接收推进停住和选择性ACK未命中；累积UNA仍可删除发送段。它没有声称日常短时投屏一定发生，也没有推断仪表端同样有缺陷。无需扩大为所有KCP库的问题。

第9.5节正确把13项独立C互通限制在合成消息、分片、重组和ACK；未将其表述为车辆显示、所有丢包/长期边界与完整内核等价。第11.4节官方对照的conv更新、MTU1444、nodelay(2,100,2,1)和窗口10240均可追溯至原固定Java证据；第11节已明确native内部行为没有由Java参数证明。其余BLE/V3Auth内容超出本代理本轮主审范围，由主代理原证据负责。

## 验证矩阵与最新结果

第13节KCP24项和C互通13项准确。审查时发现H.264的验证已增加原sd→KCP入队29组，最新结果是 **210组/5103项**，总文档仍写181组/4151项，应更新。本代理已独立重新编译执行增强后的harness，结果保存在 `cross-h264-validation.json`。其中210是210次原RTP测试场景（含用于比较的DIRECT调用），29组为其中额外关联的KCP队列调用，不能把两列再相加成239组独立基线。

新增覆盖是原sd.l对KCP stub入队、严格分段数<窗口、1376边界、frg与关键帧重复；它不执行实际ks0内核。本目录另测试原ks0，二者组合是相互核对的局部方法验证，不是完整Android发送流程动态运行。第13节通用方法限制已明确这一边界，可加一句以避免混合计数。

## 冻结材料和复跑依赖

本目录脚本当前默认依赖TEMP转换jar，interop每次会联网下载固定commit源；仅保存正文与脚本不足以保证以后离线复跑。建议冻结如下材料到主证据目录已被git忽略的raw/tools、raw/upstream-kcp、raw输入等位置，并把脚本默认路径改成这些文件（允许参数覆盖）；不需要提交第三方二进制到产品仓库。

| 当前文件 | 用途 / SHA-256 |
| --- | --- |
| `%TEMP%/ninebot-pro-v0.6.1.apk` | 原始APK；`bd0a0a063fd1966836f97ce3ba2e948a52f6a5a86538dfef78118f7c83ebf94f`；主目录raw已有副本 |
| `%TEMP%/ninebot-pro-061-dex2jar.jar` | 直接复跑原方法；`cd7dcb614c42da5af5ac64f86253231b893ceb70628a5ce7c8c3080cb186662b`；主目录raw已有副本 |
| `%TEMP%/dex-tools-v2.4.zip` | 从APK重新生成转换jar；`ee7c45eb3c1d2474a6145d8d447e651a736a22d9664b6d3d3be5a5a817dda23a` |
| `%TEMP%/ninebot-kcp-dex-tools/dex-tools-v2.4/` | 上述发行包完整解压目录；包含d2j-dex2jar.bat及依赖，不应只留一个bat |
| 该目录 `lib/asm-9.5.jar` | H.264/JPEG切片器依赖；`b62e84b5980729751b0458c534cf1366f727542bb8d158621335682a460f0353` |
| `%TEMP%/ninebot-pro-kcp-interop/ikcp.c` | 上游commit `7346e366f1928dd727ecbb53253454fdaecaebb7`；`8654038a70d1bbe605aa94f066554ffb30bf28580a58769970221de3ba5027f0` |
| `%TEMP%/ninebot-pro-kcp-interop/ikcp.h` | 同commit；`d67a0e67ba5701f1e882894b4415c4d3ad3d7d9f7231dd2ca68c6037c5217933` |
| KCP LICENSE（建议再取固定commit） | 留存第三方许可证；上游C源码自身也含版权注释 |

本次编译工具为Android Studio JBR OpenJDK 21.0.9 `21.0.9+-14787801-b1163.94`，gcc为MinGW-W64 UCRT/posix/seh 16.1.0。脚本应记录或允许覆盖JavaBin和CCompiler；不一定要复制整套JBR/gcc。`-Xverify:none`是当前dex2jar输出缺stackmap的兼容措施，应继续写明。输出class与exe可随时由源重建，无需冻结它们作为真相来源。

`run-validation.ps1`依赖TranslatedJar、JavaBin，产物在TEMP；`run-interop.ps1`还依赖gcc、curl、可用代理和上游源。冻结源以后推荐优先读本地SourceDir，下载作为显式备选；校验固定哈希后编译。现有interop-validation.json已经含完整合成报文hex，所以无需依赖TEMP的.bin保留测试向量。

## 审查结论

未发现第9/11/13节KCP协议的实质性错误。发布总文档前应同步H.264最新计数、完善UNA方向，并保存/接线本地复跑依赖。冻结路径和工具是复现可靠性问题，不改变已完成的原方法结果与证据边界。
