# Navigation audio recovery evidence (2026-11-06)

Inspected the existing report `Downloads/DiPlay-20261106-090028-497`, specifically
`startup-captures/0307bff3-917f-4b88-9f14-0d6a7bdf2184/app-full.log`
and its sibling `0307bff3-917f-4b88-9f14-0d6a7bdf2184-root-0/network.pcap`.
This capture uses nanosecond PCAP with Linux SLL2 framing. Analysis reads IPv4 UDP
and the plaintext fixed RTP header; it does not decrypt the audio.

The guidance SETUP is stream **101**, `audioType=default`,
`audioFormat=268435456` (`0x10000000`), `audioLatencyMs=80`, and
`supportsRTPPacketRedundancy=true`. Type 102 elsewhere in the same report is
AAC media, not this navigation stream. Navigation packets begin with RTP header
`80 65`, have SSRC 0, and advance 320 timestamp ticks per sequence number.
The negotiated clock is 16 kHz, so each step represents 20 ms; decoding/rendering
Opus at 48 kHz does not change that timestamp unit.

| Destination data port | Packets | Forward missing sequence numbers | Exact duplicate datagrams | Backward arrivals |
| --- | ---: | ---: | ---: | ---: |
| 55750 | 800 | 93 | 0 | 0 |
| 34893 | 874 | 118 | 4 | 0 |
| 36240 | 554 | 89 | 0 | 0 |

The four duplicate datagrams on port 34893 repeat sequences 58, 284, 358,
and 628, respectively 1.263, 9.322, 1.773, and 28.256 ms after the first copy.
Their entire encrypted datagrams match. None of the inspected traffic is a
late recovery of a sequence gap. The matching control ports 43478, 48637,
and 54735 contain no captured incoming UDP datagrams. This provides no
retransmission command or redundancy payload format to implement.

The smallest encrypted data datagrams in these three flows are 44 bytes:
12 header + 8 ciphertext payload + 16 authentication tag + 8 nonce. They
therefore do not reproduce the renderer's less-than-four-byte Opus rejection.
No decrypted XCAUDIO capture was present in this report. A SETUP capability
flag alone does not establish codec FEC, redundant Opus framing, or PLC
support through Android MediaCodec. Those paths remain unimplemented.

## Bounded changes

Type-101 AudioStream now suppresses only byte-identical **authenticated,
decrypted RTP** already delivered within 200 ms and the most recent 16
packets. The cache does not wait, synthesize audio, or reject an unseen late
packet. Full-byte matching preserves distinct packets through sequence and
timestamp wrap, or changed payloads at the same header. Each stream owns
its cache; teardown clears it and a replacement stream starts empty, including
when the phone reuses SSRC 0. Raw/capture callbacks still receive duplicates.
The stream-end diagnostic reports exact duplicates suppressed and explicitly
states `gapRecovery=none addedWaitMs=0`.

The renderer's MediaCodec presentation timestamp now divides RTP ticks by
`AudioFormat.rtpClockRate`. Previously 320 navigation ticks became 6,666 us
because the divisor was the 48 kHz decoder rate; they now become 20,000 us.
AAC media retains its existing timebase. This corrects units; it does not
establish that MediaCodec PTS was the cause of measured AudioTrack underruns.

Focused unit tests cover duplicate suppression, retaining unseen late audio,
changed payload, 16-bit sequence and 32-bit timestamp wrap, count/time bounds,
cache clearing, and 16/48 kHz timestamp conversion. A real local UDP integration
test covers authenticated delivery, duplicate capture retention, late delivery,
and replacement-stream isolation. Existing behavior was first reproduced by
three failing assertions before the filter and timestamp fix passed.

The remaining missing navigation packets cannot be reconstructed from this
capture. RF/phone-side loss, actual Opus redundancy, and device decoding or
concealment behavior need fresh iPhone/head-unit evidence. No additional
reorder delay, retransmission request, synthetic PLC frame, or longer queue
has been introduced, and navigation underruns are not claimed fixed.
