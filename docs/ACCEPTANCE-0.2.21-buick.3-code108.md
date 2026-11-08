# code108 — authenticated CarPlay availability timeout

## Vehicle evidence (code106, 2026-11-08)

- Attempt36: board I2C certificate 908B, signature128B, iPhone AA05 at08:24:14.150. Wi-Fi configuration sent twice and link-ACKed; no0x4300 and no TCP for >130s.
- Attempt37: LocalOffline certificate607B, signature64B. Identification and Wi-Fi parameter logs equal to attempt36. 0x4300 at08:26:42.305; TCP and AirPlay succeeded; first frame14.780s.
- Attempt38: board certificate908B; AA05 at08:30:59.579;0x4300/0x4301 followed; AirPlay active08:31:04.998; first frame08:31:06.233 (9.179s). Tunneled iAP2 board authentication also succeeds at08:31:06.789. User confirms picture. No APK/code/network changes before this success.
- Thus board-chip incompatibility is not established. The initial phone-side absence of availability remains unexplained; locked/unconfirmed phone or transient state are hypotheses, not diagnoses. Do not rewrite certificates or bypass availability based on these logs.
- Raw evidence: ../artifacts/analysis/mfi-20261108/{current.log,software-current.log,latest.log,app-logs.tar}. The mDNS-only capture contains no packets and cannot prove an IPv6 cause; collector stopped. Logs are local diagnostics and may include connection credentials; do not publish raw logs.

## Change

Bluetooth AUTHENTICATED arms a30s AVAILABILITY deadline before StartSession. Repeated progress cannot renew it. StartSession transitions to existing FIRST_TCP timeout; legitimate legacy TCP before StartSession transitions to SESSION. Late events cannot escape an expired availability deadline; stale timers, termination and healthy-frame completion cannot kill a later/healthy session.

AVAILABILITY_TIMEOUT is a distinct retryable failure. Existing bounded2/4/8/16/30s backoff (up to5 retries, also subject to existing total wait limit) applies; it does not receive FIRST_TCP grace credit or flip address family. UI explains successful authentication and missing phone startup, suggesting unlock/check confirmation. All9 existing locales included.

Authentication algorithms, chip timing, Wi-Fi/mDNS/IPv6 policy and unsolicited startup behavior are unchanged by this fix. Changes from other sessions remain in the shared working tree.

## Validation

- RED:4 regression tests failed on the previous watchdog (no availability timer).
- GREEN: watchdog and listener targeted suite passed.
- Static independent review: no important findings; requested typed-reason assertion added.
- Full AGENTS CI: passed (25s; 3667 tests, 0 failures/errors, 1 existing skip); mobile/home/maphost lint and debug builds successful.
- code108 on-device timeout/retry validation: pending; code106 successful session preserved, no installation during active use.
- No commit/push performed.

## Standalone delivery (2026-11-08)

Source hashes stable during verification. Standalone debug built with local authentication and car hotspot assets; code108/package/v2 signature/ZIP/assets verified. APK: /Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code108.apk
SHA256: a19ca79121f0f8dbffb15491984bf945fb8ca8c3165ae79ce24d6d7866123b66
Not installed, committed or pushed in this delivery. Telephone rotary-volume adaptation and mode5 candidate are not included.
