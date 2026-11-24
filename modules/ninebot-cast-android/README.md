# Ninebot Android protocol core

Independent Gen2 / Gen3 BLE authentication, read-only preparation, vehicle telemetry and instrument casting. This source module contains no application trial lease. `CastEngine` serializes host actions; Android permissions, GATT operations, Wi-Fi routing and frame capture remain with the host.

Link `native-core.mk` into the `ninebot_cast` native library with the pinned mbed TLS, cJSON and MPEG2-only FFmpeg dependencies. Load it through `NativeRuntime.load("ninebot_cast")`. Complete each returned transport action once; a local send callback is not evidence of a displayed frame.

Gen3 first authorization and refresh use `NinebotCredentialProvider.Authorization`; the owner supplies valid vehicle credentials and a lawful authorization adapter. This vehicle authentication is separate from application access. Material is persisted only after verification and matching preparation replies.

Prepared models currently include 14101, 14357 and 14360. Unknown models do not inherit guessed command tables. Screen size, authentication generation, codec and transport are independent. Consult the application README for tested and unverified behavior.

Host C++ regression tests are in `src/test/cpp`. Frozen vectors are synthetic test cases and source-evidence summaries; vendor binaries are not needed to run these tests.
