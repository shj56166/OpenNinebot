# Third-party notices

The MIT license at the repository root applies to the project's own work. It does not replace third-party licenses or grant rights to vendor trademarks, applications, or binary artifacts.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| KCP | Pinned source in the native module | MIT | https://github.com/skywind3000/kcp |
| cJSON | 1.7.19 | MIT | https://github.com/DaveGamble/cJSON |
| mbed TLS | 3.6.4 | Apache-2.0 OR GPL-2.0-or-later | https://github.com/Mbed-TLS/mbedtls |
| FFmpeg | 7.1.1, MPEG2VIDEO encoder only | LGPL-2.1-or-later | https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz |
| AndroidX | Versions in Gradle files | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |

License texts are included in the application assets and upstream source packages. Modified KCP source retains its upstream notice and provenance. The release codec source and relink archive contains the exact FFmpeg source archive, build configuration, static codec libraries, non-codec link objects and relinking instructions. Replacing a native library in a signed APK requires rebuilding and signing the APK with your own key.

Research builds on public work including NootNooot/segway-ninebot-ble, Ninebot Pro and NinebotEnhance. Research documents retain source references and dates. Referenced third-party implementations remain under their original licenses; vendor applications, libraries and decompiled source are not redistributed.
