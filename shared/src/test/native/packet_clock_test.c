#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../../main/jni/packet_capture_clock.h"
static struct capture_clock_sample sample(int64_t boot, int64_t wall, int64_t mono, int64_t span) {
    struct capture_clock_sample s={boot,wall,mono,boot+span}; return s;
}
int main(void) {
    struct capture_clock_state state={0};
    struct capture_clock_sample s=sample(1000000000LL,1000000000000LL,1000000000LL,1000);
    assert(capture_clock_update(&state,&s)==0 && state.epoch==0);
    s=sample(2000000000LL,1001000000000LL,2000000000LL,2000);
    assert(capture_clock_update(&state,&s)==1 && state.epoch==0);
    /* Suspend advances boottime and wall, but not monotonic: no wall step. */
    s=sample(12000000000LL,1011000000000LL,2000000000LL,1000);
    assert(capture_clock_update(&state,&s)==1 && state.epoch==0);
    /* Real observed -111 second correction. */
    s=sample(13000000000LL,901000000000LL,3000000000LL,1000);
    assert(capture_clock_update(&state,&s)==2 && state.epoch==1);
    assert(state.delta_ns==-111000000000LL);
    s=sample(14000000000LL,1102000000000LL,4000000000LL,1000);
    assert(capture_clock_update(&state,&s)==2 && state.epoch==2);
    assert(state.delta_ns==200000000000LL);
    /* Scheduler delay expands uncertainty; do not manufacture a clock step. */
    s=sample(15000000000LL,1103500000000LL,5000000000LL,1000000000LL);
    assert(capture_clock_update(&state,&s)==1 && state.epoch==2);
    s=sample(17000000000LL,1105000000000LL,7000000000LL,1000);
    assert(capture_clock_update(&state,&s)==1 && state.epoch==2);
    s.boot_after_ns=s.boot_before_ns-1;
    assert(capture_clock_update(&state,&s)==-1 && state.epoch==2);
    s=sample(18000000000LL,1106000000000LL,8000000000LL,1000);
    FILE *out=tmpfile(); assert(out);
    assert(capture_clock_row(out,"packet",7,456,1105999000000LL,&state,&s)==0);
    rewind(out); char line[1024]; assert(fgets(line,sizeof(line),out));
    assert(strstr(line,"packet\t7\t456\t1105999000000\t18000000000\t1106000000000\t8000000000\t18000001000\t2\t1\t") == line);
    fclose(out);
    /* An unusable sidecar stream must report failure to its caller. */
    out=fopen(__FILE__,"r"); assert(out);
    assert(capture_clock_row(out,"anchor",7,456,-1,&state,&s)==-1);
    fclose(out);
    return 0;
}
