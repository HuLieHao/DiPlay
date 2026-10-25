# Next-version phone report streaming implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Start the next version with bounded-memory phone report generation/download, before expanding log retention.
**Architecture:** Keep the existing local browser/QR workflow. Generate a temporary UTF-8 report through a Writer and stream its bytes over HTTP; preserve the existing small in-memory response paths. Own and delete the temporary snapshot after the transfer, and reject concurrent report generation/download rather than building multiple large copies.
**Tech Stack:** Kotlin, Android API24+, JVM socket tests, Robolectric, existing Gradle modules.
**Spec:** docs/RELEASE-PLAN-0.2.21-buick.1.md, N01/N02/N12/N13. User approved this scope and explicitly requested implementation; no renewed feature approval required.

## Global Constraints
- FF-5000 API27, ARM32, about1GB RAM; no new dependency or root operation.
- Keep detailed captured text without redaction; do not upload anything automatically.
- No health-summary UI. Keep existing QR/code workflow and local-network requirement.
- Current code46 was committed/pushed before beginning this branch. Next development build uses code47; final release name is not decided here.
- This batch is the N13 foundation, not completion of N01 retention, all next-version items, or phone/car acceptance.

## Review Focus
- Large and Chinese reports must arrive byte-for-byte with correct Content-Length and safe filename.
- Invalid token/method must not generate a report; generation failures must be HTTP errors rather than empty success.
- Two concurrent downloads must not allocate two reports; a failed transfer must release the gate and clean its own file.
- Reader failure before headers must return an error; after headers it must close the connection without claiming success.
- Existing save/share textual report generation must retain its content. Pending async-log flush state is explicit, not an assertion of an atomic archive snapshot.

### Task1: File-backed HTTP download
Files: common/src/main/java/com/shilapi/xcertplay/backup/SettingsBackupServer.kt; common/src/test/java/com/shilapi/xcertplay/backup/SettingsBackupServerTest.kt
Interface: optional reportFile: (() -> File)? creates a unique server-owned snapshot. File name is validated/normalized for Content-Disposition. Server deletes the file after serving and limits file-report requests to one in flight. Existing report ByteArray callback remains for compatibility.
- [x] Add failing real-socket tests for file content/headers/cleanup, failure, token validation, and concurrent busy response.
- [x] Run targeted tests and observe missing-interface failure, then implement routing, bounded buffer copy, ownership and gate.
- [x] Run all server tests; check old backup/import routes remain intact.

### Task2: Stream report generation and wire the phone route
Files: common/src/main/java/com/shilapi/xcertplay/DiagnosticReportFile.kt (new); common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt; common/src/main/assets/settings-backup.html; mobile/build.gradle.kts; common/src/test/java/com/shilapi/xcertplay/DiagnosticReportFileTest.kt (new)
Interface: DiagnosticReportFile.create(directory: File, versionCode: Long, write: (Writer) -> Unit): File generates a uniquely named owned report, closes/flushed output, removes partial output on failure. DiPlayActivity.writeDiagnosticReport(context, Appendable) is shared by existing text report and new file output.
- [x] Add failing tests for UTF-8 file content, unique identifiable filenames and partial generation cleanup.
- [x] Implement bounded Writer generation; phone route uses file callback. Await pending diagnostic writer for at most2seconds off UI and include success/timeout in report; do not claim atomic rotation consistency.
- [x] Correct all9 localized download-page descriptions to match captured original content; no filtering added.
- [x] Set development versionCode47; keep release versionName pending later release decision.
- [x] Run focused tests and complete AGENTS.md CI. Fresh whole-batch reviewer checks file lifecycle/large-transfer failure paths; record results.

## Follow-on work (not silently marked done)
N01 retention/summary/consistent snapshot + large archive export, N02 association, connection performance work, N03/N04/N05–N14. Real Redmi/iPhone and headunit acceptance remain separate.

## Completion evidence — 2026-10-26
- Fresh independent review identified pre-header read failure and missing mid-transfer interruption coverage; both resolved. Three response-boundary regressions were observed failing before the fix, then passing.
- Full AGENTS.md CI passed in 3m50s:3336 tests,0 failures,0 errors,1 skipped; lint and all three debug assemblies passed.
- Added13 tests cover UTF-8 reports, generation failure, token/method validation, concurrency, shutdown,64MB interrupted transfer/retry, invalid snapshot, unsafe filename, truncation and declared-length boundaries.
- Full output: main workspace artifacts/analysis/connection-priority-20261025/code47-streaming-final-ci.log.
- No device installation or release publication. Retention expansion, archive consistency and real phone/headunit acceptance remain pending as listed above.
