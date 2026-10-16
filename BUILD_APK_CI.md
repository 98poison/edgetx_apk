# 构建 APK（GitHub Actions）

本目录的 `build-apk.yml` 在干净的 GitHub 运行器上构建 APK，本机不用装任何工具链。

## 结论：用已在旧 APK 里验证过的那颗库（prebuilt 模式）

APK 的界面是**真正的 EdgeTX 固件**交叉编译成的 `libedgetx-st16mk3-simulator.so`。
你手上有旧版 `EdgeTX.apk`，里面那颗库就是**作者自己编的**，已经抽出来并逐符号核对过：

* 文件：`.edgetx/libedgetx-st16mk3-simulator.so`（11.0 MB，AArch64）
* 28 个**必需（非 weak）**符号 **全部齐全**
* 关键的 **`edgetxAndroidSetAuxSerialSink` 也在** —— 也就是**高频头串口通路是通的**
* 另外 `edgetxAndroidSetBattery / SetSwitch / ExternalModuleType / BatteryMillivolts /
  BatterySettings / SetHostAudioPending / simuGetHaptic / lcdRequestFullRefresh` 也都在

所以**不需要重编 EdgeTX**，用 `-Pedgetx.skipBuild=true` 直接打包即可。

### 唯一改过的一处

旧库**没有 `simuTouchCancel`**，而 App 原来把它声明成非 weak，会导致动态库加载失败。
已改成 weak + 回退到库里有、语义相同的 `simuTouchUp()`：

* `app/src/main/cpp/simu_host.h` —— `simuTouchCancel()` 加 `weak`
* `app/src/main/cpp/simu_host.cpp` —— `touchCancel()` 缺失时调用 `simuTouchUp()`

### 已知的两个小缺失（都不影响主要功能）

* `edgetxAndroidSetGps / GetGps` —— 旧库没有（App 里本来就是 weak）。影响：遥控器自身 GPS
  不会进固件。按钮/摇杆/高频头/网络发送都不受它影响。
* `simuTouchCancel` —— 见上，已在 App 侧兜底。

## 怎么跑

1. 把整个 `edgetx_apk-main`（含 `.edgetx/libedgetx-st16mk3-simulator.so`）弄到你自己的
   GitHub 仓库。本机没装 git，最省事的是 **GitHub Desktop**；也可以在 GitHub 网页
   仓库页用 "Add file → Upload files" 拖文件夹上去。`.gitignore` 不会挡 `.edgetx/`。
2. 仓库 **Actions** 页面点 **Build APK**（首次可能要允许 workflow 运行），参数：
   * `edgetx_mode = prebuilt`
   * `sim_lib_url` 留空（走仓库里的 `.edgetx/`）
   * `dji_msdk` 按需要勾（5-way 方向键要它；勾了 APK 每 ABI 大约 +60 MB）
3. 跑完在运行页面的 artifact 下载 **edgetx-apk**。

工作流做的事：装 JDK/SDK/NDK/CMake → 把 `.edgetx/` 里的库放到
`app/build/edgetx-libs/arm64-v8a/` → `gradlew :app:assembleRelease -Pedgetx.skipBuild=true`。

## 备选：`source` 模式

只有当你想用公开 fork 自己编 EdgeTX 时才需要（比如想补齐 GPS）。
`patches/` 里是兼容补丁与说明：`patches/README.md`。默认参数：
`edgetx_repo=https://github.com/cpp2go/edgetx_esp32`，
`edgetx_ref=793e11364f30ca39acfbc153a83da093f8e32c81`。

## 工具

`tools/extract-sim-lib.ps1` —— 从任意 APK 里抽出 `libedgetx-*-simulator.so` 并校验
ELF 与宿主符号，用法见脚本头部注释。
