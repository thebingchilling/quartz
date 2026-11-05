# quartz/windows

Quartz's own files for the Windows (desktop) app go here, next to
`quartz/android`. A push to `main` that changes anything under `quartz/`
builds both apps into one release (`.github/workflows/quartz-release.yml`).

Today the desktop app needs no Quartz code of its own: its rebrand is a few
small edits inside upstream's `desktop/` (listed in FORK.md §11), and its icon
is generated from `quartz/android/branding/icon`. Edits under `desktop/` don't
start a build; run `quartz-release` by hand after one.
