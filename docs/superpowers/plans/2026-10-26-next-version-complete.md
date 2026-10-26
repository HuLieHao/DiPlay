# BuickPlay next version complete implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for sequential task implementation and review.

**Goal:** Implement all feasible app-side N01–N14 requirements, preserve connection-speed priority, and deliver an explicitly bounded physical acceptance list.
**Architecture:** Extend existing logging, background session and settings paths rather than create a parallel connection stack. Persist bounded session archives and export them through the existing local phone server. Keep firmware-specific controls opt-in and never write system files.
**Tech Stack:** Kotlin, Android API24+, existing JVM/Robolectric tests, Gradle; no new runtime dependency.
**Spec:** docs/RELEASE-PLAN-0.2.21-buick.1.md (N01–N14 plus highest connection priority).

## Global Constraints
- FF-5000 Android8.1/API27, ARM32, about1GB RAM; Redmi validation before car.
- Detailed original logs, no redaction; no automatic upload; no health-summary UI.
- Connection speed/reliability highest priority; no claimed real-world improvement without measurements.
- No root/system writes, no device install during offline implementation; car acceptance distinct from unit-test completion.
- Worktree /Users/huliehao/.codex/worktrees/7740/headunit, branch buickplay/next-diagnostics, baseline497b2df8 (code47). Existing code46 pushed.
- Existing user approval covers implementation of all items; do not stop to request routine design permission.
- Changes to settings copy require all existing locales; new settings must have one category.
- Preserve original upstream behavior unless needed to fulfill the spec. No new fixed sleeps.

## Review Focus
- Process death, device clock rollback, full disk and export during rotation must preserve evidence or explicitly mark loss.
- User disconnect must cancel automatic callbacks; returning to live CarPlay must not repeat authentication.
- Navigation/call/audio focus changes must preserve independent media and guidance volumes and avoid duplicate OEM UI.
- Overlays must not intercept CarPlay touches or leak handlers while hidden.
- Exports must remain bounded in memory and identify selected files, checksums and partial/error status.

### Task 1: N01/N02/N12/N13 durable archives and complete export
**Files:** SessionLogFile.kt, AsyncDiagnosticLog.kt, new DiagnosticArchive.kt/DiagnosticArchiveTest.kt, HotspotNetworkDiagnostics.kt, DiagnosticExportStore.kt, DiPlayActivity.kt, backup/SettingsBackupServer.kt and FileReportResponse.kt, settings-backup.html, existing diagnostic tests.
**Interfaces:** Keep SessionLogFile constructor/reset/append/close and legacy REPORT_NAMES compatibility. Add bounded archive store under filesDir/logs with immutable session/part IDs and process ID; expose snapshot/export API consumed by phone and local save. All export paths use files/streams, never128MB Strings. Phone endpoint supports selection through a validated fixed range or session ID; archive export returns ZIP with manifest/checksums, diagnostic metadata and selected original logs/network captures.
- [x] RED: tests retain outbound/return sessions across new instances; cap/age cleanup; invalid wall clock; summary survives detail rotation; partial writes/export consistency; selected ZIP entries/checksums; capture correlation; bounded local export.
- [x] Implement detail budget7days/128MiB, summary14days/4MiB, exception context8 bounded snapshots with rate limit; low-space reserve32MiB. Use IDs+monotonic/wall/timezone metadata; log cleanup/drop/error information; no silent date guesses. Existing bounded async queue remains.
- [x] Add selection (today/yesterday/7days/all retained/session) and manifest with cutoff/clock validity, SHA256 and bytes; consistent bounded snapshots; manual network snapshots retained/associated (define budget explicitly); unique files and orphan cleanup.
- [x] Integrate both phone and local export without whole-body String; bounded preview; localized status/retry/copy; preserve original data. Run focused tests then full AGENTS.md CI. Commit and report evidence.

### Task 2: connection lifecycle, speed, recovery and boot (N05/N06/N08/N12)
**Files:** CarPlayBackgroundSession, CarPlayHostActivity, DiPlayActivity, BootReceiver, DiPlaySessionService, PhoneWake/connection startup policies and tests as discovered.
**Interfaces:** Use existing active/hasSession/foreground intents; add only minimal generation/policy state where missing. Emit correlated stage/retry/stop/suspend/resume evidence into Task1 logs.
- [x] Characterize existing lifecycle; add RED regressions for duplicate connection on return, late callback after manual stop, redundant readiness work, boot/wake duplication and hidden-page refresh.
- [x] Implement missing guards and event-driven ready transitions. Preserve auto recovery after actual link loss; explicit connect resets stop state. Resume a live session instead of reconstructing it. No arbitrary retry accelerations without protocol evidence.
- [x] Add one-entry icon preparation cache keyed by immutable input content (and sizing version) to avoid repeated decoding/resizing on reconnect; changed/replaced custom icon invalidates it. Test warm reuse, invalidation, invalid input and bounded ownership. Do not cache dynamic whole /info. Audit C01–C15 in connection-performance document, distinguish implemented instrumentation from unproven physical optimizations.
- [x] Verify existing cold-start receiver and wake events, add non-destructive hooks when supported; record unsupported vendor behavior. Run focused/full CI, commit and report.

