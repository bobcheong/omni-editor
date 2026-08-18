# ADR 017 — KMP source-set conversion and module sequencing

**Status:** accepted — 18 August 2026.

## Context

The v0.5 desktop port shares Compose UI code between Android and Linux.
The core modules (`core/model`, `core/diff`, `core/io`) are already pure
Kotlin JVM with no Android dependencies, enforced by `checkCorePurity`.

## Decision

### Module conversion

| Module | Plugin | Source sets | Notes |
|--------|--------|------------|-------|
| `core/model` | `kotlin.jvm` | (unchanged) | Pure Kotlin, no KMP |
| `core/diff` | `kotlin.jvm` | (unchanged) | Pure Kotlin, no KMP |
| `core/io` | `kotlin.jvm` | (unchanged) | JVM NIO types, no KMP |
| `design` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | Theme platform actuals |
| `feature/editor` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | BackHandler actual; Hilt removed |
| `feature/compare` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | BackHandler actual |
| `feature/setup` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | Moves to commonMain untouched |
| `app-android` | `android.application` | (unchanged) | Renamed from `app/` |
| `app-desktop` | `kotlin.jvm` + `compose-multiplatform` | (single source) | New module |

### Sequencing

1. `app/` → `app-android/` rename as isolated commit (trivially revertable)
2. Data-safety extractions to `core/io` (before any KMP conversion)
3. KMP plugin applied to UI modules
4. `app-desktop/` created last

### DI strategy

- `EditorViewModel` becomes a plain `ViewModel()` subclass with no DI
  annotations. Requires `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`
  (multiplatform) for `ViewModel` + `viewModelScope` in commonMain.
- `app-android` instantiates via Hilt factory.
- `app-desktop` constructs manually (~15 bindings).

### Navigation

- `OmniNavigator` interface for coordinator sharing (best-effort).
- Desktop uses sealed-class screen state; Android keeps `NavHostController`.

## Trigger to revisit

When core modules need platform-specific implementations.
