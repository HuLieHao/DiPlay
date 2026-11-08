# Fast FF-5000 wireless implementation plan

> Use subagent-driven-development for implementation and independent review.

Goal: implement user-approved IPv4 and bounded late-connection/retry optimizations.
Spec: docs/superpowers/specs/2026-11-06-fast-wireless-design.md
Constraints: current dirty kaiyue, no commit/push/install/worktree, no system writes or unrelated audio changes.

## Task1 connection implementation
Owner: source/tests only. Likely WirelessStartupPolicy/HotspotAddressPolicy or small profile helper, CarPlayController endpoint/Bonjour/flip, WirelessStartupWatchdog, WirelessStartupRetryBudget and host failure handling.
- Test first profile gating, IPv4 selection including savedIPv6 andIPv6-only candidate/fallback; all publications/Bonjour list IPv4 only, no retryflip.
- Test actual watchdog fakeclock: normal accept; grace accepts at21.75s without fail/cleanup; expiry22s exactlyonce; stale timer; delayed callback past absolute22s; duplicateStartSession notrenew; cancelled/replaced/internal/foreign listener refused; session/frame deadlines unchanged. Late callback accepted-time semantics must be explicit and cannot resurrect expired generation.
- Implement20s+2s grace without failure at20 and without tearingdown RFCOMM/Bonjour whilegrace. Log transition and finaltimeout.
- Test backoff2,4,4,4,4 with2s credit onlymatchingprofile/FIRST_TCP; max5retries/manualreset/stability and generic oldbehavior unchanged. Zero postgrace delay must still await clean teardown/no overlappingattempts.
- Targeted controller/listener/watchdog/address/retry tests. Inspect constructorreflection tests and update signatures onlywhereactuallychanged; no dummy compatibilityoverloads.
- Selfreview, exact changedfilelist/testcommand/results in .superpowers/sdd/2026-11-06-fast-wireless103/report.md. No subagents.

## Task2 audio evidence analysis
Parent examines existingcapture network-to-renderer/rebuffer/decoder/write/snapshot timing. Record concrete limits and findings, no unsupported policy changes.

## Task3 review and delivery
Independent integrated review; fix actionablefindings; fullAGENTS tests/lint/build. Version103 standaloneauthorizedassets. VerifyZIP/v2/version/assets/hash; new uniqueAPK preserving102. Acceptance notes user-facing behavior and devicevalidation limits.
