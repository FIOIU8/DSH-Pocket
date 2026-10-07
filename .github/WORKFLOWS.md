# GitHub Actions

`android-build.yml` runs on every push. It collects an arm64 Android runtime, runs unit tests and lint, builds a debug APK, and uploads that APK as a 14-day workflow artifact.

`release-draft.yml` is started from Actions > Draft Android release > Run workflow. Enter an app version such as `v1.0.0` and an optional DSH offset: blank or `0` selects the latest published DSH release, `-1` the previous release, and `-2` the one before that. Selection uses published `dsh-v*` releases from `deepseek-ai/deepseek-harness`, including prereleases, ordered by publication time. The selected version is checked against npm before packaging.

The run number becomes Android `versionCode`; the entered app version (without its leading `v`) becomes `versionName` and the release tag. GitHub generates release notes from the changes since the previous app release, with the selected DSH version and official release link added at the top. The DSH version is also included in the draft title and APK filename. Review the signed arm64 APK and draft notes, then publish it manually.

Before running a release, add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded, long-lived Android keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing key alias.
- `ANDROID_KEY_PASSWORD`: signing key password.

Keep the same keystore for every release so users can install updates over earlier versions. For example, encode a keystore on Linux/macOS with `base64 -w 0 release.jks` and save the output as `ANDROID_KEYSTORE_BASE64`. The automatic push build uses a temporary debug key and does not use release signing secrets.
