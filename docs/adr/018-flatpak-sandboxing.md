# ADR 018 — Flatpak sandboxing strategy

**Status:** accepted — 18 August 2026.

## Context

The Flatpak sandbox restricts filesystem access by default. A diff/merge
tool that compares arbitrary files needs broad access.

## Decision

Ship with `--filesystem=host` (full filesystem access).

**Justification:** OmniEditor is a file comparison and editing tool — the
same category as file managers and editors. Meld, VS Code, and Sublime Text
all ship on Flathub with `--filesystem=host` or equivalent broad access.
The tool's core function (comparing arbitrary file paths, CLI-driven merge)
is incompatible with portal-only access.

Verify the Meld/VS Code/Sublime Flathub manifest claims against their
actual published manifests at submission time.

`--filesystem=home` is a less contentious interim if Flathub review pushes
back on `host`. It covers the realistic desktop use case (user home
directory) while excluding system files.

### File dialogs

`JFileChooser` (Swing) as the interim file picker with `--filesystem=host`.
Inside a portal-only sandbox, Swing dialogs can browse but the app cannot
access the chosen paths. The sandbox-correct route is the XDG Desktop Portal
file chooser (`org.freedesktop.portal.FileChooser`) via D-Bus. Evaluate
portal library support at Flathub submission time.

### Desktop SourceProvider

Consumes the `FileSystemSourceProvider` from `core/io` — the same tested
implementation as the Android direct flavour. The `direct` flavour's
all-files approach maps directly to `--filesystem=host`.

## Trigger to revisit

Flathub submission review.
