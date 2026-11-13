# Platform plan: non-root system management

This document is the working plan for turning this fork into a modular, non-root system-management
platform. It records what exists today, what the audit found, and the honest status of every planned
feature. Statuses use the vocabulary required by the brief:

- **IMPLEMENTED AND TESTED**: built, installed and exercised on a device.
- **IMPLEMENTED BUT NOT DEVICE-TESTED**: built and reviewed, not yet run on hardware.
- **PARTIALLY IMPLEMENTED**: some of the described behaviour works, the rest does not exist.
- **UNSUPPORTED WITHOUT ROOT**: Android does not permit it through Shizuku or public APIs.
- **UNSUPPORTED BY ANDROID**: the platform has no public interface for it at all.
- **NOT IMPLEMENTED**: no code exists.

Branch: `platform/foundation`. Work is staged there and merged only after a phase is verified.

## Non-root policy

The platform runs on three sources of authority only:

1. The Shizuku service, executing as uid 2000 (shell) through ADB or wireless debugging.
2. Android runtime permissions the user grants.
3. Manufacturer or public Android APIs and settings that are open to those two.

**Root is optional and additive.** Existing root support stays, because devices that can use it should
not lose it. The rule is not that root is forbidden, it is that **no feature may require it**: every
screen and every action has to work at uid 2000 (shell) through Shizuku and ADB, and root can only
make the same things easier. A service running as uid 0 is reported as root-privileged in the
capability registry, and the shell-tier probes run against it just the same.

The reference device for this rule is a US Snapdragon Galaxy S23 Ultra, which cannot be rooted. If a
feature needs root there, the feature is broken, not the device.

## Audit of the existing code (evidence)

The fork already ships a substantial amount of Tier 1 functionality. What the audit found, with file
references:

| Area | Finding |
|---|---|
| Root support exists today | `com.github.topjohnwu.libsu:core` is a manager dependency; `EnvironmentUtils.isRooted()`, `StarterActivity` root path, `ShizukuReceiverStarter` root branch, `StartRootViewHolder` and `ServiceController` root launch all still exist |
| Shell execution | `xion/shell/PrivilegedShell.kt` runs commands through the Shizuku service and returns real exit codes, stdout, stderr and duration |
| Authorisations | `xion/auth/AuthorizationRepository.kt` cross-checks the service's list against Android's package manager |
| Existing screens | Dashboard, Authorizations, Wireless, Tools, Command center, Package manager, Tweaks, Diagnostics, Automation, Backup, plus quick-settings tiles and the watchdog service |
| Automation | Broadcast triggers (`START`, `STOP`, `RESTART`, `WATCHDOG`) with token authentication in `AutomationContract.kt`; no visual editor |
| Backup | App preferences and configuration export/import; authorisations deliberately excluded |
| Diagnostics | Device, service, wireless, authorisations, resources, package counts, now capabilities |

The libsu dependency and root launch paths are the main non-root violations. They are **not removed
yet**: removing them changes the classic screens and must be done in its own step with device testing,
tracked as Phase 1b below.

## Non-root parity audit

Every `isRooted()` gate in the app was inspected. None of them gate a general feature:

| Location | What it gates | Non-root behaviour |
|---|---|---|
| `home/AutomationViewHolder.kt:110` | A note about the device restriction for automation on Android 10 and older | Note shown, everything else identical |
| `settings/SettingsFragment.kt:124` | Start-on-boot preference availability | Available on Android 11 and newer regardless of root, which covers the S23 |
| `home/HomeAdapter.kt:60` | The root restart card | Card hidden; ADB and wireless start remain |
| `starter/StarterActivity.kt` | Root start path | Unreachable without a root shell; ADB path unaffected |
| `receiver/ShizukuReceiverStarter.kt` | Root start branch | Returns without starting; the wireless ADB worker is the supported path |
| `xion/service/ServiceController.kt` | Root launch mode | Only taken when the last mode was root and a root shell exists |

### Verified on the unrooted reference device

Galaxy S23 Ultra SM-S918U, Android 17 (SDK 37), One UI 9.0, service at uid 2000, no root:

| Feature | Evidence |
|---|---|
| Service start and status | Dashboard shows "Service running", "ADB · API 13", "Running as shell (uid 2000) · port 5555" |
| Shell execution | Command center header reads "ADB privileges (uid 2000)"; running `id` returned `uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),1011(adb),...` |
| Package manager | 784 packages listed with All, User and System filters, search and export |
| System tweaks | Animation scale read live from settings, "Current: 1.0" |
| Diagnostics | Capability rows report probe evidence for every entry |
| Authorisations | 24 requesting apps tracked, 23 granted |
| App lock | Available because the device has a secure lock |

