<div align="center">

# Shizuku Reimagined

**[XiØN](https://github.com/XiONDiGiTaL)** · a modernised Shizuku — dark-first interface, honest authorisation reporting,
built-in privileged tools, and a fix for the One UI 9.0 "Authorized 0 applications" bug.

`net.xiondigital.shizuku` · v2.0.0-xion · Android 7.0+ · installs beside the official Shizuku

</div>

![Dashboard](screenshots/dashboard.png)

## What this is

Shizuku lets apps use system APIs that normally require ADB or root, by running a small service
as the shell user and handing apps a binder to it. This fork keeps that engine, rebuilds the
experience around it, and adds the parts you normally reach for a terminal to do.

It is a separate installation from the official Shizuku: its own package, name and signing key,
its own service process and its own configuration file. Your existing Shizuku and every app
authorised for it stay exactly as they are.

## Highlights

**Authorisations you can trust.** The screen lists what the service actually reports, and
cross-checks it against what Android itself says. When those disagree — the One UI 9.0 symptom —
it shows a reporting failure with both numbers instead of a calm, wrong `0`.

**Real privileged tools.** A command center that runs shell commands as the service's uid and
reports the true exit code, a package manager for third-party apps, and system tweaks with
read-back.

**Diagnostics that answer questions.** Application identity, device and One UI build, service
state and privilege, wireless-debugging status, authorisation counts, storage and memory — with a
report you can copy or export.

**Automation that is documented.** Start, stop, restart and watchdog intents plus service events,
with the auth token shown in the app and a test broadcast button.

**A watchdog that behaves.** Binder health checks, exponential backoff, an attempt cap per window,
restart history and notifications that distinguish "recovered" from "gave up".

<table>
<tr>
<td><img src="screenshots/authorizations.png" alt="Authorizations"></td>
<td><img src="screenshots/tools.png" alt="Tools"></td>
</tr>
<tr>
<td align="center"><em>Authorized apps with a live cross-check</em></td>
<td align="center"><em>Package manager, tweaks, command center</em></td>
</tr>
</table>

<table>
<tr>
<td><img src="screenshots/command-center.png" alt="Command center"></td>
<td><img src="screenshots/diagnostics.png" alt="Diagnostics"></td>
</tr>
<tr>
<td align="center"><em>Shell with real exit codes</em></td>
<td align="center"><em>Device, service and report export</em></td>
</tr>
</table>

<table>
<tr>
<td><img src="screenshots/automation.png" alt="Automation"></td>
<td><img src="screenshots/backup.png" alt="Backup and restore"></td>
</tr>
<tr>
<td align="center"><em>Intents, events and auth token</em></td>
<td align="center"><em>Export and import app settings</em></td>
</tr>
</table>

## The One UI 9.0 bug

On Android 17 / One UI 9.0, Shizuku 13.6.0 lists **"Authorized 0 applications"** while apps keep
working. Android 17 changed the signature of the hidden `getInstalledPackages` call the server
uses, so the package list comes back empty; authorisation itself is fine. Upstream fixed it in
`e468d70a` by routing every query through `InstalledPackagesCompat` — the fix this build is based
on.

Measured on an SM-S918U (Android 17, One UI 9.0): the official 13.6.0 shows 0, while this build
lists 23 authorised apps and reports that Android counts 24 holders of the permission (the
official manager is the extra one). Full write-up and evidence: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Coexistence with the official Shizuku

| | Official | This build |
|---|---|---|
| Package | `moe.shizuku.privileged.api` | `net.xiondigital.shizuku` |
| Service process | `shizuku_server` | `xion_shizuku_server` |
| Service config | `shizuku.json` | `net_xiondigital_shizuku.json` |
| Client binder fan-out | yes | disabled |

The starter accepts `--server-name=`, so this build's service never replaces the official one.
Both can run at the same time; each keeps its own clients and its own configuration.

## Install

```bash
adb install -r shizuku-v2.0.0-xion-debug.apk
```

Open the app and tap **Start** (wireless debugging or root), or start the service from ADB:

```bash
adb shell <nativeLibraryDir>/libshizuku.so \
  --server-name=xion_shizuku_server \
  --apk=<base.apk>
```

Step-by-step, including how to verify that both services are alive: [docs/INSTALL.md](docs/INSTALL.md).

## Automation

```bash
adb shell am broadcast -a net.xiondigital.shizuku.START  -p net.xiondigital.shizuku --es auth <token>
adb shell am broadcast -a net.xiondigital.shizuku.STOP   -p net.xiondigital.shizuku --es auth <token>
adb shell am broadcast -a net.xiondigital.shizuku.RESTART -p net.xiondigital.shizuku --es auth <token>
adb shell am broadcast -a net.xiondigital.shizuku.WATCHDOG -p net.xiondigital.shizuku \
  --es auth <token> --ez enabled true
```

Events (`...EVENT.SERVICE_STARTED`, `SERVICE_STOPPED`, `SERVICE_CRASHED`, `SERVICE_RESTARTED`,
`WIRELESS_DEBUGGING`) are broadcast to this package. The token is displayed in **Automation**.
Reference: [docs/AUTOMATION.md](docs/AUTOMATION.md).

## Build

JDK 21, Android SDK 37, NDK 29, CMake 3.31:

```bash
./gradlew :manager:assembleDebug
```

Output: `manager/build/outputs/apk/debug/shizuku-v2.0.0-xion-debug.apk`.
Signing, native flags and the version scheme: [docs/BUILDING.md](docs/BUILDING.md).

## Verified on device

Everything below was exercised on a Samsung SM-S918U (Android 17, SDK 37, One UI 9.0) over ADB:

- builds clean; installs beside the official Shizuku (`pm list packages` shows both);
- service started as `xion_shizuku_server` while the official `shizuku_server` kept running
  untouched, with an unchanged official config file (same md5 before and after);
- dashboard reports `Service running`, `ADB · API 13`, `uid 2000`, port 5555;
- authorisations: 23 apps listed, cross-check "Android reports 24 holding the permission";
- command center executed real shell commands and returned their exit codes;
- diagnostics collected application, device, service, wireless, authorisation and resource data;
- every screen in the screenshots above was opened and inspected on the device.

Not exercised: package-manager actions, system tweaks, backup import/export, watchdog restarts,
quick-settings tiles, boot start, TV layout, root start. Those are implemented and report their
real result, but they were not run during this round. [docs/FEATURES.md](docs/FEATURES.md) lists
each item with its status.

## Documentation

| Document | Contents |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Install, start, verify coexistence, uninstall |
| [docs/FEATURES.md](docs/FEATURES.md) | Every feature with its verification status |
| [docs/AUTOMATION.md](docs/AUTOMATION.md) | Intents, events, tiles, token handling |
| [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md) | What the report contains and how to export it |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | The One UI 9.0 issue, evidence, Samsung limits |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common failures and what to check |
| [docs/BUILDING.md](docs/BUILDING.md) | Toolchain, signing, native starter |
| [CHANGELOG.md](CHANGELOG.md) | What changed in 2.0.0-xion |

## Credits

Built on [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), itself a fork of
[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). The service, the API and the privilege
model are theirs; the interface, tooling, authorisation reporting, watchdog and documentation in
this fork are [XiØN](https://github.com/XiONDiGiTaL)'s.

## Licence

The upstream project is licensed under Apache 2.0; this fork keeps the same licence. See
[LICENSE](LICENSE).
