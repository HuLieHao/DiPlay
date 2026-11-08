# Detailed capture evidence implementation plan

> **For agentic workers:** Use superpowers:executing-plans; implement in the current kaiyue workspace.

**Goal:** Implement the first four evidence improvements approved in the conversation.
**Architecture:** Extend the existing root collector, keeping packet capture and connection behavior unchanged. Use pure Java helpers for read-only proc/sys evidence, hostapd endpoint discovery and incremental complete-record btsnoop copies; existing managed command runner owns all subprocesses.
**Tech Stack:** Java / Kotlin JUnit / Android app_process.
**Spec:** User approval of audit items 1–4; no time-correlation change.

## Constraints
- Preserve all existing changes, do not push or install. New delivered APK code80.
- No system configuration writes, Bluetooth enablement, network rules, collection size rotation or new UI.
- Explicit unavailable/error output; optional evidence failures must not affect connection.

## Review focus
- Hostapd custom control directory and absent/invalid config: use read-only status/all_sta and safe argument arrays.
- Process exit/restart: raw stat including starttime, per-thread stat and I/O; failure must not terminate other evidence.
- HCI partial records, append, truncate, replacement: retain complete records in separate generations without duplication or modifying source.
- Stop during copying: bounded snapshot, cancellation between complete records; no new subprocess outside existing ownership.
- Firmware missing sysfs/audio services: explicit errors, continue other probes.

## Tasks
- [x] Add behavior tests for endpoint parsing, proc/sys discovery and btsnoop partial/rotation/cancellation handling; run red.
- [x] Implement helpers and integrate: process load every 5s, hardware every 5s, wireless/audio/HCI in existing slow snapshots; final HCI drain on manual stop.
- [x] Run focused tests and independent review; fix demonstrated defects.
- [x] Run repository full CI, standalone debug build, verify metadata/signature/SHA256, archive code80 and record acceptance evidence.

## Execution record
- Existing code79 MFi changes are unrelated and retained. No worktree or commit created.

- 完成：10项新增测试通过，最终完整CI 3557项/0失败/0错误/1跳过；native格式及时间戳测试通过。独立审查两项问题均已修复并回归。交付与SHA见code80 acceptance文档。
