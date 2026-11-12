# Changelog

All notable changes to this fork are documented here. The fork continues the work of
[thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) (13.7.0) under the [XiØN](https://github.com/XiONDiGiTaL) identity.

## 2.0.0-xion — Shizuku Reimagined

### Identity and coexistence

- Branding is **XiØN**; the brand mark links to https://github.com/XiONDiGiTaL and every project
  link in the app (source, issues, releases, wiki, in-app updater) points at
  https://github.com/XiONDiGiTaL/Shizuku.

- New application id `net.xiondigital.shizuku`, display name **Shizuku Reimagined**, independent
  signing key. It installs and runs beside the official `moe.shizuku.privileged.api` app.
- The development build keeps the client-facing permission name
  `moe.shizuku.manager.permission.API_V23`, but no longer redeclares the permission or its group
  (owned by the official app, which made the install fail before).
- The service uses its own signature permission name and skips the client binder fan-out, so it
  never hands its binder to apps authorised by the official installation.
- The service stores its configuration in a namespaced file
  (`/data/user_de/0/com.android.shell/net_xiondigital_shizuku.json`) instead of `shizuku.json`.
  The official installation's file is never read or written.
- The native starter takes `--server-name=`; the development build names its server process
  `xion_shizuku_server`. Previously the starter killed every process called `shizuku_server`
  before starting, which took down the other installation's running service.

### Authorisation reporting (One UI 9.0 / Android 17)

- New Authorizations screen that distinguishes **authorized**, **denied**, **not requested**,
  **previously authorised but no longer installed** and **unknown / unreadable**.
- Independent cross-check against the package manager: if Android reports N apps holding the
  permission while the service lists none, the screen shows a reporting failure instead of a
  misleading zero, together with a diagnosis and the exact counts.
- Empty results are never rendered as a bare "0": the card explains that the list could not be
  read and why.

### User interface

- New dark-first design system (XION palette, Sora/Instrument Serif type pairing, 18 dp cards,
  One UI-style controls) with a full light theme, and layouts that adapt to phone, tablet and TV.
- New dashboard: service state hero with honest privilege/port detail, Start / Stop / Restart /
  Reconnect / Diagnose, authorised-app and watchdog metrics, and rows for every screen.
- New screens: Authorizations, Tools (package manager, system tweaks, command center),
  Diagnostics, Automation, Backup & restore. The original home screen stays available as
  "Classic dashboard".
- The upstream "duplicate app" guard no longer blocks the classic dashboard in the development
  identity. It compared the owner of the Shizuku permission with the running package, which can
  never match here because the official app owns that permission; the guard now applies only to
  the official identity and the classic screen opens normally.
- No third-party project links remain: help, setup, app-list, download, translation and support
  targets all resolve to this fork's repository, the translation/Crowdin entry and the upstream
  support e-mail are gone, and the "Buy me a coffee" button was removed from the about dialog.

### Functionality

- **Command center**: runs shell commands through the service with ADB privileges, presets,
  copy/clear, and honest exit codes and duration.
- **Package manager**: search third-party packages, disable / enable / clear data / uninstall,
  each executed through the service with the result reported back.
- **System tweaks**: animation scales, screen-off timeout and ADB-related toggles with read-back.
- **Diagnostics**: application, device (including One UI build), service, wireless debugging,
  authorisations, resources and package inventory, exportable as a text report.
- **Automation**: documented control intents (`START`, `STOP`, `RESTART`, `WATCHDOG`) and event
  broadcasts (`SERVICE_STARTED`, `SERVICE_STOPPED`, `SERVICE_CRASHED`, `SERVICE_RESTARTED`,
  `WIRELESS_DEBUGGING`), with an auth token shown in the app.
- **Backup & restore**: export and import this app's own settings, watchdog history and startup
  preferences. Authorisations are deliberately not included — Android does not allow an app to
  read or restore them.
- **Watchdog 2.0**: binder health loop with exponential backoff, per-window attempt cap, restart
  history, recovery/gave-up notifications, battery awareness and automation events.

### Preserved fork features

Boot start, Wi-Fi-aware start, TCP mode, start/stop intents, stealth mode, in-app updates,
TV/VR support and MediaTek compatibility all keep working; see `docs/FEATURES.md`.

### Fixed

- Shell commands never returned: `ShizukuRemoteProcess` does not implement the timed
  `Process.waitFor` overload used by earlier code, so every command ended as "process hasn't
  exited". Commands now use the API's own `waitForTimeout`.
- The manager treated its own missing runtime permission as "service unavailable", disabling
  every tool even though the service always trusts its manager.
