# Shipping a true replacement build

Status: **proposed, not implemented.** This document scopes what it takes to publish Shizuku
Reimagined under the `moe.shizuku.privileged.api` package identity, so that it replaces Shizuku for
real instead of running next to it. Nothing described here exists in the build yet.

## Why the current identity falls short

The `net.xiondigital.shizuku` identity keeps development safe, but it also means no third-party app
can use this manager:

| Evidence | What it means |
|---|---|
| `api/provider/src/main/java/rikka/shizuku/ShizukuProvider.java:79` hardcodes `MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api"` | Client libraries bind to that package, so they cannot discover this build |
| `server/src/main/java/rikka/shizuku/server/BinderSender.java:155` refuses to push binders to any package other than our own manager in development mode | Authorised apps never receive a binder from this server |
| `manager/src/main/AndroidManifest.xml` removes `moe.shizuku.manager.permission.API_V23` and its group with `tools:node="remove"` | The official app owns the API permission, so clients cannot be granted it by this build |

In short: today this is a privileged toolkit for itself. Adopting the official package identity is
what turns it into a Shizuku that other apps can consume.

## The hard constraint: it cannot install over the official app

The official APK is signed with Rikka's private key. Android rejects an update whose signature does
not match, with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. No flag or install mode changes that without
root.

A true replacement therefore means:

1. The user uninstalls `moe.shizuku.privileged.api`.
2. The user installs this build, which declares that same package name.
3. From then on, every Shizuku client app binds to this build as if it were Shizuku.

It is a swap, not an upgrade. The site and the docs must say so plainly.

## What the architecture already does for us

`server/build.gradle:17` derives `DEV_IDENTITY` from the manager applicationId. Changing the
applicationId to `moe.shizuku.privileged.api` flips these behaviours automatically:

| Behaviour | Development identity (now) | Replacement identity (automatic) |
|---|---|---|
| Server process name | `xion_shizuku_server` | `shizuku_server` |
| Server config file | `net_xiondigital_shizuku.json` | `shizuku.json` |
| Manager signature permission | `${applicationId}.permission.MANAGER` | `moe.shizuku.manager.permission.MANAGER` |
| Binder fan-out to client apps | disabled | enabled |
| Config pruning of absent apps | avoided | normal |

## Authorisations should survive the swap

`server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.java` stores grants at
`/data/user_de/0/com.android.shell/shizuku.json`. That path belongs to the **shell** user, not to the
manager app, so uninstalling the manager does not delete it. Entries are keyed by **uid** plus package
names, and other apps keep their uids across the swap.

Expected result: a user uninstalls the official app, installs this build, starts the service, and
their existing grants still apply. This is the single most valuable thing to prove on a device,
because it is also the only claim that makes the migration acceptable to users.

Caveats to test, not assume:

- `ShizukuConfigManager.java:135` refreshes each entry through `getPackagesForUid`; entries for
  genuinely uninstalled apps are pruned by design.
- `ShizukuConfig.LATEST_VERSION` is 2. If a future official release bumps the version, our parser
  must not drop unknown fields.
- Uninstalling the manager may leave the shell process running; the config is only reread when a
  server starts.

## Work plan

### 1. Build flavours (`manager/build.gradle`, root `build.gradle`)

- Keep the current identity as a `dev` flavour for QA and for anyone who needs both on one phone.
- Add a `replacement` flavour with `applicationId = "moe.shizuku.privileged.api"`.
- Give the two flavours distinct APK names, for example `ShizukuReimagined.apk` for the replacement
  and `ShizukuReimagined-dev.apk` for QA, since one phone can hold only one of them.
- Keep `versionCode` from the commit count so replacement builds remain ordered.

### 2. Manifest re-ownership (`manager/src/main/AndroidManifest.xml`)

- Remove the `tools:node="remove"` entries for the permission group and
  `moe.shizuku.manager.permission.API_V23` in the replacement flavour, so the build owns them.
- Fix a real mismatch: the manifest declares `${applicationId}.permission.MANAGER`, but
  `BinderSender.java:35` expects `moe.shizuku.manager.permission.MANAGER` once
  `DEV_IDENTITY` is false. In the replacement flavour that mismatch would fail the signature check.
  Use a manifest placeholder, or a flavour specific manifest, to declare the historical name.
- Confirm in the merged manifest that `${applicationId}.shizuku` becomes
  `moe.shizuku.privileged.api.shizuku`, which is the authority clients already expect.

### 3. Identity and UI

- App label, About, and the diagnostics identity row (`DiagnosticsCollector.kt`) need a third state:
  replacement, not development.
- `HomeViewModel.kt:52` currently skips the permission ownership check in development mode. In
  replacement mode that branch becomes the useful guard again, because the app must own the
  permission it declares.
- Quick settings labels and notification text already read "Shizuku", which stays correct.

### 4. Migration experience

- On first start after the swap, read the existing config and report what was carried over, for
  example "23 apps kept their access". Without this, nobody can tell whether the swap preserved
  anything.
- If the config is empty, say so and point at the Authorizations screen.
- Optionally detect that the user came from the official app by the presence of grants whose uids
  predate this install.

### 5. Starter, docs and release

- `Starter.kt` already derives the server name from `DEV_IDENTITY`; `start.sh` is rewritten at every
  launch, so the documented command keeps working.
- Docs and the site need a migration page: uninstall Shizuku, install this, start, verify grants.
  The current install page must not imply both can be installed at once.
- Release assets need fixed names so `releases/latest/download/...` stays stable for each flavour.

## Risks

- **Client signature pinning.** Nothing in this repository verifies the manager's signature, but a
  third-party app may. Only real client apps on a real device settle this.
- **No coexistence, even for testing.** Same package means one build per phone. QA needs a second
  device, or an uninstall cycle between builds.
- **Rollback is symmetrical.** Going back to the official app means uninstalling this one first.
  Grants live in the shell config, so they should return with the official app too.
- **Play Protect and Play updates.** Signature differences are expected for any fork; the Play
  version can never update this build in place.
- **The project's standing rule.** The current rule is that the official install is never modified
  or removed. A replacement build changes that promise at the product level, and testing it requires
  uninstalling the official app on a test device.

## Verification checklist

Run on a device where the official app has been removed deliberately:

1. Install the replacement APK; confirm `pm list packages` shows `moe.shizuku.privileged.api` once.
2. Start the service from the app and from `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`.
3. Confirm the dashboard reads `Service running`, `ADB · API 13`, `uid 2000`, port 5555, and that
   Diagnostics reports the replacement identity.
4. Confirm pre-existing grants survived: open Authorizations and compare counts with the config file.
5. Install a client app that uses the Shizuku API, request permission, and confirm it receives a
   binder and executes a privileged call.
6. Reboot and confirm the watchdog or boot receiver brings the service back with the same grants.
7. Uninstall this build, reinstall the official app, and confirm the grants are still present.

## Decision needed

Two questions before any code is written:

1. Should the replacement identity be the primary download, with the development build kept only for
   QA?
2. Which device may be used for the swap test, given the official install must be removed first?
