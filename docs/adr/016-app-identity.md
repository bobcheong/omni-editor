# ADR 016 — Application identity

**Status:** accepted — 18 August 2026.

## Context

Flatpak/Flathub, the `.desktop` file, and D-Bus names all require a stable
reverse-DNS application ID. Once published on Flathub, the ID is permanent —
a changed ID is a new app.

## Decision

**ID: `dev.srcse.OmniEditor`**

Domain `srcse.dev` is owned and will be renewed. Flathub's verified-app
checkmark requires demonstrable domain control; a lapsed domain blocks
verification permanently but does not break the app.

The Android `applicationId` (`com.omnieditor`) intentionally differs.
Cross-platform ID divergence is normal and expected — Android's Java-package
convention predates Flatpak's reverse-DNS convention, and unifying them
would require an Android migration that breaks update continuity for
installed users. This ADR records the divergence as a deliberate decision.

The website and AppStream metainfo reference the same `srcse.dev` domain.

## Alternatives considered

1. `io.github.bobcheong.OmniEditor` — tied to a GitHub account, not a
   controlled domain. Survives only as long as the GitHub username.
2. `io.github.srcse.OmniEditor` — requires a GitHub org; still tied to
   GitHub's namespace.

## Trigger to revisit

Never. The ID is permanent once published.
