# ADR-009: File Browser Scope for P1

## Status
Accepted

## Context
The direct flavour needs its own file browser since it uses real filesystem
paths rather than SAF URIs. A full-featured browser (tree navigation,
favourites, recent folders, hidden-file toggle, multi-select) is a
significant UI component.

## Decision
P1 ships with a minimum viable file browser: single-directory flat list,
path breadcrumb with up-navigation, sort by name/size/date, tap to pick.
The setup screen's left/right slots make multi-select unnecessary for the
two-file compare case.

## Deferred to P2
- Tree navigation (expand/collapse directories in-place)
- Favourites / bookmarked directories
- Recent folders
- Hidden-file toggle
- Multi-select for batch operations

## Trigger to Revisit
User feedback on the file-picking flow. If the flat browser causes
friction, P2 prioritises tree navigation.
