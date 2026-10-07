# DSH Pocket

**把 DeepSeek Harness 带到 Android 手机上的原生客户端。** DSH Pocket 在设备上启动 Node.js 运行时和 DSH Web 服务，并提供原生 Compose 对话界面及管理入口。

**A native Android client for DeepSeek Harness.** DSH Pocket starts a Node.js runtime and the DSH web service on-device, with a native Compose chat interface and management screens.

> DSH Pocket uses Android `minSdk 24` and deliberately targets API 28 so its bundled runtime can execute from app-private storage. Distribution is currently intended for sideloading, not Google Play.
>
> DSH Pocket 的最低系统版本为 Android 7.0（API 24）。应用刻意使用 `targetSdk 28`，以便在私有目录运行随包提供的运行时；当前面向侧载分发，不面向 Google Play 上架。

## Features / 功能

- Run the DSH Node.js agent engine locally on Android; the engine is managed by a foreground service.
- 原生对话与 DSH 官方 Web 界面可切换；支持流式回复、会话浏览、归档与恢复。
- Configure model providers and agent presets, inspect installed plugins, and manage runtime and capability settings.
- 可选授予所有文件访问权限，让 Agent 在共享存储的 `DSHPocket` 工作区内读写文件；默认使用应用私有工作区。

The engine and its web UI communicate over `127.0.0.1`. Prompts and related context may leave the device when sent to the model provider configured by the user. Filesystem access depends on the Android capability granted to the app and the selected workspace.

引擎及其网页界面通过本机回环地址 `127.0.0.1` 通信。发送给用户配置的模型服务时，对话内容及相关上下文可能离开设备。Agent 可访问的文件范围取决于应用获得的 Android 权限和当前工作区。

## Main dependencies / 主要依赖