What root changes on a device that has it: the root restart card appears and the optional root start
path becomes reachable. Nothing else in the table depends on it.

## Capability registry (delivered in Phase 1)

`manager/src/main/java/moe/shizuku/manager/platform/Capability.kt` defines the model:

- Tier: `SHIZUKU_ADB`, `ANDROID_PERMISSION`, `MANUFACTURER`, `UNSUPPORTED`.
- State: `AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`.
- Every capability carries the evidence behind its state.

`CapabilityRegistry` probes rather than assumes. It runs only read-only commands (`id -u`,
`pm list packages`, `cmd appops get`), checks settings and permissions, and reports `UNKNOWN` when
something cannot be determined without changing state. Verified on a Galaxy S23 Ultra on Android 17:
shell privilege, package inventory, AppOps read, USB debugging, notifications and device credential
all reported available with their probe evidence, wireless debugging correctly reported unavailable
while it was disabled.

The diagnostics screen renders the snapshot under a Capabilities section, so the registry is visible
in the product rather than being an unused abstraction.

## AppOps manager (Phase 2, delivered)

`xion/tools/AppOpsActivity.kt`, reachable from Tools. A package list with search and All, User and
System filters leads to the operations the service reports for that package, each with a mode badge.
Choosing an operation offers Allow, Allow only in foreground, Ignore (deny) and Default.

Writes go through `cmd appops set <package> <op> <mode>` on the Shizuku service, and the result is
never taken on trust: `cmd appops get <package> <op>` reads the mode back, and the screen reports
success only when the read-back matches. Otherwise it says the change was not applied and what the
service still reports.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Read | 49 operations listed for a test app, 52 for the manager itself |
| Change | VIBRATE set to allow from the UI; the row badge became ALLOW and the summary count updated |
| Independent read-back | `adb shell cmd appops get net.xiondigital.shizuku VIBRATE` returned `VIBRATE: allow` |
| Restore | Set back to Default from the UI; the shell returned `VIBRATE: default` |

Limitation found while testing: operations that Android stores as UID modes (CAMERA, READ_CONTACTS,
POST_NOTIFICATION and similar) cannot be changed per package on this Android 17 build. `cmd appops set`
exits 0 and changes nothing, so the screen reports "Not applied: ... still reads ..." instead of a
success. That is exactly what the read-back exists to catch, and the same applies to
`SYSTEM_ALERT_WINDOW`, which the system also refuses to move off default here.

## Permission inspector (Phase 2, delivered)

`xion/tools/PermissionInspectorActivity.kt`, reachable from Tools. Same package browser as AppOps,
then every permission the package manager reports for that app with a GRANTED or DENIED badge and a
runtime or install-time label. Search narrows the list.

States come from `dumpsys package <package>`. Runtime permissions can be granted or revoked with
`pm grant` / `pm revoke`, and the permission is read back with the same dump afterwards: the row and
the count only change when the package manager reports the requested state. Install-time permissions
are shown with an explanation instead of a button, because ADB cannot change them.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Read | 15 permissions listed for the manager itself, 14 granted |
| Grant | NEARBY_WIFI_DEVICES granted from the UI; badge became GRANTED and the count went to 15 of 15 |
| Independent read-back | `adb shell dumpsys package net.xiondigital.shizuku` returned `NEARBY_WIFI_DEVICES: granted=true` |
| Revoke | Revoked from the UI; the dump returned `granted=false` and the count returned to 14 of 15 |

Caveat that the dialog states plainly: revoking a permission from the app that owns the screen stops
that app immediately. Android killed and restarted the manager process when its own permission was
revoked, which is expected system behaviour, not a defect in the inspector.

## Component manager (Phase 2, delivered)

`xion/tools/ComponentManagerActivity.kt`, reachable from Tools. Package browser, then the activities,
services and receivers the package manager lists for that package, with an ENABLED or DISABLED badge.
Tapping a component offers to disable or enable it. A package-level toggle is offered too, and every
change that actually lands is recorded in a Changes list attached to that screen.