### Task 3: task identity, driver UI, phone state and clock (N03/N09/N11/N14)
**Files:** CarPlayHostActivity.kt, DiPlayActivity.kt, new CarPlayClockOverlay.kt/preferences/tests, existing display settings/translations and UI tests.
**Interfaces:** Task labels BuickPlay · 连接管理 / BuickPlay · CarPlay, retain-debug. Clock uses local system time explicitly, off by default, positions left-bottom/right-bottom and bounded sizes; no touch interception or native-video resize. Settings belongs Display. Phone status only from existing proven iPhone protocol signal, never Android battery.
- [x] RED tests task identity, lifecycle cleanup, clock sizes/positions/day-night, unknown phone state and driver action sizing.
- [x] Implement task labels, optional clock with minute-aligned updates only when visible, live settings, invalid date indication; connection management one-tap retry and large controls; display reliable phone state or clear unavailable/unknown.
- [x] Generate compact/full large-font UI evidence (Arabic if alignment touched), including Task1 export range/session selector and bounded preview handoff, run focused/full CI, commit and report device-only limits.

### Task 4: independent audio volume and configuration completeness (N04/N07/N10)
**Files:** WheelKeyService.kt, NavigationWheelSettings.kt, audio/wheel policy and host key routing, backup/SettingsBackupCodec.kt and tests, settings/translations.
**Interfaces:** Preserve media/navigation streams independently. Normal dark translucent overlay, nav/speaker icons only, step1. Optional firmware-sensitive routing only for keys actually received by app; no system files or root writes, no false claim global OEM overlay replaced.
- [x] RED audio-policy tests navigation duck/restore, Siri/call end and correct independent stream; step1/clamp; unsupported interception fallback. Backup cross-version includes new settings while excludes ephemeral IDs/keys/cache and retains formal/debug identity.
- [x] Implement safe app-side volume path and opt-in setting if firmware-dependent; document vendor boundaries concretely. Audit existing focus implementation, repair proven gaps without combining streams.
- [x] Complete backup coverage/migration behavior, tests and full CI, commit and report.

### Task 5: release-ready integration and explicit acceptance matrix
**Files:** release plan, device acceptance guide, build metadata if needed.
- [x] Exercise repeated lifecycle/export cycles for bounded resources; test integration of Tasks1–4 and run full AGENTS.md CI.
- [x] Independent whole-branch review; fix important findings with failing regression then green suite.
- [x] Produce code47 debug APK and checksums, record exact path and install instructions. Mark implemented vs physical-only/firmware-blocked items individually, not wholesale done. No release publish or car writes.

Completed app-side implementation at0754bdc5; final CI3418/0failure/1existing skip and scoped review approved. Redmi final1 upgrade, realCarPlay, retainedreturn, clocktouch and SafariZIP checks recorded in docs/VEHICLE-TEST-0.2.21-buick.1-code47.md. Firmware/hardware acceptance remains individually marked; no globalOEM/ACC/phonebattery or speedgain claim.


### Task 6: User-directed dock clock refinement (2026-10-26)

User explicitly requests clock above left-bottom CarPlay menu button, fixed 24h HH:mm, visual font matching original CarPlay time. This supersedes Android12/24 preference behavior. Use video content bounds, not Android dp margins/font scaling. CarPlay is a raster stream with no native widget bounds API; use current screenshot calibration for standard left sidebar, document approximate matching and atypical/portrait layouts. Preserve default off and touch passthrough. Existing right option should remain coherent; existing font choices may act as relative visual size with default20 matching native. Do not modify system settings, audio algorithms or other task code. Source worktree /Users/huliehao/.codex/worktrees/7740/headunit.

- [ ] Add behavioral tests for forced24h, video-relative geometry/letterboxing/size independence and lifecycle regression.
- [ ] Implement bounded dock clock positioning in CarPlayClockOverlay, wire actual visible video content bounds from CarPlayHostActivity; avoid guessing pixels from full-screen Android resolution. No OCR or frame capture loop.
- [ ] Run focused tests then required full CI, commit source only; parent owns acceptance docs and device. Record limitations and generate screenshot proof if possible.
- [ ] Independent scoped review then real Redmi visual check and standalone build.


### Task 7: Bounded audio clock evidence for unresolved N07

User asks again to locate audio root cause. Source algorithm changes are not justified by current evidence. Read audio-rootcause-500.md in this plan workspace for causal limits. Instrument current renderer worker without altering timing/rate/buffer behavior. No perPCM strings/disk writes, new threads/polls, rings, settings or network capture subsystem. Existing five-second stats plus terminal flush and bounded first/rebuffer snapshots only. Do not change500ms phone configuration or software defaults. No root/system operations.

- [ ] Add focused behavioral tests: state snapshot observational (does not change subsequent trim decisions/learning), window reset retains lifetime/latest state, unsigned playback-head advancement and sample gaps, first/pause/recovery snapshots and no perwrite logging.
- [ ] Add numeric snapshot: targetFrames, filtered error/derivedaverage, integral, constructor inherited integral, requestedrate, latest sampledPCM/compressed, sampleage/dt, backlogguard. Window primitive counters for samples, min/maxPCM/compressed, maxsamplegap, zero/advancingheadread counts, max unsigned head advance with its elapsed interval. Reuse existingmaxWriteMs. Capture initial firstsample once and beforepause/afterrecovery within existingreport caps. Snapshot only on worker, no callback should perturb behavior; labels distinguish instantaneous fromwindow and resetwindows only for normal5sstats/terminal.
- [ ] Keep lines reasonably short (split state/window if needed), available in existing archive and diagnostic ZIP, retain terminalflush. Only actualmedia trim emits. No inferred permanentlostpackets or measuredhardwareclaims.
- [ ] Focused RED/GREEN, full exactAGENTS CI frozen, independent scoped review, commit source/tests only. Parent builds standalone clock+diagnostic APK and installs Redmi, confirms real24h/dockclock and diagnosticfields, asks for audio playback to collect actualstates.
