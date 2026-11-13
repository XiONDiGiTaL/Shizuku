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

`su`, root shells, Magisk, root filesystems, privileged installs and exploits are out of scope. A
service that happens to be running as uid 0 is treated as **unavailable** by the capability registry,
not as a bonus, and no feature depends on it.

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
| 2. Advanced permission inspector | **NOT IMPLEMENTED** | Existing Authorizations screen covers granted/denied per app only |
| 3. AppOps manager | **NOT IMPLEMENTED** | Read capability probed; no UI, no writes |
| 4. System component manager | **NOT IMPLEMENTED** | No component enumeration or disable/enable |
| 5. Automation studio | **PARTIALLY IMPLEMENTED** | Broadcast triggers and token auth exist; no visual editor, conditions or actions |
| 6. Optimization center | **NOT IMPLEMENTED** | Tweaks screen changes individual settings, no profiles or preview |
| 7. Advanced package manager | **PARTIALLY IMPLEMENTED** | Enable/disable, clear, uninstall per app exist; no inventory export, no compare, no debloat assistant |
| 8. Command center 2.0 | **PARTIALLY IMPLEMENTED** | Execution, exit codes, duration, clear/copy exist; history has no recall UI, no recipes, no search |
| 9. Security and privacy center | **NOT IMPLEMENTED** | App lock and diagnostics exist; no unified center |
| 10. Device profiles | **NOT IMPLEMENTED** | Backup exports preferences only |
| 11. Snapshot and restore | **PARTIALLY IMPLEMENTED** | Preference export/import only; no settings or AppOps snapshots |
| 12. Intelligent diagnostics | **PARTIALLY IMPLEMENTED** | Data collection is real and honest; no problem detection or recommendations |
| 13. Control hub | **PARTIALLY IMPLEMENTED** | Dashboard shows service state, authorisations, watchdog; not modular or customisable |
| 14. Module system | **NOT IMPLEMENTED** | No module abstraction |
| 15. Manufacturer compatibility | **NOT IMPLEMENTED** | Registry detects manufacturer; no adapters |
| 16. Non-root enforcement | **PARTIALLY IMPLEMENTED** | Policy adopted and registry refuses root privilege; libsu and root launch paths still present |
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