Components come from the resolver tables in `dumpsys package`, which list every component that
declares an intent filter. A component with no filter is not in that output and is not listed, which
the screen states instead of implying the list is complete.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Read | 8 components listed for a test app, activities and receivers with state badges |
| Component toggle | Confirmed the prompt; Android answered with a SecurityException, the badge stayed ENABLED and nothing was recorded |
| Package disable | Summary became "8 components · package disabled", Changes showed 1, `pm list packages -d` listed the package |
| Undo | The entry read "Entire package, disabled · just now"; restoring returned "package enabled", cleared the entry and `pm list packages -d` no longer listed it |

Limit on this Android version: the shell user cannot change component state at all. `pm disable-user
--user 0 <package>/<component>` answers with `SecurityException: Shell cannot change component state`,
so component toggles are reported as refused and are never recorded. Package-level enable and disable
still work, which is why that action exists here and why the undo list is built around it.

Bug found and fixed during testing: the first undo implementation stored the state after the change as
if it were the state before it, so restoring compared the package against the change it had just made,
dropped the entry, and left the package disabled. The record now carries both previous and current
state, and the undo verifies against the previous one. Re-tested end to end afterwards.

## Optimization center (Phase 2, delivered)

`xion/tools/OptimizationCenterActivity.kt`, reachable from Tools. Three profiles, each a named set of
values for settings Android exposes to the shell: window, transition and animator scales plus the
screen timeout. Tapping a profile reads the current value of every setting and shows `current -> new`
for each before anything is written.

Applying writes each setting with `settings put`, reads it back, and only reports the setting as
applied when the value matches. Anything the device refuses is listed as NOT applied with the value it
still holds. Every written setting is stored with the value it replaced, and Restore puts those values
back, verifying each one again before clearing the record.

Verified on the unrooted S23 Ultra, starting from `1.0 / 1.0 / 1.0` and a 30 second timeout:

| Step | Evidence |
|---|---|
| Preview | `window_animation_scale: 1.0 -> 0.0`, same shape for the other two scales and the timeout |
| Apply | Dialog reported "Instant applied" with all four settings; `settings get` returned `0.0`, `0.0`, `0.0`, `30000` |
| Restore | Confirmed; `settings get` returned `1.0`, `1.0`, `1.0`, `30000` and the last applied line disappeared |

Scope, stated in the screen: this changes four settings only. It does not touch vendor performance
modes, CPU governors, refresh rate or battery policy, because those are not uniformly writable through
the shell and would need per manufacturer adapters.

## Snapshot and restore (Phase 2, delivered)

`xion/tools/SnapshotsActivity.kt`, reachable from Tools. A snapshot records:

- the four supported settings (three animation scales, screen timeout);
- whether each user installed package is enabled, plus which are disabled;
- AppOps modes for user apps that are allowed, foreground or denied.

AppOps in the plain ignore state are deliberately not recorded: that is Android's baseline for most
operations, it makes up the large majority of entries, and the shell cannot write it per package anyway.
The screen states this in its footer.

Restoring always previews first: it reads the current state and lists each difference as
`current -> snapshot`, including package state changes. Only after confirming does it write, and each
write is verified before it is counted. Items that cannot be put back are listed by name.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Capture | Finished in 35 s: 4 settings, 306 apps, 1489 AppOps recorded |
| Preview | After changing a scale outside the app, the preview read `1 change, window_animation_scale: 0.5 -> 1.0` |
| Restore | Reported `1 item restored`; `settings get` returned `1.0` |
| Second pass | Same flow on the transition scale: `1 change, transition_animation_scale: 0.5 -> 1.0`, restored to `1.0` |

Performance note: the implementation originally compared each stored AppOp with its own shell call,
which would have meant thousands of round trips for a real snapshot. Reads are grouped per package, so
a preview costs one dump per app: 25 s for 306 apps on this device.

Limits: a snapshot holds no app data, no runtime permissions and no system files, so it is a settings
and state restore, not a backup. Disabled system packages are not captured.

## Command center (Phase 2, delivered)

`xion/tools/CommandCenterActivity.kt` now keeps the transcript as recorded lines and adds:

- **History**: the last 20 commands, recalled into the input, with a clear action.
- **Recipes**: named commands, saved from the current input, then run, inserted or deleted.
- **Find**: filters the transcript by substring and reports how many lines match, without discarding
  the transcript.

