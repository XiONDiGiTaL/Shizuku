# Troubleshooting

## The service will not start

1. Open **Diagnostics** and check *Service state*, *Privilege uid* and *ADB TCP port*.
2. Wireless debugging must be **enabled** (Android 11+) or the device must be rooted.
3. If nothing else works, start it from a computer:

   ```bash
   adb shell <nativeLibraryDir>/libshizuku.so --server-name=xion_shizuku_server --apk=<base.apk>
   ```

   The command that matches this installation is printed in **Tools** and in the README of the
   starter failure dialog; use the `--server-name` value shown there.

## "Service running" but tools fail

- Check **Diagnostics → Authorization**: the app must be reachable by the service. If the service
  reports a state but commands return errors, the error text from the tool is the real reason
  (for example SELinux denial or a missing binary) and is shown verbatim.
- Commands run as the service's uid; with ADB that is `shell` (uid 2000), so operations that need
  root are marked as such and will fail without root.

## The authorised list is empty

- If the screen shows a **reporting failure** with two different numbers, the service could not
  enumerate packages. This is the Android 17 signature change described in
  `docs/COMPATIBILITY.md`; update to a build containing `InstalledPackagesCompat`.
- If both numbers are `0`, no app currently holds the permission; authorise an app in the
  official Shizuku or from the client app's own request flow.

## Two services are running

That is expected when the official Shizuku is also installed:

```bash
adb shell ps -A -o USER,PID,NAME | grep shizuku_server
```

`shizuku_server` is the official service; `xion_shizuku_server` is this build. Each has its own
configuration file and its own clients. Stop either one from its own app.

## The watchdog keeps restarting the service

Open **Automation → Watchdog** or the dashboard's WATCHDOG card. Restart history, attempt caps
and the last failure reason are recorded there; the watchdog backs off exponentially and gives
up after the configured number of attempts per window instead of looping forever.

## Exporting a report

**Diagnostics → Export report** writes a text file through the system file picker;
**Copy report** puts the same text on the clipboard. Neither uploads anything.
