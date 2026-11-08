# Car setup defaults implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Prefill confirmed settings for the single FF-5000/ac8227l/API27 headunit.
**Architecture:** Initialize before wizard/settings reads and host direct entry. Only fill absent persisted keys, preserve all explicit values/guide completion/permissions. Reuse existing local hotspot asset and code104 one-time hotspot migration. Wireless enabled, manual car hotspot when local credentials available, auto car hotspot on app/wireless launch, media3/navigation11, vehicle-light appearance, one-step volume. Clock already always visible; preserve size and validated resolution/framerate. No fake permissions or wizard completion.
**Tech Stack:** Existing Kotlin SharedPreferences/Robolectric.

- [x] Test first and implement small preset helper/integration, exact profile gate, no secret constants; verify fresh and existing installs/edits, host and guide observe same values. Independent review.
- [x] Full AGENTS checks, standalone code105 artifact/assets/signature verification, acceptance docs; no install/push/commit.
