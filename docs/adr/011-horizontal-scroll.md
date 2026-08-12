# ADR-011: Shared-Offset Horizontal Scrolling for Document Views

## Status
Accepted

## Context
The editor and both diff views must pan long lines horizontally on a phone
while behaving like a single document page: every row moves together, gutters
stay pinned, and vertical `LazyColumn` scrolling keeps working.

The first implementation attached `Modifier.horizontalScroll(sharedScrollState)`
to every row, sharing one `ScrollState`. `ScrollState` is designed for exactly
one attached consumer: each `horizontalScroll` modifier overwrites
`scrollState.maxValue` with its own row's overflow width as rows compose and
recycle. Short rows (overflow 0) clamp the shared offset back to 0, so any
vertical scroll that composed a short line snapped the view to the left edge,
and long lines were unreachable whenever a shorter row measured last. The
split view avoided the problem by ellipsizing at the pane edge — which made
content past ~20 characters unreachable instead.

## Decision
One `HorizontalScrollController` (in `design`) per document view holds a single
`offsetPx` clamped against `maxOffsetPx = contentWidth − viewportWidth`:

- **Bounds, not measurement.** Content width is computed, not measured: all
  document text is monospace, so `max display columns × glyph width` is exact.
  Tabs count as their expansion width (editor: exact via `expandTabs`; diff
  views: 4-column approximation).
- **Gesture input** attaches to the container that already owns vertical
  scrolling (`Modifier.horizontalDocumentScroll` → `scrollable(Horizontal)`).
  Compose's axis disambiguation routes each gesture to whichever axis crosses
  touch slop first; no nested-scroll plumbing is needed.
- **Rendering**: each row's content lays out at full intrinsic width
  (`wrapContentWidth(unbounded = true)`, `softWrap = false`) inside a
  `clipToBounds()` box and translates by `-offsetPx` in `graphicsLayer` at
  draw time — no recomposition per scroll frame, and gutters, which sit
  outside the clipped box, stay pinned.
- **Editor max-width tracking**: full chunked scan at load (yields every
  2 048 lines), then a running max updated from document change events and
  from rows as they render. Caret and selection overlays draw in content
  space (translated by the shared offset, clipped at the gutter edge), and
  the controller follows the caret via `ensureVisible`.
- Word wrap disables the horizontal axis (`maxOffset = 0`).

## Consequences
- All rows pan as one page; no per-row `maxValue` exists to clobber.
- Split view drops `TextOverflow.Ellipsis`; both panes share one controller
  and translate together.
- **Known limitation**: deleting the longest line does not shrink the
  editor's max width until the file is reloaded. The running max never
  decreases mid-session; the only effect is surplus scroll room. Accepted —
  exact shrink-tracking requires a width-augmented line structure, a natural
  extension of the R-13 tree if it ever matters.
- Proportional fonts would break the width computation; the document surface
  is monospace by design (OE-SPEC-001), so this constraint is already load-
  bearing elsewhere.
