# Startup Capture Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline.

**Goal:** Preserve root packets and detailed system/app evidence from App startup without slowing connections.
**Architecture:** Async process-start launcher, independent root app_process collector and bundled NDK packet capture, streaming diagnostic ZIP integration.
**Tech Stack:** Kotlin/Java, Android app_process, C/AF_PACKET, PCAP, Gradle/JUnit.
**Spec:** docs/superpowers/specs/2026-11-02-startup-capture-design.md

## Global Constraints
- Existing kaiyue checkout; preserve unrelated edits; no push.
- No connection/audio policy changes; no capture size rotation.
- Root failure cannot block connection. Device-only checks must be reported as unverified.

## Review Focus
- Missing root / stuck shell: bounded startup, visible failure.
- Parent app dies or restarts: no orphan capture children.
- Active packet writes during export: complete PCAP records and fixed prefix.
- Unknown commands/drivers: record errors, preserve other streams.
- Full disk: expose failure; never report empty capture as healthy.

### Task 1: Capture export and policies
- [x] Add failing export test for large binary captures surviving normal archive limits.
- [x] Implement streaming inclusion of all retained capture files with fixed lengths and checksums.
- [x] Verify tests, including active PCAP boundary and traversal handling.

### Task 2: Automatic collector
- [x] Add native PCAP format test and policy tests before implementation.
- [x] Add Java root collector, native all-interface packet reader, Kotlin startup launcher and process lifecycle ownership.
- [x] Add full app log mirror and diagnostic status to existing report.
- [x] Verify native build, targeted tests and failure paths.

### Task 3: Delivery
- [x] Run required CI tasks and independent review of this change.
- [x] Increment delivery code, build APK, inspect packaged native collector and entry point.
- [x] Record exact validation and remaining on-device acceptance in delivery notes.

## Execution notes
User explicitly requested implementation after agreeing the app-start scope; no repeated approval gate. Existing unrelated dirty tree is preserved, no bulk commit. Task 1 export consumes Task 2 capture directory and PCAP boundary; use filesDir/startup-captures/<UUID>, immutable names, fixed prefixes. Native writer publishes complete record offsets atomically.

Task 1: export tests RED (missing entries) → GREEN. Native format test missing-header RED → compiled/executed GREEN. Task 2: four-ABI build passed; review fixes tested by CaptureLogQueueTest, CaptureRootLaunchTest and StartupCaptureExportTest (8 tests total).
Final review: independent capture_review found queue failure memory retention, cross-attempt stale readiness, and mutable boundary export. Fixed with character-budget queue/disable, isolated root attempt directories, and synthesized ZIP PCAP completion markers.
Ruling: bundle native AF_PACKET capture rather than external tcpdump; PCAP remains standard Linux SLL. Driver monitor mode is not required or enabled. No root/device success is inferred from host builds.
Ruling: existing unrelated dirty changes remain untouched and uncommitted; no push. versionCode 74 was already staged in working source for the next delivery, with no code74 APK in artifacts; use this new delivery number.
Validation note: initial full suite overlapped a targeted test invocation and failed on in-progress-results-generic.bin missing; repeat full suite alone after fixes.

Final validation: full CI BUILD SUCCESSFUL in 6m6s, 3534 tests/0 failures/0 errors/1 skipped; host native format test passed. Auth-enabled code74 debug APK built; four native ABIs, RootCaptureMain dex entry, package/version and local auth assets verified. No device connected or changed.
