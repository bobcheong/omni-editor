# ADR-008: Three-Pane Wide-Screen Layout Deferred

## Status
Accepted

## Context
3-way merge requires showing three versions: left, base, right. A
three-pane layout on wide screens is the natural presentation, but it
is a significant UI component requiring responsive breakpoints, sync
scrolling across three panes, and adaptive presentation for phones.

The resolution sheet from R-29 already works at any screen width —
it shows both/all versions in a modal bottom sheet, which is usable
on phones and tablets alike.

## Decision
P1 ships with conflict resolution through the existing active-line
sheet. The three-pane wide-screen layout is deferred to P2.

## Consequences
- 3-way merge is functional at any screen width via the sheet.
- The sheet is modal (one hunk at a time), which is slower for
  reviewing many conflicts but correct and complete.
- Wide-screen users don't get the spatial overview a three-pane
  layout would provide.

## Trigger to Revisit
User feedback on 3-way merge workflow. If the modal-per-hunk flow
causes friction on wide screens, prioritise the layout in P2.
