# Control response timing implementation plan

> Execute with superpowers:executing-plans in the existing isolated worktree.

Goal: distinguish encryption from socket backpressure in the observed 1.9 s /info response.
Spec: ../../CONNECTION-PERFORMANCE-0.2.21-buick.1.md, C01 and implementation step 1; user asked to continue offline.
Architecture: monotonic timestamps inside the existing control loop, one expanded response diagnostic and one batch flush diagnostic. No new worker, cache, settings, dependencies or protocol behavior changes. Keep complete existing payload traces.

- [x] Add a real loopback /info exchange test asserting intact bplist and nonnegative handling/encoding/encryption/write timing, followed by a successful flush record associated with CSeq.
- [x] Run the test and confirm the missing timing diagnostic causes failure.
- [x] Add timing boundaries in AirPlaySession.runControl. Flush diagnostics apply only to batches with a visible diagnostic request; do not expand recurring feedback logging. Distinguish buffered response from flushed bytes; neither proves remote receipt.
- [x] Run targeted tests, then AGENTS.md full CI. Preserve code44's actual POST /auth-setup flush callback and final batch flush. Do not touch timeout or reconnection behavior.
- [x] Record offline findings and remaining physical-device validation. No new device commands, installation, root action or public release.

Review focus: MFi callback ordering; feedback log volume; pipelined batch CSeq association; logging timestamp overhead; no claim that write/flush equals peer receipt. Existing authentication regression tests cover successful/failed/false-positive callbacks. Loopback test verifies the new trace and byte-level response.

Execution ledger: existing isolated worktree retained; scoped to measurement because logs narrow the delay to encryption/write but cannot distinguish them offline. Test first failed with missing handleMs, then passed along with authentication regressions. Reviewer identified unmeasured early auth flush; added authFlushMs and kept new final trace after the existing setup callback. Follow-up review found no remaining issue in this delta. Full CI passed in 4m 43s (252 tasks), including all three test modules, requested lint and debug assembly tasks; no device commands executed.
