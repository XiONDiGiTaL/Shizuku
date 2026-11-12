# Features

Status legend: **verified on device** means it was exercised on the SM-S918U running this build;
**implemented** means the code path, UI and error reporting exist but the destructive or
hard-to-restore action was not executed during testing.

## Service control

| Feature | Status |
|---|---|
| Start / stop / restart from the dashboard | verified on device (start via ADB starter, stop, restart path) |
| Reconnect to a running service | implemented |
| Privilege and port reporting (`ADB · API 13`, `uid 2000`, port) | verified on device |
| Boot start, Wi-Fi-aware start, TCP mode, stealth mode | implemented (upstream paths preserved) |
| Start/stop intents and quick-settings tiles | implemented |

## Authorisations

| Feature | Status |
|---|---|
| Real authorised list with per-app revoke | verified on device (23 apps listed) |
| States: authorized / denied / not requested / uninstalled / unknown | implemented, list rendering verified |
| Independent cross-check against the package manager | verified on device (23 reported vs 24 holding the permission) |
| Reporting-failure diagnosis instead of a false zero | implemented; the mismatch branch is shown only when it occurs |

## Tools

| Feature | Status |
|---|---|
| Command center with presets, exit codes, duration | verified on device (`pm list packages`, `dumpsys`, `getprop`, `settings`) |
| Package manager: disable / enable / clear data / uninstall | implemented; not executed (destructive) |
| System tweaks: animation scales, timeouts, ADB toggles | implemented; not executed (changes device state) |

## Diagnostics

| Feature | Status |
|---|---|
| Application, device, One UI and service sections | verified on device |
| Wireless debugging, authorisations, resources, package inventory | verified on device |
| Export report / copy report | implemented (export uses the system file picker) |

## Automation

| Feature | Status |
|---|---|
| Control intents with auth token | implemented; the screen shows the live token and a test broadcast button |
| Event broadcasts | implemented |
| Watchdog toggle via intent | implemented |

## Watchdog 2.0

| Feature | Status |
|---|---|
| Binder health loop, exponential backoff, per-window attempt cap | implemented |
| Restart history, recovery/gave-up notifications, battery awareness | implemented |
| Automation events on restart | implemented |
| Keep-alive behaviour across reboot | not tested |

## Backup

| Feature | Status |
|---|---|
| Export / import this app's own settings and watchdog history | implemented; not executed during testing |
| Authorisations | deliberately excluded (Android does not allow an app to read or restore them) |

## Preserved fork features

Boot start, Wi-Fi-aware start, TCP mode, watchdog, start/stop intents, stealth mode, in-app
updates, TV/VR support and MediaTek compatibility. Documentation and issue references for these
remain in the upstream project this fork is based on.
