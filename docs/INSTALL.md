# Installing Shizuku Reimagined

## What you are installing

| | Official Shizuku | Shizuku Reimagined (this build) |
|---|---|---|
| Package | `moe.shizuku.privileged.api` | `net.xiondigital.shizuku` |
| Name | Shizuku | Shizuku Reimagined |
| Signing key | upstream | independent |
| Service config | `/data/user_de/0/com.android.shell/shizuku.json` | `.../net_xiondigital_shizuku.json` |
| Server process | `shizuku_server` | `xion_shizuku_server` |

Both can be installed at the same time. This build never modifies, replaces or uninstalls the
official app, and it does not touch the official app's stored authorisations.

## Requirements

- Android 7.0 (API 24) or newer; targetSdk 37.
- One of the supported ways to start the service: **wireless debugging** (Android 11+),
  **root**, or **ADB over USB**.

## Install

```bash
adb install -r shizuku-v2.0.0-xion-debug.apk
```

The debug APK produced by the build is signed with a local development key
(`keystore/xion-dev.jks`, alias `xiondev`); that file and `signing.properties` are deliberately
not committed. Without them the build falls back to the standard debug keystore. Either way it is
a development key: do not treat its signature as a security boundary.

## Start the service

1. Open **Shizuku Reimagined**.
2. Tap **Start**. With wireless debugging enabled the app walks through pairing; with root it
   starts immediately; otherwise run the command shown under Diagnostics with ADB:

   ```bash
   adb shell <nativeLibraryDir>/libshizuku.so --server-name=xion_shizuku_server --apk=<base.apk path>
   ```

   The `--server-name` argument is what keeps this service separate from a running official
   Shizuku server.
3. The dashboard switches to **Service running** and shows the real privilege level
   (`ADB · API 13`, `uid 2000`) and port.

## Verify coexistence

```bash
adb shell ps -A -o USER,PID,NAME | grep shizuku_server
# shell  9355  shizuku_server          <- official, untouched
# shell 11875  xion_shizuku_server     <- this build
```

## Uninstall

Uninstalling this app removes only its own installation and its own service configuration.
The official Shizuku app and its authorisations are unaffected.

## First run after install

- Authorisations belong to a package, so apps that were authorised for the official Shizuku do
  not gain access to this build automatically. The Authorizations screen shows the permission
  state Android reports for every app, which is useful for verifying the Android 17 reporting
  fix (see `docs/COMPATIBILITY.md`).
