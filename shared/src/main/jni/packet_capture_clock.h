#ifndef PACKET_CAPTURE_CLOCK_H
#define PACKET_CAPTURE_CLOCK_H
#include <stdint.h>
#include <stdio.h>
#include <inttypes.h>

struct capture_clock_sample {
    int64_t boot_before_ns, wall_ns, mono_ns, boot_after_ns;
};
struct capture_clock_state {
    int initialized;
    int64_t low_ns, high_ns, mid_ns, delta_ns;
    uint64_t epoch;
};
/* Return baseline=0, continuous=1, observed offset change=2, invalid=-1.
 * Interval comparison accounts for scheduling between the paired clock reads.
 * BOOTTIME (not MONOTONIC) is used for step detection so suspend is not a wall step. */
static inline int capture_clock_update(struct capture_clock_state *state, const struct capture_clock_sample *s) {
    if(s->boot_before_ns<0 || s->wall_ns<0 || s->mono_ns<0 || s->boot_after_ns<s->boot_before_ns) return -1;
    int64_t low=s->wall_ns-s->boot_after_ns, high=s->wall_ns-s->boot_before_ns;
    int64_t mid=low+(high-low)/2;
    int event=0;
    state->delta_ns=0;
    if(state->initialized) {
        state->delta_ns=mid-state->mid_ns;
        const int64_t tolerance=5000000; /* 5ms outside the measured sampling uncertainty. */
        event=(low-state->high_ns>tolerance || state->low_ns-high>tolerance)?2:1;
        if(event==2) state->epoch++;
    }
    state->initialized=1; state->low_ns=low; state->high_ns=high; state->mid_ns=mid;
    return event;
}
static inline int capture_clock_row(FILE *out, const char *kind, int64_t packet_index, int64_t end_offset,
        int64_t kernel_wall_ns, struct capture_clock_state *state, const struct capture_clock_sample *s) {
    int event=capture_clock_update(state,s);
    return fprintf(out,"%s\t%" PRId64 "\t%" PRId64 "\t%" PRId64 "\t%" PRId64 "\t%" PRId64
        "\t%" PRId64 "\t%" PRId64 "\t%" PRIu64 "\t%d\t%" PRId64 "\n",
        kind,packet_index,end_offset,kernel_wall_ns,s->boot_before_ns,s->wall_ns,s->mono_ns,
        s->boot_after_ns,state->epoch,event,event<0?0:state->delta_ns)<0?-1:0;
}
#endif
