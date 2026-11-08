# Connection evidence implementation plan

Goal: Complete the user-authorized diagnostic evidence additions and investigate firmware IPv4/IPv6 behavior.
Architecture: Add observational link diagnostics through the existing logging path; root collector performs bounded read-only route queries and exposes actual Bluetooth capture coverage. Preserve transport behavior and original evidence.
Spec: User-approved three additions in this conversation: iAP2 queue/write/ACK/retry evidence, IPv6 return-route diagnostics, accurate Bluetooth capture status. Existing full capture remains unchanged.

Constraints: kaiyue; preserve dirty files; no system configuration changes, firmware writes, network rules, MCU writes, install or push. Do not change timeouts/audio strategy. No automatic HCI enablement. No commits without need. Full repository checks before delivery. Increment versionCode if delivering an APK.

- [x] Task 1: Add tested iAP2 link write/ACK/retransmission metadata, correlation to CSM message, diagnostic failures must not affect connection.
- [x] Task 2: Add tested root read-only IPv4/IPv6 route evidence, snmp6, Bluetooth coverage status without claiming unavailable HCI is captured.
- [x] Task 3: Inspect firmware archive and existing code for IPv4/IPv6 cause; document direct evidence vs inference.
- [x] Task 4: Independent review, full AGENTS checks, archive new debug APK/version/SHA256 if build succeeds.

Review focus: partial/coalesced writes; cumulative ACK and sequence wrap; diagnostic sink exceptions; unavailable old ip subcommands; untrusted neighbour text; root lifecycle/child cleanup; no change to transport timing semantics.

Implementation completed with independent static review (no P1/P2). Full verification and signed APK evidence recorded in docs/ACCEPTANCE-0.2.21-buick.3-code98.md. No commits/worktree cleanup because user requires preserving current dirty kaiyue workspace.
