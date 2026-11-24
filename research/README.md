# 独立协议向量与历史研究工具

`gen2/` 是独立于生产 Java/C++ 的 Python AES 参考实现及 53 个合成向量；所有密钥和序列号均为合成数据。

```sh
python -m pip install cryptography
python research/gen2/generate_probe_vectors.py --check
```

Gen2、Gen3、KCP、RTP 和会话回归入口见 `modules/ninebot-cast-android/src/test/cpp`，只需公开依赖，无需官方二进制。

`docs/research/archive/` 保留研究日期、对照结果和原作者的分析脚本。历史 emulation/静态分析工具需要自行提供所研究的原始 SDK 文件，可能依赖 Unicorn、Capstone、pyelftools、cryptography；未附官方 APK/SO、反编译原件或私人抓包。文件名与哈希仅用于识别来源，不表示材料已随附。最新实现、实车验证边界以首页为准。
