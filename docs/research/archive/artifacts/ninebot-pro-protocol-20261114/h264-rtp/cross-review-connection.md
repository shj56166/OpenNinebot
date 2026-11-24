# H.264/RTP 代理对连接、认证及配置研究的交叉复核

复核日期：2026-11-14。审阅 `../connection-auth/notes.md`、`run_validation.py` 和 `validation.json`，独立对照同一 APK 原 `x32/hd1/l21/d02`、`vc1`、`MirrorActivity`、`o31`；对自动反编译损坏的 `l21.j` 异常控制流另读取 dex2jar 原方法 `javap -c -p l21`。没有修改对方文件，也没有执行 Android P2P 或接触车辆。

## 审阅结果

未发现需要推翻连接/认证报告的矛盾。各项结论及证据边界如下。

| 审阅项 | 独立核对结果 | 主要证据 |
| --- | --- | --- |
| 扫描目标的名称与MAC | 名称trim、MAC trim后小写；名称精确且区分大小写，完整MAC忽略大小写。匹配没有加密挑战、证书、云密钥 | `x32` 构造与 `z`；`hd1.c/d` |
| 自动/手动的已有组 | 无组即扫描；匹配所选目标的组复用并重查；不同组时 auto=true 调 `b` 提示且不removeGroup，auto=false 执行removeGroup，成功回调再扫描 | `l21.a` 原匿名GroupInfoListener；`vc1` case2 |
| 手机与仪表角色 | group的本地GO或owner为空均拒绝；MAC正确后owner名称或networkName任一须精确匹配；连接信息还要求groupFormed、手机不是GO、GO地址是IPv4 | `hd1.d/g` |
| 路由与端口 | 从group接口查手机非回环IPv4，再按接口名查可选Network；媒体/控制均绑定选定本地IPv4和同号5012/5013，连接GO动态IPv4 | `hd1.g`；`o31.c` |
| 异步边界 | generation过滤旧回调；500ms等待路由就绪；连接30秒仍connecting则重置并cancelConnect；启动尝试一次而非无限重连 | `hd1.a/b/f/g`；`vc1` case1；`MirrorActivity.k` |
| 存储配置与实际transport | `d02.B`的6/8字段缺省Direct，9字段读取transport；`F`原样返回。JPEG配置即使存KCP，`o31.a`仍先调用JPEG的`b()`并return；不能据UI/存储值认定JPEG经过KCP | `d02.B/F`；`o31.a` fallback235..238；本代理原 `sd.l` 另验证H264的真实KCP入队 |
| 单个目标的JPEG缺省 | 缺profile时只对内置精确名称+MAC组合选`E()`，其余选`C()`；无效配置异常后也重复相同选择。没有尺寸探测或协议协商 | `l21.j`原转换字节码0..160；`d02.C/E` |
| 独立媒体实现与车辆认证 | 可见媒体路径由P2P、Android编码和Java RTP/KCP完成；未发现官方投屏SO或认证流程。P2P名称/MAC检查只能证明与本地所选目标一致，不能作为车辆密码学认证 | 可见调用链；APK SO/asset清单；manifest权限；README外部开启前提 |

`l21.j` 的JADX自动文本把catch后错误包装与后续fallback位置恢复得不可直接编译。原转换方法的异常表明确把0..87及缺app路径导向90，再在102构造`in1`，111执行`ln1.a`；失败时121开始日志并在127..153按同一目标判定重选JPEG/H264。这支持主报告“缺失或解析失败使用同一单目标特例”的表述。没有将这个固定测试目标解释为仪表型号自动识别，也没有在本文件重复其原始标识。

## 原方法验证的覆盖范围

审阅34条断言：实际执行原 `x32` 构造/匹配、`hd1.a/d`、`d02` 构造/解析/preset/level/F。Android group/device用值对象替代，`hd1`构造被Unsafe跳过，枚举/helper被桩化。`validation.json`已经如实说明这些约束。

这些34条并未动态执行`l21.a/j`、P2P组操作、定时器或`MirrorActivity.k`；相应结论是独立静态控制流审核，不应把它们写作“34项已验证完整建组”。本轮没有重复运行同一34条，也没有追加虚计测试数量。

APK ZIP独立列举仅有 `libandroidx.graphics.path.so`、`libimage_processing_util_jni.so`、`libsurface_util_jni.so` 三种SO名称，四ABI；assets只有dexopt profile文件。manifest权限快照不含Bluetooth系列；全JADX可见`System.loadLibrary`调用指向AndroidX图形/图像库。这些证据结合媒体调用链，支持“此APK的可见投流实现不借用官方九号SO”。它们不能代替实车证明，也不能推断车端不用认证：仪表进入投屏模式仍是外部前提，BLE开投屏/车辆授权能力不在本APK流程中。

## 与本代理和JPEG分支交叉结果

原 `sd.l` KCP入队补验与此报告的transport结论一致：只在H264入口构造 `ks0` 后才发生RTP二次分段。JPEG代理的 `o31.b` 与本代理对 `o31.a` 的fallback审核均证明JPEG早返回。共享`sd.i`只接受20字节反馈，与JPEG自身16字节`mpv`请求存在识别格式覆盖差异；该问题发生在媒体控制平面，和车辆认证不是同一分支。

后续实车仍须验证仪表开投屏所需前提、实际GO地址和名称、各尺寸固件接受的媒体/反馈形式。此审阅未提供这些外部状态的成功证据。
