# Notio for Windows

> **Status: 0.9.67 (2026-11-13).** The desktop app mirrors the functions of the Android app: all the kinds of notes, reminders, the lock, backups, sync with the phone over the local network, ten languages. It is the `windows/` folder of the Notio repository and is built from the repository root (`:desktopApp`, `:windowsShared`); installers: MSI and EXE in ten languages, and a portable zip. See `DEV_SETUP.md` for the build stack and `../CHANGELOG.md` for what changed.

Notio for Windows is the Windows desktop counterpart to **[Notio by vibic](../README.md)** for Android: the same offline note-taking/budget product, native to the desktop. It has **two roles in one app**:

1. **A desktop client** — the same folders, notes and budgets as the phone app, used directly on the PC.
2. **An optional sync node** — when turned on, the app also listens on the local network so `Notio-Android` (and the other
   desktop app) can pair with it and exchange data. No cloud, no account, no third party ever sees the data. The
   protocol is the one the Android app already ships (sync v1): mDNS `_notio-sync._tcp`,
   TCP 47821, a 12-character pairing code, encrypted frames.

## Why this matters as a desktop app first

A Windows PC is usually on and reachable more often than any one phone, which is *why* it makes a good sync
rendezvous point — but that is secondary to it being a good notebook/budget app in its own right. Nothing here is a
"server" in the traditional sense: turning the sync-node role off leaves a complete, ordinary desktop app behind.

## Planned features

Same eleven note kinds and behaviour as the Android app (Note (formatted text), Markdown, list, budget, drawing, blueprint, audio,
photo, camera video, screen recording, schedule), undo / redo, the budget log and currencies, comments, database encryption, and the
same privacy stance (offline-first, no analytics, no ads) — the feature set is meant to match the Android app, adapted to
desktop input (mouse/keyboard/pen instead of touch) and desktop conventions (a resizable window, not a single-pane
phone layout).

## Documentation

- [`DEV_SETUP.md`](DEV_SETUP.md) — the build stack: what to install, how to build, test and release
- [`../CHANGELOG.md`](../CHANGELOG.md) — what changed, version by version
- [`../android/README.md`](../android/README.md) — the Android app's description this app is meant to match

## License

[PolyForm Noncommercial 1.0.0](../LICENSE) — noncommercial use is allowed; commercial use requires a separate agreement with vibic777.
