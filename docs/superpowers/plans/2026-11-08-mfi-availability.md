# MFi authenticated startup stall

Evidence: code106 attempt36 authenticated but no 0x4300 for >130s; attempt37 LocalOffline succeeded; attempt38 board MFi succeeded in 9179ms including AirPlay and tunneled iAP2 authentication without code changes. No basis to alter certificates, signing, IPv6 policy or send unsolicited 0x4301.

Confirmed defect: WirelessStartupWatchdog is unarmed until StartSession. Add 30s availability phase after Bluetooth iAP2 AUTHENTICATED; never renew on repeated progress. StartSession or legitimate legacy TCP transitions to existing budgets. Reject late callbacks and preserve healthy sessions. New typed failure uses existing bounded retry budget and localized actionable message. Keep phone-side initial missing availability as unresolved.

Validation: regression tests for absent availability, repeats, StartSession, legacy TCP, late callbacks; full AGENTS CI. Preserve unrelated changes; no push. Do not interrupt currently healthy vehicle session to install.
