# Offline reconnect readiness implementation plan

Execute using superpowers:executing-plans in the existing isolated worktree. User authorized continuing offline until a vehicle is required.

Spec: ../../CONNECTION-PERFORMANCE-0.2.21-buick.1.md. Preserve Android API27 support, complete diagnostic text, generation isolation, existing authentication and timeout behavior. No device access or root writes.

Design: use the existing session close atomic gate to emit one first-close record before teardown. Label internal control/event/video/iAP callers. Persist connection timings and first-close records through the existing asynchronous diagnostic writer independent of menu and stale UI state, including controller generation. Parallelize only independent JmDNS close operations, retain the complete teardown barrier and hold the multicast lock until closure finishes. Resource reuse remains deferred.

- [x] Write failing real-session EOF diagnostic, menu/stale-generation persistence and blocked dual-stack closure tests.
- [x] Implement first-close diagnostics and selected diagnostic persistence without accepting stale session state.
- [x] Implement concurrent registry shutdown, preserving interruption status and all-resource closure before return.
- [x] Run focused tests, independent review and full AGENTS CI.
- [x] Increment debug build to code45, assemble, copy versioned APK and checksum into artifacts, document install/test sequence and remaining vehicle questions.

Review focus: cascade must not overwrite first cause; rejected/auxiliary streams must not gain new closure behavior; log path must not double-write; exceptions and interruption must not leave old DNS registries alive at return; multicast lock must cover shutdown. Do not claim seconds saved until measured on the actual headunit.

Execution ledger: targeted red tests reproduced missing first-close, dropped timings and serial registry shutdown; targeted green passed. Independent review found stale wireless-wrapper filtering, reproduced in WirelessStartupListenerTest then fixed with shared ConnectionDiagnosticPolicy. Added interrupted-caller/one-registry-failure coverage and thread-start fallback. Full CI found four plain-JVM close regressions from Android Log.i; first-close now calls the diagnostic listener inside runCatching so logging cannot abort teardown. Follow-up reviewer found no blockers. Final full CI passed in 4m 33s. APK copied with SHA-256, code45/package/minSdk/ARM32/local assets/signature checked. No device actions, installation or GitHub upload. Vehicle validation remains required.
