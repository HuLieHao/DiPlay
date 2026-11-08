#include <jni.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include "packet_capture_format.h"
#include "packet_capture_timestamp.h"
#include "packet_capture_clock.h"

static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *p=data;
    while(size) { ssize_t n=write(fd,p,size); if(n<0 && errno==EINTR) continue;
        if(n<=0) return -1; p+=n; size-=(size_t)n; }
    return 0;
}
static long long monotonic_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (long long)ts.tv_sec*1000+ts.tv_nsec/1000000;
}
/* Userspace read-time anchors supplement, never replace, kernel packet timestamps. */
static int clock_sample(struct capture_clock_sample *sample) {
    struct timespec before, wall, mono, after;
    if(clock_gettime(CLOCK_BOOTTIME,&before) || clock_gettime(CLOCK_REALTIME,&wall) ||
       clock_gettime(CLOCK_MONOTONIC,&mono) || clock_gettime(CLOCK_BOOTTIME,&after)) return -1;
    sample->boot_before_ns=(int64_t)before.tv_sec*1000000000+before.tv_nsec;
    sample->wall_ns=(int64_t)wall.tv_sec*1000000000+wall.tv_nsec;
    sample->mono_ns=(int64_t)mono.tv_sec*1000000000+mono.tv_nsec;
    sample->boot_after_ns=(int64_t)after.tv_sec*1000000000+after.tv_nsec;
    return 0;
}
static void clock_disable(FILE **out, const char *reason) {
    int error=errno;
    if(*out) { fclose(*out); *out=NULL; }
    printf("packet clock correlation unavailable reason=%s errno=%d; kernel PCAP unchanged\n",reason,error); fflush(stdout);
}
static FILE *clock_open(const char *path, int uid) {
    char name[4096];
    if(snprintf(name,sizeof(name),"%s.clock.tsv",path)>=(int)sizeof(name)) { errno=ENAMETOOLONG; return NULL; }
    int fd=open(name,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
    if(fd<0) return NULL;
    if(fchown(fd,(uid_t)uid,(gid_t)uid)) { int error=errno; close(fd); errno=error; return NULL; }
    FILE *out=fdopen(fd,"w");
    if(!out) { int error=errno; close(fd); errno=error; return NULL; }
    setvbuf(out,NULL,_IOFBF,65536);
    if(fputs("# BuickPlay clock correlation v1; all clocks in nanoseconds\n"
        "# kernelWallNs is the original kernel REALTIME timestamp, unchanged in PCAP. -1 means no packet.\n"
        "# bootBeforeNs/bootAfterNs bracket a userspace clock sample AFTER recvmsg, BEFORE PCAP write; NOT kernel arrival time.\n"
        "# BOOTTIME matches Android elapsedRealtimeNanos (includes suspend); MONOTONIC matches native health monoMs (excludes suspend).\n"
        "# sampleEpoch labels observed clock mapping changes, NOT packet arrival epochs: queued packets around a step may belong to an older mapping.\n"
        "# event: 0 baseline, 1 no detected step, 2 offset change beyond bracket uncertainty + 5ms, -1 invalid sample. Small/unsampled steps can be missed.\n"
        "# Use sample uncertainty and kernel times to associate only unambiguous stable intervals. Do not subtract wall times across a step or treat dequeue delay as network delay.\n"
        "# Ignore incomplete trailing TSV rows and rows whose pcapEnd exceeds the exported PCAP length.\n"
        "# packetIndex is 1-based; pcapEnd is the byte boundary of the completed record. Anchor rows repeat the last boundary.\n"
        "kind\tpacketIndex\tpcapEnd\tkernelWallNs\tbootBeforeNs\twallNs\tmonoNs\tbootAfterNs\tsampleEpoch\tevent\toffsetDeltaNs\n",out)==EOF || fflush(out)) {
        int error=errno; fclose(out); errno=error; return NULL;
    }
    return out;
}
static void clock_record(FILE **out, const char *kind, long long count, long long offset, int64_t kernel,
        struct capture_clock_state *state, const struct capture_clock_sample *sample) {
    if(!*out) return;
    uint64_t previous_epoch=state->epoch;
    if(capture_clock_row(*out,kind,count,offset,kernel,state,sample)) clock_disable(out,"write");
    else if(state->epoch!=previous_epoch) {
        printf("packet clock offset change sampleEpoch=%" PRIu64 " deltaNs=%" PRId64 " packetIndex=%lld pcapEnd=%lld; queued packet arrival epoch may differ\n",
            state->epoch,state->delta_ns,count,offset); fflush(stdout);
    }
}
static void clock_anchor(FILE **out, const char *kind, long long count, long long offset, struct capture_clock_state *state) {
    if(!*out) return;
    struct capture_clock_sample sample;
    if(clock_sample(&sample)) clock_disable(out,"clock_gettime");
    else clock_record(out,kind,count,offset,-1,state,&sample);
}
static int publish(const char *path, long long bytes, int uid) {
    char tmp[4096], final[4096], value[40];
    if(snprintf(tmp,sizeof(tmp),"%s.complete.tmp",path)>=(int)sizeof(tmp) ||
       snprintf(final,sizeof(final),"%s.complete",path)>=(int)sizeof(final)) return -1;
    int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);
    if(fd<0) return -1;
    int n=snprintf(value,sizeof(value),"%lld\n",bytes);
    int ok=fchown(fd,(uid_t)uid,(gid_t)uid)==0 && write_all(fd,value,(size_t)n)==0;
    close(fd); return ok?rename(tmp,final):-1;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_diagnostics_RootCaptureMain_capture(
        JNIEnv *env,jclass clazz,jstring filename,jstring stopfile,jint uid) {
    (void)clazz;
    const char *path=(*env)->GetStringUTFChars(env,filename,NULL);
    const char *stop=(*env)->GetStringUTFChars(env,stopfile,NULL);
    if(!path || !stop) { if(path) (*env)->ReleaseStringUTFChars(env,filename,path); return ENOMEM; }
    int socket_fd=-1, output=-1, result=0;
    FILE *clock_out=NULL;
    struct capture_clock_state clock_state={0};
    unsigned char *packet=NULL; long long offset=0, count=0, bytes=0, drops=0;
    socket_fd=socket(AF_PACKET,SOCK_DGRAM|SOCK_CLOEXEC,0);
    if(socket_fd<0) { result=errno; goto done; }
    int one=1, stamp_option=SO_TIMESTAMPNS_NEW, stamp_wide=1, stamp_ns=1;
    if(setsockopt(socket_fd,SOL_SOCKET,stamp_option,&one,sizeof(one))) {
        stamp_option=SO_TIMESTAMPNS_OLD; stamp_wide=sizeof(long)==8;
        if(setsockopt(socket_fd,SOL_SOCKET,stamp_option,&one,sizeof(one))) {
            stamp_option=SO_TIMESTAMP_OLD; stamp_ns=0;
            if(setsockopt(socket_fd,SOL_SOCKET,stamp_option,&one,sizeof(one))) { result=errno; goto done; }
        }
    }
    // Bind only after timestamping is enabled, so no untimestamped packets enter the queue.
    struct sockaddr_ll any; memset(&any,0,sizeof(any)); any.sll_family=AF_PACKET; any.sll_protocol=htons(ETH_P_ALL);
    if(bind(socket_fd,(struct sockaddr*)&any,sizeof(any))) { result=errno; goto done; }
    int buffer=8*1024*1024;
    int buffer_result=setsockopt(socket_fd,SOL_SOCKET,SO_RCVBUF,&buffer,sizeof(buffer));
    socklen_t buffer_len=sizeof(buffer); getsockopt(socket_fd,SOL_SOCKET,SO_RCVBUF,&buffer,&buffer_len);
    printf("packet socket ready interfaces=any promiscuous=false filter=none format=pcap_linux_sll2 timestamp=kernel_realtime option=%d ns=%d rcvbuf=%d setResult=%d\n",stamp_option,stamp_ns,buffer,buffer_result); fflush(stdout);
    output=open(path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,0600);
    if(output<0) { result=errno; goto done; }
    if(fchown(output,(uid_t)uid,(gid_t)uid)) { result=errno; goto done; }
    uint8_t global[24]; capture_pcap_header(global);
    if(write_all(output,global,sizeof(global))) { result=errno; goto done; }
    offset=24;
    if(publish(path,offset,uid)) { result=errno; goto done; }
    clock_out=clock_open(path,uid);
    if(!clock_out) clock_disable(&clock_out,"open");
    clock_anchor(&clock_out,"start",count,offset,&clock_state);
    packet=malloc(262144); if(!packet) { result=ENOMEM; goto done; }
    unsigned loopback=if_nametoindex("lo"); int selftest=0;
    long long last=monotonic_ms();
    while(access(stop,F_OK)!=0) {
        struct pollfd pollfd={socket_fd,POLLIN,0}; int ready=poll(&pollfd,1,1000);
        if(ready<0 && errno!=EINTR) { result=errno; break; }
        if(ready>0 && (pollfd.revents & (POLLERR|POLLHUP|POLLNVAL))) { result=EIO; break; }
        if(ready>0 && (pollfd.revents & POLLIN)) {
            struct sockaddr_ll source; memset(&source,0,sizeof(source));
            union { struct cmsghdr alignment; unsigned char bytes[256]; } ancillary;
            struct iovec iov={packet+20,262124};
            struct msghdr message; memset(&message,0,sizeof(message));
            message.msg_name=&source; message.msg_namelen=sizeof(source);
            message.msg_iov=&iov; message.msg_iovlen=1;
            message.msg_control=ancillary.bytes; message.msg_controllen=sizeof(ancillary.bytes);
            ssize_t n=recvmsg(socket_fd,&message,MSG_TRUNC);
            if(n<0) { if(errno==EINTR) continue; result=errno; break; }
            struct capture_clock_sample receive_clock;
            if(clock_out && clock_sample(&receive_clock)) clock_disable(&clock_out,"clock_gettime");
                if(n>262124) { printf("packet oversized bytes=%zd omitted=true\n",n); fflush(stdout); continue; }
                uint32_t packet_sec,packet_ns;
                if(!capture_kernel_timestamp(&message,stamp_option,stamp_wide,stamp_ns,&packet_sec,&packet_ns)) {
                    printf("packet timestamp missing_or_invalid; stopping rather than inventing arrival time\n"); fflush(stdout);
                    result=ENOMSG; break;
                }
                capture_cooked_header(packet,source.sll_pkttype,source.sll_hatype,source.sll_halen,source.sll_addr,
                    ntohs(source.sll_protocol),(uint32_t)source.sll_ifindex);
                uint8_t header[16];
                capture_record_header(header,packet_sec,packet_ns,(uint32_t)n+20);
                if(write_all(output,header,16) || write_all(output,packet,(size_t)n+20)) { result=errno; break; }
                offset+=36+n; bytes+=n; count++;
                if(clock_out) clock_record(&clock_out,"packet",count,offset,(int64_t)packet_sec*1000000000+packet_ns,&clock_state,&receive_clock);
                const char signature[]="BuickPlay-capture-selftest ";
                if(!selftest && loopback && source.sll_ifindex==(int)loopback) {
                    for(size_t i=20;i+sizeof(signature)-1<=(size_t)n+20;i++) {
                        if(memcmp(packet+i,signature,sizeof(signature)-1)==0) {
                            char testpath[4096];
                            int len=snprintf(testpath,sizeof(testpath),"%s.selftest",path);
                            if(len>0 && len<(int)sizeof(testpath)) {
                                int testfd=open(testpath,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,0600);
                                if(testfd>=0) {
                                    const char proof[]="known_loopback_payload_observed=true hotspot_verified=false\n";
                                    if(fchown(testfd,(uid_t)uid,(gid_t)uid)==0 && write_all(testfd,proof,sizeof(proof)-1)==0) selftest=1;
                                    close(testfd);
                                }
                            }
                            printf("capture_selftest observed=%d interface=lo\n",selftest); fflush(stdout); break;
                        }
                    }
                }
        }
        long long now=monotonic_ms();
        if(now-last>=1000) {
            struct tpacket_stats stats; socklen_t len=sizeof(stats);
            int rc=getsockopt(socket_fd,SOL_PACKET,PACKET_STATISTICS,&stats,&len);
            if(rc==0) drops+=stats.tp_drops;
            if(publish(path,offset,uid)) { result=errno; break; }
            printf("packet health monoMs=%lld packets=%lld payloadBytes=%lld completeBytes=%lld kernelDrops=%lld statsResult=%d\n",now,count,bytes,offset,drops,rc); fflush(stdout);
            clock_anchor(&clock_out,"anchor",count,offset,&clock_state);
            if(clock_out && fflush(clock_out)) clock_disable(&clock_out,"flush");
            last=now;
        }
    }
done:
    clock_anchor(&clock_out,"stop",count,offset,&clock_state);
    if(clock_out) {
        if(fflush(clock_out) || fsync(fileno(clock_out))) clock_disable(&clock_out,"final_flush");
        else { fclose(clock_out); clock_out=NULL; }
    }
    if(output>=0) { if(offset) publish(path,offset,uid); close(output); }
    if(socket_fd>=0) close(socket_fd);
    free(packet);
    printf("packet stopped errno=%d packets=%lld completeBytes=%lld kernelDrops=%lld\n",result,count,offset,drops); fflush(stdout);
    (*env)->ReleaseStringUTFChars(env,filename,path); (*env)->ReleaseStringUTFChars(env,stopfile,stop);
    return result;
}