| Component | Version | License | Source / package repository |
| --- | --- | --- | --- |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx), Google Maven |
| Jetpack Compose UI, Material 3, icons | Compose BOM 2026.02.01 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx), Google Maven |
| AndroidX Core KTX | 1.19.1 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx), Google Maven |
| AndroidX Lifecycle Runtime KTX | 2.11.0 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx), Google Maven |
| AndroidX WebKit | 1.14.0 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx), Google Maven |
| `@deepseek-ai/dsh` and its npm dependency tree | CI: 0.2.0-rc.2; release: selected by workflow | MIT and other upstream licenses | [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), [npm](https://www.npmjs.com/package/@deepseek-ai/dsh) |
| Node.js LTS (`nodejs-lts`) | Resolved from Termux index at build time | MIT | [Termux recipe](https://github.com/termux/termux-packages/blob/master/packages/nodejs-lts/build.sh) |
| C++ runtime (`libc++`) and OpenSSL | Resolved from Termux index at build time | NCSA; Apache-2.0 | [Termux packages](https://github.com/termux/termux-packages) |
| ICU (`libicu`) and Mozilla CA certificates | Resolved from Termux index at build time | Custom ICU license; MPL-2.0 | [Termux packages](https://github.com/termux/termux-packages), [ICU license](https://github.com/unicode-org/icu/blob/main/LICENSE) |
| `c-ares`, SQLite, zlib, `resolv-conf`, ripgrep, PCRE2 | Resolved from Termux index at build time | MIT; Public Domain; ZLIB; BSD 3-Clause | [Termux packages](https://github.com/termux/termux-packages) |
| Kotlin Compose Gradle plugin | 2.2.10 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin), Gradle Plugin Portal |

Android libraries are resolved through Google Maven (with the Aliyun Google Maven mirror configured first) and Maven Central. Gradle plugins are resolved through Google Maven, Maven Central, and the Gradle Plugin Portal. The Compose BOM manages the versions of Compose artifacts. JUnit and `org.json` are test dependencies, not app runtime libraries.

Android 依赖通过 Google Maven（配置中优先使用阿里云 Google Maven 镜像）和 Maven Central 获取；Gradle 插件还使用 Gradle Plugin Portal。Compose BOM 统一管理 Compose 组件版本。JUnit 与 `org.json` 仅用于测试，不属于应用运行时依赖。

The Node runtime collector currently downloads these Termux packages: `nodejs-lts`, `libc++`, `openssl`, `c-ares`, `libicu`, `libsqlite`, `zlib`, `resolv-conf`, `ca-certificates`, `ripgrep`, and `pcre2`. The exact license identifier for every package is in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md); package versions are resolved from the Termux index at build time. That file also lists the npm package versions, declared licenses, and upstream repositories for the runtime archive.

运行时收集脚本会从 Termux 仓库获取上列系统包；具体版本在构建时从仓库索引解析。当前运行时 ZIP 中 npm 包的版本、许可证声明和仓库链接见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。这些上游组件保留各自许可证，不受本项目 MIT 许可重新授权。

## Build / 构建

### Requirements / 环境

- Android SDK Platform 37 and Android Build Tools suitable for the configured Android Gradle Plugin.
- JDK 25, Python 3.12, Node.js 22, and npm. CI uses these versions.
- `x86_64` or `aarch64` runtime build host target; release workflow currently builds `arm64-v8a` APKs.

需要 Android SDK Platform 37、JDK 25、Python 3.12、Node.js 22 和 npm。CI 使用上述工具版本；本地运行时支持 `x86_64` 和 `aarch64`，发布工作流目前构建 `arm64-v8a` APK。

### Prepare runtime / 准备运行时

`app/src/main/assets/runtime.zip` is generated and ignored by Git. Prepare it before invoking Gradle. The following PowerShell commands prepare an x86_64 runtime with the same DSH version used by the push CI build:

`app/src/main/assets/runtime.zip` 由构建生成且不会提交到 Git。运行 Gradle 前需要先准备它。以下 PowerShell 命令会为 x86_64 准备运行时，并使用 push CI 当前固定的 DSH 版本：

```powershell
python tools/collect_runtime.py fetch --arch x86_64 --work .runtime-work
npm init -y --prefix .runtime-work/bundle
npm install --prefix .runtime-work/bundle --omit=dev --ignore-scripts --no-audit --no-fund @deepseek-ai/dsh@0.2.0-rc.2
python tools/collect_runtime.py pack --arch x86_64 --work .runtime-work --out .runtime-work/runtime-x86_64.zip
Copy-Item .runtime-work/runtime-x86_64.zip app/src/main/assets/runtime.zip
python tools/generate_third_party_notices.py app/src/main/assets/runtime.zip --output THIRD-PARTY-NOTICES.md
```

To build arm64 locally, replace `x86_64` with `aarch64` in the runtime commands and use `-PruntimeArch=aarch64` below. To match a release, install the DSH version selected for that release instead of the CI-pinned version, then regenerate the notices file.

如需本地构建 arm64，请将运行时命令中的 `x86_64` 改为 `aarch64`，并在下方构建命令中使用 `-PruntimeArch=aarch64`。如需与某个正式发布版本对应，应安装该发布选中的 DSH 版本并重新生成第三方清单。

### Build APK / 构建 APK

The local debug signing configuration expects `.keystore/debug.keystore`, which is intentionally ignored by Git. Create the standard Android debug key once in a fresh checkout:

本地 Debug 签名配置使用 Git 忽略的 `.keystore/debug.keystore`。在新检出目录中首次构建前，创建标准 Android Debug 密钥：

```powershell
New-Item -ItemType Directory -Force .keystore | Out-Null
keytool -genkeypair -v -keystore .keystore/debug.keystore -storepass android -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug -PruntimeArch=x86_64
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Release APKs are built by the GitHub Actions draft-release workflow and require repository secrets for a persistent signing key. See [`.github/WORKFLOWS.md`](.github/WORKFLOWS.md).

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。正式 Release APK 由 GitHub Actions 草稿发布工作流构建，需要配置长期使用的签名密钥 Secrets，详见 [`.github/WORKFLOWS.md`](.github/WORKFLOWS.md)。

## Licensing / 许可证

The project-owned source code and assets are licensed under the MIT License; see [`LICENSE`](LICENSE). Third-party components bundled in the APK keep their own licenses and notices. In particular, the runtime includes MPL-2.0 components. The runtime archive retains upstream license files where the package provides them; see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) for the current inventory and known packaging warnings. The MIT license does not replace or relicense third-party components. The DSH name and other third-party marks are not granted by this project license.

本项目自有源代码和资源采用 MIT 许可证，详见 [`LICENSE`](LICENSE)。APK 中打包的第三方组件仍适用各自许可证和声明；运行时包含 MPL-2.0 组件。运行时归档会保留上游包提供的许可证文件；当前清单及已发现的打包问题见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。项目 MIT 许可不会替代或重新授权第三方组件。DSH 名称及其他第三方商标也不由本项目许可证授予。
