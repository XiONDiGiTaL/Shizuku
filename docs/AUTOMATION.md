# Automation

Every control intent and event below is implemented by real receivers and broadcasts in this
build; the Automation screen displays the same strings, the current auth token, and a
"Send test broadcast" button.

Control intents are handled by `AuthenticatedReceiver`, so a caller must either hold the app's
own permission or supply the auth token shown in **Automation → AUTH TOKEN**:

```bash
adb shell am broadcast -a net.xiondigital.shizuku.START \
  -p net.xiondigital.shizuku --es auth <token>
```

From Tasker/MacroDroid/Automate, send the same explicit broadcast to the package with the
`auth` string extra. Without the token the intent is ignored.

## Control intents

| Action | Effect | Extras |
|---|---|---|
| `net.xiondigital.shizuku.START` | Start the service with the last successful method (root or wireless debugging) |, |
| `net.xiondigital.shizuku.STOP` | Stop the service and its binder |, |
| `net.xiondigital.shizuku.RESTART` | Stop, then start again through the same path the UI uses |, |
| `net.xiondigital.shizuku.WATCHDOG` | Enable or disable the foreground watchdog | `enabled: true\|false` |

`WATCHDOG` without the extra flips the current value.

## Events

| Action | Emitted when | Extras |
|---|---|---|
| `...EVENT.SERVICE_STARTED` | The binder becomes available | `timestamp` |
| `...EVENT.SERVICE_STOPPED` | The service is stopped on purpose | `timestamp` |
| `...EVENT.SERVICE_CRASHED` | The binder dies unexpectedly | `timestamp` |
| `...EVENT.SERVICE_RESTARTED` | The watchdog performed a restart attempt | `attempt`, `enabled` (success) |
| `...EVENT.WIRELESS_DEBUGGING` | TCP port / wireless ADB state changes while the app runs | `available`, `timestamp` |

Events are explicit broadcasts to this package. They are delivered while the app process is
alive; the watchdog keeps it alive when enabled.

## Quick start / quick settings

Five tiles are registered: start, stop, restart, watchdog and diagnose. They call the same code
paths as the dashboard, so the reported result is the real one.

## Notes

- Nothing here bypasses the platform: starting the service still requires root, an existing
  wireless-debugging connection, or an ADB shell.
- `RESTART` is a stop followed by a start; if the start path is unavailable the dashboard and
  the restart history show that honestly instead of claiming success.
