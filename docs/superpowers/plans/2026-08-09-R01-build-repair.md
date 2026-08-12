# R-01 Build Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all Android modules compile again and apply the CLAUDE.md amendments from the P1 Completion Plan v3 §3.

**Architecture:** Fix five build configuration issues across the Gradle files (KSP version, compileSdk/targetSdk, Hilt plugin, kotlin.android plugin, compilerOptions placement), then update CLAUDE.md with the new rules.

**Tech Stack:** AGP 9.3.1, Kotlin 2.3.21, KSP 2.3.10, Compose BOM 2026.06.01, Hilt 2.60.1, Gradle 9.5.0, JDK 21

## Global Constraints

- Independence: no third-party product names in code, comments, strings, package IDs, assets, commit messages, or docs.
- Licence: Apache-2.0, MIT, BSD OK. LGPL needs justification. GPL/AGPL forbidden. Record in `docs/licenses.md`.
- `core/model` and `core/diff` must not import `android.*` or `androidx.*`.
- Both flavours (`direct`, `store`) must build and pass tests.
- Commit messages reference requirement IDs.

## Known issues (from completion plan and codebase audit)

1. **KSP version `2.3.11` does not exist.** KSP 2.3+ uses standalone versioning (no longer `<kotlin>-<ksp>`). Latest is `2.3.10`.
2. **`compileSdk` (37) and `targetSdk` (35) are not reconciled.** Both should come from one catalogue value.
3. **Hilt plugin missing on feature modules.** `feature:editor`, `feature:compare`, `feature:setup` run `ksp(libs.hilt.compiler)` but don't apply `libs.plugins.hilt`. Only `app` applies it.
4. **`kotlin.android` plugin may be needed.** The Compose compiler plugin (`kotlin.compose`) does not imply Kotlin Android compilation in AGP 9.x. Must verify by building.
5. **`kotlin { compilerOptions { … } }` is inside `android { }`.** In 5 modules. May need to move to project scope depending on AGP 9.x behavior.
6. **CLAUDE.md needs §3 amendments** from the completion plan.

## File map

- Modify: `gradle/libs.versions.toml` — fix KSP version, add `compileSdk`/`targetSdk` catalogue values
- Modify: `build.gradle.kts` (root) — add `kotlin.android apply false` if needed
- Modify: `app/build.gradle.kts` — add `kotlin.android`, move `compilerOptions`, use catalogue SDK values
- Modify: `design/build.gradle.kts` — add `kotlin.android`, move `compilerOptions`, use catalogue SDK values
- Modify: `feature/editor/build.gradle.kts` — add `kotlin.android`, add `hilt`, move `compilerOptions`, use catalogue SDK values
- Modify: `feature/compare/build.gradle.kts` — add `kotlin.android`, add `hilt`, move `compilerOptions`, use catalogue SDK values
- Modify: `feature/setup/build.gradle.kts` — add `kotlin.android`, add `hilt`, move `compilerOptions`, use catalogue SDK values
- Modify: `CLAUDE.md` — apply §3 amendments

---

### Task 1: Fix the version catalogue

**Files:**
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Produces: `libs.versions.compileSdk`, `libs.versions.targetSdk`, corrected `libs.plugins.ksp`

- [ ] **Step 1: Fix KSP version and add SDK catalogue values**

In `gradle/libs.versions.toml`, change:
```toml
ksp = "2.3.11"
```
to:
```toml
ksp = "2.3.10"
```

And add after the `agp` line:
```toml
compileSdk = "37"
targetSdk = "37"
minSdk = "31"
```

- [ ] **Step 2: Verify KSP version resolves**

Run: `./gradlew help --no-configuration-cache 2>&1 | head -30`

If `2.3.10` does not resolve, check the Gradle error for the available versions and use the latest `2.3.x`. Record the correct version.

---

### Task 2: Fix Android module build scripts

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Modify: `design/build.gradle.kts`
- Modify: `feature/editor/build.gradle.kts`
- Modify: `feature/compare/build.gradle.kts`
- Modify: `feature/setup/build.gradle.kts`

**Interfaces:**
- Consumes: `libs.versions.compileSdk`, `libs.versions.targetSdk`, `libs.versions.minSdk` from Task 1
- Produces: Compilable Android modules

- [ ] **Step 1: Add `kotlin.android` to the root plugins block**

In `build.gradle.kts` (root), add to the plugins block:
```kotlin
alias(libs.plugins.kotlin.android) apply false
```

- [ ] **Step 2: Add `kotlin.android` plugin to all 5 Android modules**

In each of `app/build.gradle.kts`, `design/build.gradle.kts`, `feature/editor/build.gradle.kts`, `feature/compare/build.gradle.kts`, `feature/setup/build.gradle.kts`, add to the plugins block:
```kotlin
alias(libs.plugins.kotlin.android)
```

