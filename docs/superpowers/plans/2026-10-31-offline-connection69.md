# Code69 offline connection evidence and recovery plan

Goal: complete offline-verifiable improvements from code67 vehicle evidence, preserving code68 always-visible clock.
Architecture: separate transient recovery selection from a single bounded, environment-scoped successful preference; confirm only after first main-screen frame. Extend existing bounded diagnostics rather than changing protocol waits or system settings.
Spec: user 2026-10-31 instruction to complete all offline fixes and prior code68 acceptance analysis.
Constraints: fixed kaiyue workspace; no push, no system/device writes; no commits of mixed historical changes. Android API27 supported. No timeout/default buffer changes without evidence.
Review focus: stale callbacks after manual stop; controller recreation during retries; environment changes; invalid system clock; no inference that ARP is association or underrun is audible loss.

- [x] Task 1: shared network WirelessAddressPreference + CarPlayController + host shared retry state. Tests cover no timeout persistence, successful confirmation, stale token/clear, environment change, malformed preference and fallback. Existing recovery remains unchanged.
- [x] Task 2: common archive clock-jump evidence, metadata correctness, clock visibility diagnostics. Tests for repeated jumps and snapshot state. No automatic system clock writes.
- [x] Task 3: shared media offline analysis and demonstrable fixes only; correlate actual stream input and speed controller, preserve defaults unless proven defect.
- [x] Task 4: startup environment timing evidence/analysis: read existing snapshots and packet/source availability, add only bounded actionable evidence; no speculative readiness delay or restart.
- [x] Task 5: independent review, full repository CI, code69 APK signature/hash, acceptance report distinguishing offline proof and remaining car acceptance.

Progress: initial uncommitted working tree preserved; code68 is delivered baseline. Agent audio owns shared media, agent time owns common archive/clock plus two diagnostic hook sites. Main owns network/controller and host background retry state. All Gradle runs serialized by main.

Review ledger: independent reviewer offline69_review found three P2s: auto-selected phone scope, randomized generated-hotspot scope, old frame completing replacement observer. Fixed in one pass. Randomized-hotspot and listener race tests observed RED in /tmp/code69-review-red.log then GREEN in /tmp/code69-review-green.log. Actual-phone scope isolation tested, controller resolves actual device before preference selection. No deferred minor findings.
Rulings: no source commits because working tree contains historical mixed changes; no push. No speculative TCP timeout/buffer/protocol or system-clock changes; missing packet/time-source proof explicitly remains real-device work. Scope excludes per-run random P2P/LOHS credentials but includes actual phone; manual/existing networks retain SSID/BSSID/interface.
Verification ledger: original preference missing API RED; audio short-rebuffer error RED -0.5561648582375168 vs0 then media suite GREEN; archive metadata-list test RED then first full CI GREEN; snapshot duration test RED then GREEN. First full CI passed4m44s before review fixes. Final full CI passed4m29s, counts=[3501, 0, 0, 1]; signed APK69 delivered.
