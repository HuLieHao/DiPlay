package com.shilapi.xcertplay.diagnostics;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Incremental copies of existing btsnoop files. Never enables snooping or writes the source. */
final class BluetoothSnoopCopy implements Closeable {
    interface Output { File create(String name) throws Exception; }
    private final Output output;
    // Device/inode identity survives active -> .last rename; do not copy the same records twice.
    private final Map<String, Cursor> cursors=new HashMap<>();
    private int generation;
    private long completeRecords;
    synchronized String status() {
        if (completeRecords > 0) return "HCI completeRecords="+completeRecords+" copied_existing; may include historical records; live coverage unverified; snooping NOT enabled by collector";
        if (generation > 0) return "HCI header_only; no complete records copied; snooping NOT enabled by collector";
        return "HCI unavailable; no valid complete capture copied; snooping NOT enabled by collector";
    }
    private static final byte[] MAGIC={98,116,115,110,111,111,112,0};
    private static final class Cursor {
        String identity;
        byte[] header;
        long offset=16;
        File target;
        byte[] lastRecord;
        long lastStart;
    }
    BluetoothSnoopCopy(Output output) { this.output=output; }
    synchronized String copy(File source, String identity, BooleanSupplier stop) throws Exception {
        if(stop.getAsBoolean()) return "cancelled";
        try(RandomAccessFile in=new RandomAccessFile(source,"r")) {
            long limit=in.length();
            if(limit<16) return "pending_header bytes="+limit;
            byte[] header=new byte[16]; in.readFully(header);
            if(!Arrays.equals(MAGIC,Arrays.copyOf(header,8)) || header[8]!=0 || header[9]!=0 || header[10]!=0 || header[11]!=1)
                throw new IOException("not btsnoop v1");
            Cursor c=cursors.get(identity);
            boolean changed=c==null || !identity.equals(c.identity) || limit<c.offset || !Arrays.equals(header,c.header);
            // Detect same-inode truncation/rewrite that has already grown back beyond our cursor.
            if(!changed && c.lastRecord!=null) {
                byte[] previous=new byte[c.lastRecord.length];
                in.seek(c.lastStart); in.readFully(previous);
                changed=!Arrays.equals(previous,c.lastRecord);
            }
            if(changed) {
                c=new Cursor(); c.identity=identity; c.header=header;
                c.target=output.create(String.format(Locale.ROOT,"bluetooth-%04d.btsnoop",++generation));
                try(FileOutputStream out=new FileOutputStream(c.target)) { out.write(header); out.getFD().sync(); }
                cursors.put(identity,c);
            }
            long records=0;
            try(RandomAccessFile out=new RandomAccessFile(c.target,"rw")) {
                out.seek(out.length());
                while(!stop.getAsBoolean() && limit-c.offset>=24) {
                    in.seek(c.offset);
                    long original=(in.readInt() & 0xffffffffL), included=(in.readInt() & 0xffffffffL);
                    if(included>original || included>16*1024*1024) throw new IOException("invalid btsnoop record at "+c.offset);
                    if(limit-c.offset<24+included) break;
                    byte[] record=new byte[24+(int)included]; in.seek(c.offset); in.readFully(record);
                    long boundary=out.getFilePointer();
                    try { out.write(record); }
                    catch(IOException e) { out.setLength(boundary); throw e; }
                    c.lastStart=c.offset; c.lastRecord=record; c.offset+=record.length; records++; completeRecords++;
                }
                out.getFD().sync();
            }
            return "output="+c.target.getName()+" completeBytes="+c.offset+" newRecords="+records+" sourceBytes="+limit+" pendingBytes="+(limit-c.offset);
        }
    }
    @Override public void close() { /* All handles are scoped to a single bounded snapshot. */ }
}
