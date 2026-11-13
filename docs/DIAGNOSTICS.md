# Diagnostics

**Diagnostics** collects what is needed to explain a problem without guessing. Everything is
read from the device; nothing is uploaded.

## Sections

| Section | Rows |
|---|---|
| Application | package, version and version code, build type, identity (development or official), whether the official Shizuku is installed |
| Device | manufacturer, model, device, Android release and SDK, One UI version, build id, fingerprint, ABI, form factor |
| Service | state, running flag, privilege uid, privilege level (root / ADB / none), API version, server patch level, SELinux context, last start/stop/crash, start-on-boot and watchdog settings, recorded restarts |
| Wireless debugging | USB debugging, wireless debugging, ADB TCP port, TCP mode, mDNS pairing support, battery-optimisation exemption |
| Authorizations | reported by service, granted, denied, unresolved, remembered but absent, or the failure message and detail if the list could not be read |
| Resources | battery level and charging state, memory, low-memory flag, data storage |
| Package inventory | installed and third-party package counts, read through the service |

Rows that depend on the service read "-" or "not running" when there is no service rather than
inventing a value.

## Export

- **Copy report** puts the plain-text report on the clipboard.
- **Export report** writes the same text through the system file picker; the app never chooses a
  destination for you.

The report is the same text in both cases, formatted as `[Section]` followed by `key: value`
lines, with a generation timestamp.