History and recipes are local records of commands, not results: only the transcript, written from
actual command results, claims that something ran. A recipe with a destructive pattern still goes
through the same confirmation as a typed command.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Run | `uptime` returned `load average: 0.45, 1.01, 1.14` with `[exit 0 · 106 ms]` |
| Recipe | Saved as `uptime`, listed under Recipes, actions showed Run, Insert, Delete |
| Recipe run | Transcript then held two `load average` lines, the second at a later timestamp |
| History | Dialog listed `uptime` and recalled it into the input |
| Find | Searching `load` reported `2 matching lines` and rendered only those two |

Bug found and fixed during testing: an action dialog built with both a message and an item list
rendered the message only, so Run, Insert and Delete never appeared. The action list is now the dialog
content, and the recipe name is the title.

## Security and privacy center

`xion/security/SecurityCenterActivity.kt` collects three answers on one screen:

- **App lock**: whether the switch is on, whether the device still has the screen lock it relies on,
  and a tap that opens the same toggle dialog the dashboard uses.
- **AppOps overrides**: per-package modes set to allow or foreground while the matching runtime
  permission is *not* granted, so the operation is allowed without the permission. Uid-wide modes are
  excluded because the shell user cannot change them per package, and a mode that matches a granted
  permission is normal and not listed.
- **Sensitive permissions**: one row per user app with the sensitive runtime permissions it holds.

Chips switch between both lists or one of them. Every row opens the tool that changes it: the AppOps
manager or the permission inspector, both of which now accept a package name and open straight to it.

The two scans run in parallel, about ten seconds on the reference device, and each is a single shell
command: `dumpsys package` filtered to granted runtime permissions, and `cmd appops get` over the
third-party packages filtered to the operations this screen reports on.

Verified on the unrooted S23 Ultra:

| Step | Evidence |
|---|---|
| Scan | `30 AppOps overrides · 89 apps with sensitive access` |
| Override link | Row `READ_CONTACTS · adornkwgt.ld.kustom` opened AppOps detail for `adornkwgt.ld.kustom` |
| Permission link | Row for `ai.perplexity.app.android` opened the permission inspector at `37 permissions, 27 granted` |
| Filter | The Overrides chip hid the permissions section and kept the 30 override rows |
| App lock | The row switched the lock ON then OFF, and the badge followed the stored state |
| No service | The screen reports that the scan needs the service instead of showing zero findings |

## What each planned subsystem will use

Nothing in the plan needs root. The mappings that follow are the basis for the phase estimates.

| Subsystem | Mechanism | Tier |
|---|---|---|
| Permission inspector (read) | `dumpsys package`, `pm list permissions` over the service | Tier 1 |
| Permission inspector (change) | `pm grant` / `pm revoke` for runtime permissions, `cmd appops set` for special access; settings deep links otherwise | Tier 1 and Tier 2 |
| AppOps manager | `cmd appops get/set/reset` over the service | Tier 1 |
| Component manager | `pm list packages`, `dumpsys package <pkg>`, `pm disable-user`, `pm enable` | Tier 1 |
| Package manager | `pm list packages -f -U`, `dumpsys package`, `pm uninstall --user 0`, `pm install` | Tier 1 |
| Command center | Existing `PrivilegedShell` | Tier 1 |
| Optimization profiles | `settings put` for animation scales, `cmd deviceidle`, `am set-standby-bucket`, refresh rate where exposed | Tier 1, partly Tier 3 |
| Automation studio | Broadcast receivers, alarms, `JobScheduler`, `adb shell` actions | Tier 1 and Tier 2 |
| Snapshot and restore | Supported settings, AppOps modes, package enabled states | Tier 1 |
| Diagnostics | Existing collector plus the capability registry | Tier 1 and Tier 2 |
| Manufacturer adapters | Only settings that exist on that vendor's build | Tier 3 |

## Status matrix

