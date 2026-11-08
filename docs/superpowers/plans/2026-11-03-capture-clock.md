# Capture clock correlation implementation plan

> **For agentic workers:** Use superpowers:executing-plans in current kaiyue workspace.

**Goal:** Implement approved audit item 5: correlate PCAP and system/app clocks across time sync.
**Architecture:** Keep kernel realtime PCAP timestamps unchanged. A buffered native sidecar records packet index/end boundary, kernel realtime and a userspace BOOTTIME/REALTIME/MONOTONIC/BOOTTIME bracket after recvmsg; periodic anchors cover idle intervals. Compare realtime-to-boottime offset intervals to label observed clock offset changes, without pretending a dequeue sample proves the packet's arrival epoch. BOOTTIME matches app elapsedRealtime; MONOTONIC excludes suspend.
**Tech Stack:** C/JNI, existing ZIP archive, native unit tests and repository Gradle checks.
**Spec:** User request to optimize previously explained clock association; no system time or connection behavior changes.

## Constraints and review focus
- Preserve original kernel timestamps, SLL2, complete PCAP boundaries, existing dirty changes; no push/install. APK code81.
- Timestamp sampling bracket must expose uncertainty; suspend must not be mistaken for wall clock adjustment.
- Clock sampling/output failure must be explicit and must not terminate network capture.
- A clock step observation belongs to the sampling timeline, not necessarily a queued packet's kernel arrival epoch. No fabricated corrected PCAP.
- Buffered file flushed periodically and on stop; PCAP packet count/offset links only completed records. Stop and export semantics unchanged.

## Tasks
- [x] Add native regression tests for backward/forward steps, suspend, jitter/long sample uncertainty, invalid samples and serialized packet/anchor rows.
- [x] Implement correlation helper + sidecar, startup/periodic/stop anchors and in-report format guidance; build code81.
- [x] Independent review and native tests; full repository checks; authenticated APK build, metadata/signature/hash checks and archive.

完成：原生三组测试、下载重试测试、完整CI通过；独立审查无待处理问题。交付记录见code81 acceptance文档。
