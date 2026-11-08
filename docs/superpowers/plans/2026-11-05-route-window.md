# Failure-window route evidence plan

Goal: Address verified code98 review gaps without changing connection or system network policy.
Spec: user approved asynchronous limited route snapshots on FIRST_TCP wait/timeout; prioritize current known phone addresses and report selection/truncation.
Architecture: explicit diagnostic event with attempt and interface, async app-to-root private-file handoff; independent root route worker with bounded/coalesced work. Target hints only from observed peer addresses; unknown stays unknown, never infer Wi-Fi MAC from Bluetooth. Candidate selection retains raw evidence and selection rationale.

- [x] Implement lifecycle-safe event handoff and independent bounded root snapshots, correlated timestamps/attempts and no connection-thread IO.
- [x] Implement known-target priority, fallback evidence ranking, and candidate inclusion/exclusion/truncation records; test stale neighbour ordering and missing target.
- [x] Review stop races/event bursts/slow system snapshot/unknown target; run full AGENTS checks.
- [x] Deliver new versionCode99 standalone debug with SHA256; preserve old APKs/unrelated work, no install/push.

Completed and reviewed; validation and limitations: docs/ACCEPTANCE-0.2.21-buick.3-code99.md.
