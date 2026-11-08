#ifndef PACKET_CAPTURE_FORMAT_H
#define PACKET_CAPTURE_FORMAT_H
#include <stdint.h>
#include <string.h>
static inline void capture_le32(uint8_t *p, uint32_t n) {
    p[0]=(uint8_t)n; p[1]=(uint8_t)(n>>8); p[2]=(uint8_t)(n>>16); p[3]=(uint8_t)(n>>24);
}
static inline void capture_be16(uint8_t *p, unsigned n) { p[0]=(uint8_t)(n>>8); p[1]=(uint8_t)n; }
static inline void capture_be32(uint8_t *p, uint32_t n) {
    p[0]=(uint8_t)(n>>24); p[1]=(uint8_t)(n>>16); p[2]=(uint8_t)(n>>8); p[3]=(uint8_t)n;
}
static inline void capture_pcap_header(uint8_t *p) {
    memset(p,0,24); capture_le32(p,0xa1b23c4d); p[4]=2; p[6]=4;
    capture_le32(p+16,262144); capture_le32(p+20,276); /* nanoseconds, LINKTYPE_LINUX_SLL2 */
}
static inline void capture_record_header(uint8_t *p, uint32_t sec, uint32_t ns, uint32_t bytes) {
    capture_le32(p,sec); capture_le32(p+4,ns); capture_le32(p+8,bytes); capture_le32(p+12,bytes);
}
static inline void capture_cooked_header(uint8_t *p, unsigned type, unsigned hardware,
        unsigned length, const uint8_t *address, unsigned protocol, uint32_t interface_index) {
    memset(p,0,20); capture_be16(p,protocol); capture_be32(p+4,interface_index);
    capture_be16(p+8,hardware); p[10]=(uint8_t)type; p[11]=(uint8_t)length;
    memcpy(p+12,address,length>8?8:length);
}
#endif
