package com.shilapi.xcertplay.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only evidence discovery. root is / on the device; no shell interprets discovered values. */
final class CaptureEvidence {
    static final String[] HOSTAPD_CONFIGS = {"/data/misc/wifi/hostapd.conf", "/data/vendor/wifi/hostapd/hostapd.conf"};
    static final String[] BLUETOOTH_CONFIGS={"/system/etc/bluetooth/bt_stack.conf","/vendor/etc/bluetooth/bt_stack.conf","/etc/bluetooth/bt_stack.conf"};
    static final String[] SNOOP_PATHS = {
        "/data/misc/bluetooth/logs/btsnoop_hci.log", "/data/misc/bluetooth/logs/btsnoop_hci.log.last",
        "/data/misc/bluedroid/btsnoop_hci.log", "/data/misc/bluedroid/btsnoop_hci.log.last",
        "/data/vendor/bluetooth/logs/btsnoop_hci.log", "/sdcard/btsnoop_hci.log"
    };
    static File at(File root, String path) { return new File(root, path.startsWith("/") ? path.substring(1) : path); }
    private static String text(File root, String path) throws IOException {
        try(InputStream in=new FileInputStream(at(root,path)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static void line(OutputStream out, String value) throws IOException { out.write((value+"\n").getBytes(StandardCharsets.UTF_8)); }
    static void file(File root, OutputStream out, String path) throws IOException {
        // Separate read errors from output errors: a full disk must propagate to the collector.
        String value;
        try { value = "--- "+path+" ---\n"+text(root,path); }
        catch (IOException | SecurityException e) { value=path+" unavailable="+e; }
        line(out,value);
    }
    private static File[] entries(File directory) {
        File[] files=directory.listFiles();
        if(files==null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
    static List<String[]> hostapdEndpoints(File root) {
        Map<String,String[]> result=new LinkedHashMap<>();
        for(String config:HOSTAPD_CONFIGS) try {
            String iface="ap0", control=null;
            for(String raw:text(root,config).split("\n")) {
                String value=raw.trim();
                if(value.startsWith("interface=")) iface=value.substring(10).trim();
                if(value.startsWith("ctrl_interface=")) control=value.substring(15).trim().replaceFirst("^DIR=", "").split("\\s+")[0];
            }
            if(control!=null && (control.startsWith("/data/misc/wifi/") || control.startsWith("/data/vendor/wifi/"))
                    && !control.contains("..") && iface.matches("[a-zA-Z0-9_.-]{1,15}")) {
                result.put(control+"/"+iface,new String[]{control,iface});
            }
        } catch(IOException ignored) { /* Individual config read errors are in system-snapshots.log. */ }
        for(String dir:new String[]{"/data/misc/wifi/hostapd/ctrl", "/data/vendor/wifi/hostapd/ctrl", "/data/misc/wifi/hostapd"}) {
            for(File socket:entries(at(root,dir))) if(socket.getName().matches("(?:ap|wlan|p2p)[a-zA-Z0-9_.-]*"))
                result.put(dir+"/"+socket.getName(),new String[]{dir,socket.getName()});
        }
        // Known FF-5000 endpoint remains probeable even before a config/socket appears.
        if(result.isEmpty()) result.put("fallback",new String[]{"/data/misc/wifi/hostapd/ctrl","ap0"});
        return new ArrayList<>(result.values());
    }
    static Set<String> snoopPaths(File root) {
        Set<String> paths=new LinkedHashSet<>(Arrays.asList(SNOOP_PATHS));
        for(String config:BLUETOOTH_CONFIGS) try {
            for(String raw:text(root,config).split("\n")) {
                String line=raw.trim();
                if(!line.startsWith("BtSnoopFileName=")) continue;
                String path=line.substring("BtSnoopFileName=".length()).trim();
                if((path.startsWith("/data/misc/") || path.startsWith("/data/vendor/bluetooth/") || path.startsWith("/sdcard/"))
                        && !path.contains("..")) paths.add(path);
            }
        } catch(IOException ignored) { /* Errors recorded separately, never change configuration. */ }
        return paths;
    }
    static void audioResources(File root, OutputStream out, int app, BooleanSupplier stop) throws IOException {
        Set<Integer> pids=new LinkedHashSet<>(); pids.add(app);
        for(File entry:entries(at(root,"/proc"))) {
            if(stop.getAsBoolean()) return;
            if(!entry.getName().matches("[0-9]+") || pids.size()>=8) continue;
            try {
                String name=text(root,"/proc/"+entry.getName()+"/comm").trim();
                if(name.equals("audioserver") || name.equals("mediaserver")) pids.add(Integer.parseInt(entry.getName()));
            } catch(IOException | NumberFormatException ignored) { }
        }
        line(out,"audio scheduling pids="+pids+" includes_app="+app+" maxProcesses=8 maxThreadsPerProcess=256");
        for(int pid:pids) {
            int count=0;
            for(File task:entries(at(root,"/proc/"+pid+"/task"))) {
                if(stop.getAsBoolean()) return;
                if(!task.getName().matches("[0-9]+")) continue;
                if(count++>=256) { line(out,"audio scheduling truncated pid="+pid+" threadLimit=256"); break; }
                for(String name:new String[]{"comm","stat","schedstat","sched"}) {
                    if(stop.getAsBoolean()) return;
                    file(root,out,"/proc/"+pid+"/task/"+task.getName()+"/"+name);
                }
            }
        }
    }
    static void resources(File root, OutputStream out, int app, int collector, BooleanSupplier stop, BooleanSupplier heavy) throws IOException {
        resourcesWithMode(root,out,app,collector,stop,heavy);
    }
    static void resources(File root, OutputStream out, int app, int collector, BooleanSupplier stop) throws IOException {
        resourcesWithMode(root,out,app,collector,stop,()->true);
    }
    private static void resourcesWithMode(File root, OutputStream out, int app, int collector, BooleanSupplier stop, BooleanSupplier heavy) throws IOException {
        if(stop.getAsBoolean()) return;
        for(String path:new String[]{"/proc/stat","/proc/diskstats","/proc/vmstat","/proc/loadavg","/proc/meminfo",
                "/proc/pressure/cpu","/proc/pressure/io","/proc/pressure/memory","/sys/devices/system/cpu/online"}) {
            if(stop.getAsBoolean()) return;
            file(root,out,path);
        }
        Set<Integer> pids=new LinkedHashSet<>(Arrays.asList(app,collector));
        for(File entry:entries(at(root,"/proc"))) {
            if(stop.getAsBoolean()) return;
            if(!entry.getName().matches("[0-9]+")) continue;
            try {
                String name=text(root,"/proc/"+entry.getName()+"/comm").trim();
                if(name.equals("audioserver") || name.equals("mediaserver") || name.startsWith("media.codec") ||
                        name.equals("surfaceflinger") || name.equals("system_server") || name.equals("logcat") || name.equals("dmesg") || name.equals("logd") || name.startsWith("android.hardwar") ||
                        name.contains("bluetooth")) pids.add(Integer.parseInt(entry.getName()));
            } catch(IOException | NumberFormatException ignored) { /* Process may exit during discovery. */ }
        }
        for(int pid:pids) {
            for(String name:new String[]{"stat","status","io","schedstat","cmdline"}) {
                if(stop.getAsBoolean()) return;
                file(root,out,"/proc/"+pid+"/"+name);
            }
            if(!heavy.getAsBoolean()) { line(out,"threadScans=disabled pid="+pid); continue; }
            for(File thread:entries(at(root,"/proc/"+pid+"/task"))) {
                if(!heavy.getAsBoolean()) break;
                if(!thread.getName().matches("[0-9]+")) continue;
                for(String name:new String[]{"stat","schedstat","wchan","io"}) {
                    if(stop.getAsBoolean()) return;
                    if(!heavy.getAsBoolean()) break;
                    file(root,out,"/proc/"+pid+"/task/"+thread.getName()+"/"+name);
                }
            }
        }
        boolean frequency=false, thermal=false;
        for(File cpu:entries(at(root,"/sys/devices/system/cpu"))) {
            if(!cpu.getName().matches("cpu[0-9]+")) continue;
            String base="/sys/devices/system/cpu/"+cpu.getName()+"/cpufreq/";
            if(!at(root,base).isDirectory()) continue;
            frequency=true;
            for(String name:new String[]{"scaling_cur_freq","cpuinfo_cur_freq","scaling_min_freq","scaling_max_freq","scaling_governor"}) {
                if(stop.getAsBoolean()) return;
                file(root,out,base+name);
            }
        }
        for(File zone:entries(at(root,"/sys/class/thermal"))) {
            if(!zone.getName().matches("thermal_zone[0-9]+")) continue;
            thermal=true;
            for(String name:new String[]{"type","temp"}) {
                if(stop.getAsBoolean()) return;
                file(root,out,"/sys/class/thermal/"+zone.getName()+"/"+name);
            }
        }
        if(!frequency) line(out,"cpufreq unavailable=no readable cpu*/cpufreq directories");
        if(!thermal) line(out,"thermal unavailable=no readable thermal_zone directories");
    }
}