| Section | Status | Notes |
|---|---|---|
| 1. Architecture, capability registry | **PARTIALLY IMPLEMENTED** | Registry and diagnostics integration done; module boundaries and UI foundation still to build |
| 2. Advanced permission inspector | **IMPLEMENTED AND TESTED** | Per-package runtime and install-time permissions, grant and revoke with read-back; install-time permissions are read-only |
| 3. AppOps manager | **IMPLEMENTED AND TESTED** | Browse per package, change a mode, read it back; permission-backed UID modes are not writable on Android 17 and are reported as not applied |
| 4. System component manager | **PARTIALLY IMPLEMENTED** | Components listed and browsable; component enable/disable is refused by Android 17 for the shell user and reported honestly; package enable/disable works with a verified undo list |
| 5. Automation studio | **PARTIALLY IMPLEMENTED** | Broadcast triggers and token auth exist; no visual editor, conditions or actions |
| 6. Optimization center | **IMPLEMENTED AND TESTED** | Three profiles with a live preview, verified writes and a one tap restore; scope is limited to four settings the shell can change |
| 7. Advanced package manager | **PARTIALLY IMPLEMENTED** | Enable/disable, clear, uninstall per app exist; no inventory export, no compare, no debloat assistant |
| 8. Command center 2.0 | **IMPLEMENTED AND TESTED** | Execution with exit codes, history recall, named recipes that can be run, inserted or deleted, and a transcript search |
| 9. Security and privacy center | **IMPLEMENTED AND TESTED** | One screen with the app lock state, sensitive permissions, AppOps overrides and a link to the tool that changes each one |
| 10. Device profiles | **NOT IMPLEMENTED** | Backup exports preferences only |
| 11. Snapshot and restore | **IMPLEMENTED AND TESTED** | Named snapshots of supported settings, package enabled states and meaningful AppOps modes, with a diff preview and a verified restore |
| 12. Intelligent diagnostics | **PARTIALLY IMPLEMENTED** | Data collection is real and honest; no problem detection or recommendations |
| 13. Control hub | **PARTIALLY IMPLEMENTED** | Dashboard shows service state, authorisations, watchdog; not modular or customisable |
| 14. Module system | **NOT IMPLEMENTED** | No module abstraction |
| 15. Manufacturer compatibility | **NOT IMPLEMENTED** | Registry detects manufacturer; no adapters |
| 16. Non-root enforcement | **PARTIALLY IMPLEMENTED** | Root kept as an optional path by decision, registry reports it without requiring it; parity audit found no feature that requires root |
| 17. UI and UX | **PARTIALLY IMPLEMENTED** | Existing Material 3 dark and light UI; new subsystems not designed yet |
| 18. Development and testing | **PARTIALLY IMPLEMENTED** | Branch separation and device testing in place; no automated regression suite |
| 19. Documentation | **PARTIALLY IMPLEMENTED** | This document plus existing docs; new features have no docs yet |

## Phase plan

### Phase 1: foundation (in progress)

- Capability registry with tiers and probe evidence. **Done.**
- Diagnostics surface for the registry. **Done.**
- This plan. **Done.**
- Phase 1b: remove libsu and the root launch paths, replace the root status card with an honest
  "root service detected, unsupported" state, and verify the classic screens still work.
- Module boundary: a `platform` package that feature modules register into, with declared
  manufacturer and version requirements. No plugin loading.

### Phase 2: core system management

Permission inspector, AppOps manager, component manager, package inventory and snapshot compare.
Each ships only for operations whose capability probe says AVAILABLE, and each write is verified by
reading the value back before reporting success.

### Phase 3: automation and optimization

Visual trigger, condition, action editor; execution history; profiles with a preview of exactly which
settings change and a restore of previous values.

### Phase 4: security and diagnostics

Unified security center, diagnostic rules with evidence and recommended fixes, log redaction.

### Phase 5: dashboard and modularity

Control hub cards with per-card visibility, device profiles, snapshot and restore.

### Phase 6: testing and documentation

Version matrix testing, manufacturer checks, regression pass, documentation completion.

## Known limitations to design around

- **AppOps are not permissions.** Setting an AppOp does not change the Android runtime permission,
  and the UI must never imply otherwise.
- **`pm grant` covers runtime permissions only.** Special access (notification listener, accessibility,
  usage access, overlay) needs `cmd appops` or a settings deep link.
- **Disabling system components can break a device.** Every disable needs a warning, and the platform
  must record what it changed so it can be undone.
- **Uninstalling preinstalled apps is `pm uninstall --user 0`.** It removes the app for the user, not
  from the system image, and only some packages allow it.
- **Background automation is subject to Doze and app standby.** Alarms are not exact, and the UI must
  say so instead of promising reliability.
- **Refresh rate control is vendor-specific.** Where the framework exposes it, it works; where it does
  not, the feature is unavailable rather than simulated.
- **Snapshot restore is not a backup.** It covers supported settings, AppOps and package states, not
  application data or system partitions.

## Branding

Feature names are functional: Control Hub, Module System, Automation Studio, Device Optimization
Center, Advanced Package Manager, Security and Privacy Center, Intelligent Diagnostics. The existing
application identity and name stay as they are; no subsystem is named after a person or a website.
