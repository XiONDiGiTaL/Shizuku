# Building from source

## Toolchain

| Component | Version used for the verified build |
|---|---|
| JDK | 21 |
| Android SDK platform | 37 (`ANDROID_HOME` with `platforms;android-37.0`) |
| Build tools | 37.0.0 |
| NDK | 29.0.13113456 |
| CMake | 3.31.0 |
| Gradle | via the included wrapper |

Point the build at the SDK in `local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

On Windows use forward slashes; a backslash escaped value is read literally and breaks the
build.

## Debug APK

```bash
export JAVA_HOME=/path/to/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :manager:assembleDebug
```

Output: `manager/build/outputs/apk/debug/ShizukuReimagined-debug.apk` (release:
`manager/build/outputs/apk/release/ShizukuReimagined.apk`; versioned copies land in `out/apk/`).

## Signing

`signing.gradle` reads `signing.properties` from the repository root when it exists:

```properties
storeFile=keystore/xion-dev.jks
storePassword=...
keyAlias=xiondev
keyPassword=...
```

Paths are resolved against the repository root, so the build works from any directory. Without
the file the build falls back to the default debug keystore.

## Version string

`build.gradle` sets `baseVersionName = "2.0.1"` and appends `-xion`; the version code is the
commit count, so every build is uniquely identifiable in Diagnostics.

## Native components

The starter (`manager/src/main/jni/starter.cpp`) is compiled into `libshizuku.so` and parses:

- `--apk=<path>`, manager APK whose secondary dex holds the server;
- `--server-name=<name>`, process name of the server to manage and replace. The release build
  passes `shizuku_server`, the name client apps and the starter expect.

## Notes

- The server module's `BuildConfig.DEV_IDENTITY` is derived from the manager applicationId. With
  `moe.shizuku.privileged.api` it is false, which enables the client binder fan-out, the
  `shizuku_server` name and the shared `shizuku.json`.
- `:manager:assembleDebug` builds the server dex into the manager APK; no separate server
  install is needed.
