# App startup capture

User-approved scope: start detailed collection at application process startup, without a connected computer, before and across CarPlay retries; retain full logs and packets for later export. Connection speed takes priority; capture never blocks connection. No startup timeout or audio tuning changes in this delivery.

Use the existing FF5000 factory root shell (then ordinary su fallback), asynchronously. A separate root app_process collector watches the owning app process and stops its children when that process exits. No boot daemon, hotspot reset, firewall changes or monitor mode. Only this private FF5000 profile starts root automatically.

Bundle an AF_PACKET collector with the existing NDK build rather than depend on an installed tcpdump. Standard PCAP, Linux cooked packets, all interfaces/protocols, complete packet data. Record readiness, errors, received/dropped counters, wall/monotonic anchors and interface mappings. Root and actual packet availability remain device acceptance checks.

Continuous all-buffer logcat and available kernel logs; periodic lightweight network snapshots and slower full service/driver/process snapshots. Missing commands and permission errors stay in output. Independent workers and timeouts prevent a stuck dumpsys from stopping packets. Preserve capture directories without size-based rotation. Stop on write failure, not silently overwrite old evidence.

Include all retained process capture directories in every ZIP, explicitly separate from date/session filtering (clock can change). Fixed byte-prefix export with checksums; no whole-file memory loading. Native PCAP export must end at a published complete-record boundary. App metadata and detailed connection logs are also mirrored to the independent capture directory.

Alternatives: existing bounded manual ADB snapshots miss early events; external tcpdump alone depends on device installation; boot service is deferred by user choice. Root native capture plus existing export is selected.
