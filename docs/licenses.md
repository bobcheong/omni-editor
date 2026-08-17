# Third-party dependencies

Every dependency is recorded here in the commit that adds it (CLAUDE.md).
Permitted: Apache-2.0, MIT, BSD. LGPL by justification only. GPL/AGPL forbidden.

| Dependency | Version | Licence | Used by | Added in |
|---|---|---|---|---|
| Kotlin stdlib / coroutines | 2.3.21 / 1.11.0 | Apache-2.0 | all | T-01 |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | app | T-01 |
| AndroidX Lifecycle | 2.11.0 | Apache-2.0 | app, features | T-01 |
| Jetpack Compose (BOM) | 2026.06.01 | Apache-2.0 | app, design, features | T-01 |
| Material 3 / adaptive | via BOM | Apache-2.0 | app, design | T-01 |
| Hilt / Dagger | 2.60.1 | Apache-2.0 | app, features | T-01 |
| Hilt Navigation Compose | 1.4.0 | Apache-2.0 | app, features | T-01 |
| kotlinx.serialization JSON | 1.11.0 | Apache-2.0 | core/model, core/io, app | T-01 |
| KSP | 2.3.11 | Apache-2.0 | build (annotation processing) | T-01 |
| JUnit 4 | 4.13.2 | EPL-1.0 (test only) | tests | T-01 |
| Kotest assertions / property | 5.9.1 | Apache-2.0 (test only) | tests | T-01 |
| Turbine | 1.2.1 | Apache-2.0 (test only) | tests | T-01 |
| detekt | 1.23.8 | Apache-2.0 (build only) | build | T-01 |

| AndroidX DataStore Preferences | 1.2.1 | Apache-2.0 | app (settings persistence) | R-35 |
| Navigation Compose | 2.9.0 | Apache-2.0 | app (nav graph) | T-01 |
| AndroidX Benchmark Macro | 1.3.4 | Apache-2.0 (test only) | benchmark | F-05b |
| AndroidX UiAutomator | 2.3.0 | Apache-2.0 (test only) | benchmark | F-05b |

**Licence notes:**
- JUnit 4 (EPL-1.0): test-only dependency, not bundled in release APKs. EPL-1.0 is
  outside the Apache/MIT/BSD allowlist but is standard for JVM test frameworks and
  carries no runtime or distribution obligations for test-only use.

Watch items for future phases:
- P2 (documents): OOXML/PDF parsing libraries — check licences carefully
- P3 (remote): Apache Commons Net (FTP), SSHJ (SFTP), SMBJ (SMB) — all Apache-2.0
- P3 (archives): RAR decoder (Q-6) — potential licence restriction
