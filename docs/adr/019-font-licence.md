# ADR 019 — Bundled font: JetBrains Mono (OFL-1.1)

**Status:** accepted — 18 August 2026.

## Context

Desktop cannot assume any monospace font is installed. The editor needs a
bundled monospace font for consistent rendering and measurement.

## Decision

Bundle **JetBrains Mono** (SIL Open Font License 1.1) as the desktop
editor face.

### Licence analysis

OFL-1.1 permits bundling and commercial distribution. Its copyleft clause
applies only to the font itself (derivative fonts must also be OFL), never
to application code. This is the de facto standard for open fonts.

Record in `docs/licenses.md` with an explicit font-asset carve-out.

### Rendering discipline (R-50)

The font is injected as part of the **one fully-specified `TextStyle`**
used for both rendering and measurement. No separate `fontFamily` parameter
anywhere — this prevents the R-50 caret-drift bug where rendering and
measurement use different faces.

Desktop test: caret x-position at column 200 of a long line matches the
measured position (verifies no letterSpacing drift).

### Platform handling

- **Desktop:** JetBrains Mono loaded from bundled resources.
- **Android:** continues using system monospace (`FontFamily.Monospace`).

## Trigger to revisit

If a cross-platform bundled font is needed (both Android and desktop using
the same face for pixel-identical rendering).
