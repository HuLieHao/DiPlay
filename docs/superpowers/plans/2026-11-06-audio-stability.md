# Audio stability implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Finish offline-verifiable audio stability improvements and expose meaningful vehicle comparison controls.
**Architecture:** Preserve current dirty kaiyue/code105 behavior except isolated, tested audio changes. Clock controller must not confuse compressed backlog with hardware drift. Permit diagnostic heavy snapshots on/off while retaining raw events/PCAP and explicitly report mode. Investigate real navigation RTP contract before bounded reorder/concealment: never invent retransmission/FEC wire semantics, unlimited buffering or claim RF packet-loss fixed. No root/system writes, no install/push/commit.
**Tech Stack:** Kotlin MediaCodec/AudioTrack, Java root diagnostics, existing settings and tests.

- [x] Clock: reproduce measured compressed-backlog overspeed, correct controller with real-drift regression and rebuffer behavior, bounded diagnostics; independently review.
- [x] Capture comparison: settings Diagnostics toggle heavy system snapshots (default preserve current), live mode propagation to root, both event/periodic heavy audio dumps obey it, retain lightweight events/rawPCAP, truthful state/lifecycle tests/translations. Independent review.
- [x] Navigation: investigate codec timing/sequence/redundancy and implement only justified bounded recovery preserving stream timing and low latency. Test wrap/late/duplicate/gap/bounds/stream teardown; document any hardware/iPhone limitation. Independent review.
- [x] Integrate, AGENTS full checks, standalone next APK increment once, verify artifact and acceptance report with unresolved real-device boundaries.

Navigation ruling: PCAP缺少乱序重传证据，完成有界exact去重及时间戳修正；PLC/FEC/丢包重传不冒充完成，硬件缺包继续待现场诊断。
