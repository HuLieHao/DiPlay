# Audio diagnostic evidence, code100

## Global constraints
Work on kaiyue and preserve all existing changes. No commit, push, install, system configuration or playback-policy change. Diagnostic work must not block the audio thread on file I/O or root commands. Preserve PCAP, root cleanup and normal logs. No unlimited PCM capture. User approved implementation of the four additions below.

## Task 1: audio evidence
Implement and test together:
- Unique renderer/stream identity and precise anomaly events beyond existing first-five report limits: underruns, rebuffer pause/recovery, queue/decoder drops; monotonic and wall clocks, RTP sequence/sample and buffer context where known. Clearly distinguish detected timestamp from actual occurrence when observation is periodic.
- Queue residence and decoder input/output delay plus write timing correlation, retaining bounded counters/metadata and existing playback decisions. Correlation must not misattribute output to latest received packet.
- Low-frequency AudioTrack timestamp evidence (10 seconds), frames/nanoseconds, availability and playback state; failures must not affect audio.
- Async anomaly-triggered root audio snapshots independent of slow periodic snapshots, bounded/coalesced, explicit dropped/coalesced/delay/status, AudioFlinger/audio policy and app/audioserver scheduling evidence. Retain all anomaly events in full app log where queue allows; snapshot coalescing must not suppress events. Stop/parent exit cleanup and file flush preserved. Follow route-event journal existing lifecycle/security patterns without conflating event semantics.

Tests cover beyond-five events, identity and timing correlation, timestamp unavailable, burst coalescing and stopping. Implementer owns audio renderer/new helpers, StartupCapture and RootCaptureMain integration, relevant tests. Parent owns version, acceptance docs and build/artifact delivery.

## Task 2: verification and delivery
Review scoped changes against pre-task filesystem snapshot, fix findings, run AGENTS full checks, build authorized standalone debug with versionCode100, archive new APK and SHA256, record acceptance and limitations. No real-device verification claimed.
