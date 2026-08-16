# v0.3 Remaining Items — Design Spec

**Date:** 2026-08-16
**Issue:** #15
**Status:** Approved

## Goal

Wire LargeFileDocument into the editor for INDEXED_READ_ONLY tier files, by widening EditorState to accept any TextDocument and adding an openLargeDocument path in EditorViewModel.

## Changes

### 1. EditorState: PieceTableDocument → TextDocument

Change `EditorState(val document: PieceTableDocument)` to `EditorState(val document: TextDocument)`. EditorState only uses `TextDocument` interface methods (`line()`, `lineCount`, `edit()`, `beginBatch()`, `commitBatch()`, `undo()`, `redo()`, `editGeneration`, `dirty`, `changes`).

PieceTableDocument-specific methods used in EditorState or EditorViewModel:
- `editCoalesced()` — used in IME handler. Guard with `is PieceTableDocument` check; fall back to `edit()` for non-PieceTableDocument.
- `markSaved()` — used in save flow. Guard with `is PieceTableDocument` check; no-op for read-only documents.
- `text()` — used in `getContent()`, find/replace, text tools. Add `text()` to `TextDocument` interface, or provide it via extension. LargeFileDocument can implement it by reading all lines.
- `breakCoalescing()` — guard with `is PieceTableDocument`.

### 2. EditorViewModel.openLargeDocument()

New method accepting a `TextDocument` directly:
```kotlin
fun openLargeDocument(document: TextDocument, readOnly: Boolean, fileName: String)
```
Creates `EditorState(document)` with `readOnly = true`. Skips `viewModelScope.launch { changes.collect }` for read-only documents (dirty is always false).

### 3. NavGraph INDEXED_READ_ONLY wiring

Replace the TODO at line 593-602: copy URI content to a cache file via `ContentResolver`, call `LargeFileDocument.open(cacheFile)`, pass to `viewModel.openLargeDocument()`. Header shows "(read-only, large file)".

### 4. TextDocument.text() addition

Add `fun text(): String` to `TextDocument` interface. `PieceTableDocument` already has it. `LargeFileDocument` implements by joining all lines. This enables find, text tools, and `getContent()` to work on any document type.

## Constraints

- Read-only large documents: edit operations throw UnsupportedOperationException (already implemented in LargeFileDocument)
- EditorState must gracefully handle read-only documents (skip edit-related operations when `readOnly` is true)
- Both flavours must build
- No new dependencies
