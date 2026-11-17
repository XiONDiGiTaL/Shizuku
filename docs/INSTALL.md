# Installing Shizuku Reimagined

## What you are installing

| | Official Shizuku | Shizuku Reimagined (this build) |
|---|---|---|
| Package | `moe.shizuku.privileged.api` | `moe.shizuku.privileged.api` |
| Name | Shizuku | Shizuku Reimagined |
| Signing key | upstream | independent |
| Service config | `/data/user_de/0/com.android.shell/shizuku.json` | same file |
| Server process | `shizuku_server` | `shizuku_server` |

This build **replaces** the official app. It uses the same package id, because every Shizuku client
app hardcodes that id when it looks for the manager; a different id is never discovered, and client
apps report "binder haven't been received". The signing key is different, so Android will not update
the official app in place:

1. Uninstall the official Shizuku.
2. Install this APK.
3. Start the service, then authorise your apps here. Grants already in the shell config survive.

## Requirements

- Android 7.0 (API 24) or newer; targetSdk 37.
- One of the supported ways to start the service: **wireless debugging** (Android 11+),
  **root**, or **ADB over USB**.

## Install

```bash
adb install -r ShizukuReimagined.apk
```

The release and debug builds both use that stable file name (`ShizukuReimagined.apk` and
`ShizukuReimagined-debug.apk`), so the command is identical for every version. On a phone without
a computer, install the APK straight from the release page instead.

The debug APK produced by the build is signed with a local development key
(`keystore/xion-dev.jks`, alias `xiondev`); that file and `signing.properties` are deliberately
not committed. Without them the build falls back to the standard debug keystore. Either way it is
a development key: do not treat its signature as a security boundary.

## Start the service

1. Open **Shizuku Reimagined**.
2. Tap **Start**. With wireless debugging enabled the app walks through pairing; with root it
   starts immediately; otherwise run the script the app writes, from a computer:

   ```bash
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```

   This mirrors the official Shizuku's `start.sh`: the app writes the script at launch with its own
   native library and APK paths, so there are no placeholders to fill in and no line continuations
   (PowerShell rejects `\`). The path is also shown in Diagnostics → Application → *ADB start script*,
   and the script is rewritten on every launch because those paths change when the app updates.

   The script passes `--server-name=shizuku_server`, which is what keeps this service separate
   from a running official Shizuku server.
3. The dashboard switches to **Service running** and shows the real privilege level
   (`ADB · API 13`, `uid 2000`) and port.

## Verify it is running

```bash
adb shell ps -A -o USER,PID,NAME | grep shizuku_server
# shell 11875  shizuku_server     <- this build
```

## Replacing the official app

Because the package id is the same, the two cannot be installed at once: uninstall the official app,
then install this build. The authorisations themselves live in the shell user's config file, so a
swap does not lose them.

The signing keys differ, so this can never be installed as an update over the official app, and the
official app can never be installed as an update over this one. Uninstall first, every time.

## Uninstall

Uninstalling this app removes the package and its data. The authorisation state in
`/data/user_de/0/com.android.shell/shizuku.json` belongs to the shell user and stays behind, so a
later official install finds the grants again.

## First run after install

- Authorisations belong to a package, so apps that were authorised for the official Shizuku do
  not gain access to this build automatically. The Authorizations screen shows the permission
  state Android reports for every app, which is useful for verifying the Android 17 reporting
  fix (see `docs/COMPATIBILITY.md`).
