#ifndef PACKET_CAPTURE_TIMESTAMP_H
#define PACKET_CAPTURE_TIMESTAMP_H
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>

/* Linux OLD uses kernel long pairs; NEW uses int64 pairs, including on 32-bit Android. */
static inline int capture_kernel_timestamp(struct msghdr *msg, int option, int wide, int nanoseconds,
        uint32_t *seconds, uint32_t *nanos) {
    if(msg->msg_flags & MSG_CTRUNC) return 0;
    size_t size=wide?16:8;
    for(struct cmsghdr *c=CMSG_FIRSTHDR(msg);c;c=CMSG_NXTHDR(msg,c)) {
        if(c->cmsg_len<CMSG_LEN(0)) return 0;
        if(c->cmsg_level!=SOL_SOCKET || c->cmsg_type!=option) continue;
        if(c->cmsg_len<CMSG_LEN(size) ||
            (unsigned char*)c+c->cmsg_len>(unsigned char*)msg->msg_control+msg->msg_controllen) return 0;
        int64_t sec,sub;
        if(wide) { int64_t values[2]; memcpy(values,CMSG_DATA(c),16); sec=values[0]; sub=values[1]; }
        else { int32_t values[2]; memcpy(values,CMSG_DATA(c),8); sec=values[0]; sub=values[1]; }
        if(sec<0 || (uint64_t)sec>UINT32_MAX || sub<0 || sub>=(nanoseconds?1000000000:1000000)) return 0;
        *seconds=(uint32_t)sec; *nanos=(uint32_t)(nanoseconds?sub:sub*1000); return 1;
    }
    return 0; /* Never substitute gettimeofday(): that would hide capture queueing delay. */
}
#endif
