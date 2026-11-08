#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "../../main/jni/packet_capture_format.h"
int main(void) {
    uint8_t header[24], record[16], cooked[20];
    capture_pcap_header(header);
    assert(header[0] == 0x4d && header[1] == 0x3c);
    assert(header[20] == 0x14 && header[21] == 1); /* LINKTYPE_LINUX_SLL2 = 276 */
    capture_record_header(record, 123, 456, 1516);
    assert(record[0] == 123 && record[4] == 0xc8 && record[5] == 1);
    assert(record[8] == 0xec && record[9] == 5 && memcmp(record+8, record+12, 4) == 0);
    const uint8_t mac[8] = {1,2,3,4,5,6,0,0};
    capture_cooked_header(cooked, 4, 1, 6, mac, 0x0800, 0x12345678);
    assert(cooked[0] == 8 && cooked[1] == 0 && cooked[2] == 0 && cooked[3] == 0);
    assert(cooked[4] == 0x12 && cooked[5] == 0x34 && cooked[6] == 0x56 && cooked[7] == 0x78);
    assert(cooked[8] == 0 && cooked[9] == 1 && cooked[10] == 4 && cooked[11] == 6);
    assert(cooked[12] == 1 && cooked[17] == 6 && cooked[18] == 0 && cooked[19] == 0);
    return 0;
}
