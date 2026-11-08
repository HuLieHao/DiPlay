# code75 capture review fixes

User supplied three verified findings against code74: staged ZIP export needs another full copy; userspace packet timestamps can hide collector delay; SLLv1 drops interface indices. Preserve raw capture retention and prioritize connection responsiveness.

- [x] Verify phone report path stages all retained files; add exportTo(OutputStream) and chunked authenticated HTTP without staging or space reservation. Local save remains space-dependent; phone download is the large-log escape path.
- [x] Add kernel per-packet timestamp parsing, nanosecond PCAP, SLL2 with ifindex. Try Linux NEW then OLD timestamp options for the API27 kernel; never replace a missing kernel timestamp with userspace wall time.
- [x] Add zero-staging-space, complete/failed chunk framing, real HTTP streaming, native timestamp ABI/malformed controls and SLL2 format tests.
- [x] Address independent review: idle/non-reading phone write-progress timeout and atomic root-directory/export-pause registration. Regression tests must first fail.
- [x] Run full project checks once after targeted fixes, build auth-enabled code75 debug APK, verify signature/package/native libraries.

Downloading packets would itself be captured back to disk. During phone ZIP transfer, pause packet persistence only, keep draining the socket and count intentionally discarded packets; continue system/app logs. Resume on success, disconnect or write-stall timeout. The source PCAP prefix remains fixed for the entire export.

No real device is connected. Root/SELinux, actual ap0 packet delivery and long drive load remain device acceptance checks.

Protocol references: Linux kernel timestamping documentation https://www.kernel.org/doc/html/latest/networking/timestamping.html ; libpcap SLL2 layout https://raw.githubusercontent.com/the-tcpdump-group/libpcap/master/pcap/sll.h . Implementation is independent, with byte-layout and ancillary parsing tests.

Validation: review regression run had 2 assertion failures (stalled phone and early root registration), then green after fixes. Final full CI passed, 3543 tests / 0 failures / 0 errors / 1 skipped, 4m16s. Native SLL2 and timestamp ABI tests pass. Auth-enabled code75 built and packaged ABI strings/entry points/metadata checked. No real device connected.
