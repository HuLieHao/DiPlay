# Faster FF-5000 connection

User approved IPv4 only and the other discussed optimizations. Priority: quick usable connection without system-network writes. Scope is verified FF-5000/ac8227l/API27 profile only, no global IPv6 disable. Other devices retain behavior.

Chosen approach: force published wireless endpoint/Bonjour family to usable IPv4, ignore stale savedIPv6 preference and suppress timeout flip; keep confirmation semantics first frame. If IPv4 unavailable report retryable readiness failure, never silently fall back toIPv6.

At first TCP deadline20s enter bounded2s grace (same listener and bootstrap/resources alive). Accept valid current-generation noninternal peer during grace, advancing normal session watchdog. At22s fail/clean only once. User stop/new generation invalidate immediately; no adoption of failed or foreign attempts. Delayed timer callbacks do not extend absolute deadline. Apply grace only verified profile; retain generic20s behavior.

Borrow grace from existing retry backoff: FIRST_TCP failures nominal backoff2,4,4,4,4s; deduct2s already spent in grace -> post-failure delays0,2,2,2,2s. Preserve5-retry limit and cleanup completion before new attempt, generation/manual-stop guards. Other failure types/devices retain previous behavior. Thus silent first cycle stays22s plus setup/cleanup, later waits capped; do not extend every timeout without compensating.

Alternatives: fix firmwareIPv6 routes rejected by user in favorIPv4; blanketlongertimeout rejected as slower silent failures. Unbounded reuse of late attempts rejected as unsafe state crossover. No new setting/UI needed; logs explicitly label policy, grace and delay accounting.

Audio: investigate existing Nov5/6 capture windows; do not blindly enlarge buffer/change rate. Only implement separate audio fix if causally supported; otherwise record findings and uncertainty as deliverable.

No root/network/firewall/firmware/MCU changes; preserve all capture/exports and unrelated dirty changes. No install/push/commit. Delivered newAPK increments103.