- [ ] **Step 3: Add Hilt plugin to the 3 feature modules**

In `feature/editor/build.gradle.kts`, `feature/compare/build.gradle.kts`, `feature/setup/build.gradle.kts`, add to the plugins block:
```kotlin
alias(libs.plugins.hilt)
```

- [ ] **Step 4: Move `kotlin { compilerOptions { … } }` out of `android { }`**

In all 5 Android modules, the block:
```kotlin
android {
    ...
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
}
```
becomes two separate top-level blocks:
```kotlin
android {
    ...
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
```

- [ ] **Step 5: Use catalogue values for compileSdk, targetSdk, minSdk**

In all 5 Android modules, replace hardcoded SDK values with:
```kotlin
compileSdk = libs.versions.compileSdk.get().toInt()
```
```kotlin
targetSdk = libs.versions.targetSdk.get().toInt()
```
```kotlin
minSdk = libs.versions.minSdk.get().toInt()
```

(`targetSdk` only exists in `app/build.gradle.kts`'s `defaultConfig`.)

- [ ] **Step 6: Try to build**

Run: `./gradlew assembleDirectDebug 2>&1 | tail -40`

If it fails, diagnose. Common issues:
- If `kotlin.android` conflicts with AGP 9.x built-in Kotlin: remove the plugin and leave `compilerOptions` inside `android { }`. Update the plan accordingly.
- If `compilerOptions` at project scope isn't recognized: the standalone `kotlin.android` plugin wasn't applied, or it conflicts. Try the alternative placement.

Fix whatever breaks, then re-run until clean.

- [ ] **Step 7: Build both flavours**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

---

### Task 3: Apply CLAUDE.md amendments

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Replace the "never load a whole file" rule**

Replace lines 28-29:
```markdown
- The editor and both compare panes share one `TextDocument`. Never load a whole file
  into a `String`. Never assume a file fits in memory.
```
with:
```markdown
- The editor and both compare panes share one `TextDocument`.
- Documents above `DocumentLimits.EDITOR_MAX_BYTES` are refused with `OmniError.TooLarge`.
  Within the ceiling a file may be held in memory. **No code path may be O(file) per
  keystroke or per rendered row** — the ceiling does not excuse that. See
  `docs/adr/003-size-ceiling.md`.
- Line count is `newlines + 1`. A file ending in a terminator has a real, caret-placeable
  empty final line. See `docs/adr/007-line-model.md`.
```

- [ ] **Step 2: Add new rules after the architecture rules section**

Add before the "Working method" section:
```markdown
- No UI control may exist without behaviour. A menu item, button or switch that does
  nothing is a defect, not a placeholder.
- No production code uses reflection to reach private state.
- Every UI task's acceptance criteria include semantics, touch-target size and contrast.
  Accessibility is not a later phase.
- State the test tier for every criterion. Never assert a test passed on a tier this
  environment does not have.
```

- [ ] **Step 3: Add item 7 to the definition of done**

After item 6, add:
```markdown
7. If a task names an ADR, the ADR file exists, states the decision, the alternatives
   and the trigger to revisit, and is referenced in the commit. A task naming an ADR is
   not done without it.
```

---

### Task 4: Verify everything

- [ ] **Step 1: Run the full verification suite**

```bash
./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

```bash
./gradlew detekt 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL (no detekt violations)

```bash
./gradlew checkCorePurity 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL (no android imports in core modules)

- [ ] **Step 2: Run existing tests**

```bash
./gradlew :core:model:test :core:diff:test 2>&1 | tail -10
```
Expected: All 247 tests pass (43 model + 204 diff)

```bash
./gradlew :core:io:test 2>&1 | tail -10
```
Expected: All 198 tests pass

- [ ] **Step 3: Check for version-compatibility warnings**

```bash
./gradlew assembleDirectDebug 2>&1 | grep -i "warning\|deprecated\|incompatible\|mismatch" | head -20
```
Expected: No version-compatibility warnings. Deprecation warnings from dependencies are acceptable.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts \
  app/build.gradle.kts design/build.gradle.kts \
  feature/editor/build.gradle.kts feature/compare/build.gradle.kts \
  feature/setup/build.gradle.kts CLAUDE.md
git commit -m "fix(build): repair Android module compilation [R-01, T-01a]

- Fix KSP version (2.3.11 → 2.3.10)
- Reconcile compileSdk/targetSdk from version catalogue
- Apply kotlin.android plugin to Android modules
- Apply Hilt plugin to feature modules using hilt-compiler
- Move kotlin compilerOptions to project scope
- Apply CLAUDE.md amendments from P1 Completion Plan v3 §3"
```
