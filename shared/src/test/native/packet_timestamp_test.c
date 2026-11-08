#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include "../../main/jni/packet_capture_timestamp.h"
int main(void) {
    union { struct cmsghdr align; unsigned char bytes[256]; } control;
    struct msghdr msg; memset(&msg,0,sizeof(msg)); memset(&control,0,sizeof(control));
    msg.msg_control=control.bytes; msg.msg_controllen=CMSG_SPACE(16);
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
    c->cmsg_level=SOL_SOCKET; c->cmsg_type=64; c->cmsg_len=CMSG_LEN(16);
    int64_t ts[2]={1793534574,987654321}; memcpy(CMSG_DATA(c),ts,sizeof(ts));
    uint32_t sec=0,ns=0;
    assert(capture_kernel_timestamp(&msg,64,1,1,&sec,&ns));
    assert(sec==1793534574 && ns==987654321); /* No userspace clock consulted. */
    msg.msg_flags=MSG_CTRUNC;
    assert(!capture_kernel_timestamp(&msg,64,1,1,&sec,&ns));
    msg.msg_flags=0;
    c->cmsg_type=29; c->cmsg_len=CMSG_LEN(8); msg.msg_controllen=CMSG_SPACE(8);
    int32_t old[2]={1793534574,123456}; memcpy(CMSG_DATA(c),old,sizeof(old));
    assert(capture_kernel_timestamp(&msg,29,0,0,&sec,&ns));
    assert(sec==1793534574 && ns==123456000);
    old[1]=1000000; memcpy(CMSG_DATA(c),old,sizeof(old));
    assert(!capture_kernel_timestamp(&msg,29,0,0,&sec,&ns));
    c->cmsg_len=CMSG_LEN(2);
    assert(!capture_kernel_timestamp(&msg,29,0,0,&sec,&ns));
    msg.msg_controllen=0;
    assert(!capture_kernel_timestamp(&msg,29,0,0,&sec,&ns));
    return 0;
}
