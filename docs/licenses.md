# Third-party dependencies

Every dependency is recorded here in the commit that adds it (CLAUDE.md).
Permitted: Apache-2.0, MIT, BSD. LGPL by justification only. GPL/AGPL forbidden.

| Dependency | Version | Licence | Used by | Added in |
|---|---|---|---|---|
| Kotlin stdlib / coroutines | see catalogue | Apache-2.0 | all | T-01 |
| AndroidX Core, Activity, Lifecycle | see catalogue | Apache-2.0 | app | T-01 |
| Jetpack Compose (BOM) | see catalogue | Apache-2.0 | app, design | T-01 |
| Material 3 / adaptive | see catalogue | Apache-2.0 | app, design | T-01 |
| Hilt / Dagger | see catalogue | Apache-2.0 | app | T-01 |
| kotlinx.serialization | see catalogue | Apache-2.0 | core/model | T-01 |
| JUnit 4 | 4.13.2 | EPL-1.0 (test only) | tests | T-01 |
| Kotest assertions / property | see catalogue | Apache-2.0 | tests | T-01 |
| Turbine | see catalogue | Apache-2.0 | tests | T-01 |
| detekt | see catalogue | Apache-2.0 | build only | T-01 |

Watch items: the RAR decoder (P3, Q-6) and any document-parsing library (P2) are where
restrictive licences cluster. Check before adopting, not after.
