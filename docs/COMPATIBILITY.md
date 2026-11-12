# Compatibility and the One UI 9.0 "Authorized 0 applications" issue

## What the issue looks like

On Samsung devices running Android 17 / One UI 9.0, Shizuku 13.6.0 shows
**"Authorized 0 applications"** even though apps hold `moe.shizuku.manager.permission.API_V23`
and can talk to the service. The list of authorised apps is empty, so nothing can be revoked
from the UI, and the count is simply wrong.

## Root cause

The server enumerates packages through hidden APIs. Android 17 changed the hidden
`getInstalledPackages` signature, so the call returns nothing and the server reports an empty
authorised list. Authorisation itself is unaffected, which is why apps keep working while the
list stays empty.

Upstream fixed this in commit `e468d70a` ("support Android 17"), which adds
`server/util/InstalledPackagesCompat.java` and routes every package query through it. The
installed 13.6.0 build predates that fix; the base of this fork (13.7.0) already contains it.

## What this build does about it

1. It contains the compat layer, so the service resolves packages on Android 17.
2. The Authorizations screen does not trust the service alone. It asks Android directly how many
   apps hold the permission (`dumpsys package | grep 'API_V23: granted=true'`) and compares that
   with what the service reported.

   - Agreement, or a service list with entries → the real list is shown.
   - Service list empty while Android reports holders → the screen shows a **reporting failure**
     with both numbers and a diagnosis, never a bare `0`.
   - Service unreachable → the screen says so and offers to start it.

## Device evidence from this build (SM-S918U)

| Check | Result |
|---|---|
| Android / One UI | 17 (SDK 37) / One UI 9.0 (sem 3701) |
| Official Shizuku 13.6.0 list | "Authorized 0 applications" |
| Packages holding `API_V23: granted=true` | 24 |
| Shizuku Reimagined, service reported | 23 authorised |
| Shizuku Reimagined, cross-check | "Android reports 24 holding the permission" |

The one-app difference is the official manager itself, which is always granted the permission
it owns; it does not request access from this build.

## Genuine Samsung limitations (not bugs)

- **Authorisations are per package.** Apps authorised for the official Shizuku do not gain
  access to this build automatically, and revoking in one app does not revoke in the other.
- **The permission is owned by the official app.** This build cannot redeclare the permission
  or its group; Android rejects such an install. It therefore uses the official permission name
  for compatibility and its own signature permission for its own service.
- **Samsung battery management** can still stop a service that Android would otherwise keep,
  regardless of the app's watchdog. Keep the app out of "sleeping apps" if the service must stay
  up.

## Support matrix

| | Status |
|---|---|
| Android 7–16 | Supported by upstream code paths; not exercised in this round of testing |
| Android 17 / One UI 9.0 (SM-S918U) | Built, installed, started and inspected on device |
| Root start | Unchanged from upstream; not exercised on this device (not rooted) |
| Wireless debugging start | Unchanged from upstream; the pairing UI is intact |
| TV / leanback | Launcher entry and layout rules present; not exercised |
