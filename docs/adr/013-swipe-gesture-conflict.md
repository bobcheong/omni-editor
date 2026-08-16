# ADR 013 — Swipe diff-to-diff gesture conflict with horizontal scroll

**Status:** accepted — F-09, 17 August 2026.

## Context

OE-TXT-2 specifies swipe left/right for next/previous difference. ADR-011
attaches horizontal gesture input for long-line panning to the same container
via `scrollable(Horizontal)`. Compose's axis disambiguation routes any
horizontal-slop gesture to the pan, so a naive swipe detector either never
fires or steals panning.

## Decision

**Fling-at-bound** approach: a horizontal fling when `offsetPx` is already
at 0 (left bound) or `maxOffsetPx` (right bound) triggers diff navigation;
panning otherwise wins. When word-wrap is on (no horizontal overflow), all
horizontal flings trigger diff navigation since there is no pan target.

## Alternatives considered

- Edge-zone swipe strips outside the scrollable container — adds invisible
  touch targets, confusing
- Word-wrap-only enablement — too restrictive
- Drop the gesture — functional equivalent exists (Prev/Next buttons) but
  the swipe is a natural touch affordance

## Trigger to revisit

If fling-at-bound feels accidental in device testing, fall back to
word-wrap-only enablement.
