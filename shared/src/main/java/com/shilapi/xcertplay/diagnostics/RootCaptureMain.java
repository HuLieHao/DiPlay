package com.shilapi.xcertplay.diagnostics;

import android.os.SystemClock;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate root process; never runs on the connection thread. All commands are read-only. */
public final class RootCaptureMain {
    private static native int capture(String file, String stopFile, int uid);
    private static final List<Process> children = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean stopping;
    private static File directory;
    private static int owner;
    private static int appPid;
    // Accessed only by the route worker. Periodic probes yield between commands to new event records.
    private static long routeJournalOffset;
    private static File routeJournal;
    private static File audioJournal;
    private static final AudioCaptureEvents.Snapshots audioPending=new AudioCaptureEvents.Snapshots();
    private static volatile long audioOffset;
    private static volatile boolean audioSnapshotInFlight;
    private static Boolean lastHeavyMode;
    private static final AudioCaptureMode.Cached audioModeCache=new AudioCaptureMode.Cached(System::nanoTime);
    private static boolean heavyAudioEnabled() { return heavyAudioEnabled(false); }
    private static synchronized boolean heavyAudioEnabled(boolean fresh) {
        File file=audioJournal==null?null:new File(audioJournal.getParentFile(),"audio-heavy-mode.txt");
        boolean enabled=fresh?audioModeCache.refresh(file):audioModeCache.enabled(file);
        if(lastHeavyMode==null || lastHeavyMode!=enabled) {
            lastHeavyMode=enabled;
            String status="heavyAudioObserved="+(enabled?"on":"off")+" wallMs="+System.currentTimeMillis()+
                " elapsedMs="+SystemClock.elapsedRealtime()+" rawAudioEvents=retained rawPcap=retained lightweightCounters=retained";
            event(status);
            if(directory!=null) try(FileOutputStream out=new FileOutputStream(output("audio-heavy-status.txt"))) {
                CaptureEvidence.line(out,status);
            } catch(Exception e) { event("audio mode status unavailable="+e); }
        }
        return enabled;
    }
    private static void audioStopBoundary(String scope) {
        if(directory==null || audioJournal==null) return;
        String boundary=AudioCaptureEvents.boundary(audioOffset,audioJournal.length(),scope)+" snapshotInFlight="+audioSnapshotInFlight+" "+audioPending.status();
        event("audio stop "+boundary);
        try(FileOutputStream out=new FileOutputStream(output("audio-reader-stop.txt"))) {
            CaptureEvidence.line(out,""+audioOffset);
            CaptureEvidence.line(out,boundary);
        } catch(Exception e) { event("audio stop boundary unavailable="+e); }
    }
    private static void audioReader() {
        while(!stopping) {
            if(audioJournal.isFile()) try(RandomAccessFile input=new RandomAccessFile(audioJournal,"r")) {
                input.seek(audioOffset);
                for(int i=0;i<512 && !stopping;i++) {
                    long before=input.getFilePointer(); String line=input.readLine();
                    if(line==null) break;
                    long after=input.getFilePointer(); input.seek(after-1);
                    if(input.read()!='\n') { input.seek(before); break; }
                    audioOffset=after;
                    if(AudioCaptureEvents.valid(line)) audioPending.offer(line);
                    else event("audio event rejected offset="+before);
                }
            } catch(IOException e) { event("audio journal unavailable="+e); }
            sleep(100);
        }
        audioPending.seal();
        audioStopBoundary("reader_stopped_observation");
    }
    private static void audioSnapshots() {
        while(!stopping) {
            String trigger=audioPending.take();
            if(trigger==null) { sleep(100); continue; }
            if(stopping) { audioPending.cancelledBeforeProbe(); break; }
            if(!heavyAudioEnabled()) { event("audio snapshot skipped heavyAudioObserved=off trigger="+trigger); continue; }
            audioSnapshotInFlight=true;
            long start=SystemClock.elapsedRealtime();
            String[] fields=trigger.split("\t",-1);
            long detectedNs=Long.parseLong(fields[3]);
            try(FileOutputStream out=new FileOutputStream(output("audio-anomalies.log"),true)) {
                CaptureEvidence.line(out,"audio snapshot trigger="+trigger+" snapshotStartElapsedMs="+start+
                    " detectedToSnapshotNs="+(System.nanoTime()-detectedNs)+" "+audioPending.status()+
                    " occurrenceTime=see_event_observation_window");
            } catch(Exception e) { event("audio snapshot header unavailable="+e); }
            command("audio-anomalies.log",2,"dumpsys","media.audio_flinger");
            command("audio-anomalies.log",2,"dumpsys","media.audio_policy");
            command("audio-anomalies.log",2,"dumpsys","audio");
            if(heavyAudioEnabled()) try(FileOutputStream out=new FileOutputStream(output("audio-anomalies.log"),true)) {
                CaptureEvidence.audioResources(DEVICE_ROOT,out,appPid,()->stopping || !heavyAudioEnabled());
            } catch(Exception e) { event("audio scheduling unavailable="+e); }
            event("audio snapshot status="+(stopping?"stopped":!heavyAudioEnabled()?"cancelled_mode_off":"completed_commands_status_in_audio_anomalies_log")+" durationMs="+(SystemClock.elapsedRealtime()-start)+" "+audioPending.status());
            audioSnapshotInFlight=false;
            // Snapshots enforces separate urgent/ordinary limits; immediately recheck priority.
        }
    }
    private static final RouteCaptureEvents.Context routeContext=new RouteCaptureEvents.Context();
    private static final File DEVICE_ROOT = new File("/");
    private static BluetoothSnoopCopy snoop;
    private static synchronized void event(String message) {
        System.out.println("wallMs=" + System.currentTimeMillis() + " elapsedMs=" + SystemClock.elapsedRealtime() + " " + message);
        System.out.flush();
    }
    private static File output(String name) throws Exception {
        File file = new File(directory, name);
        if (!file.exists() && !file.createNewFile()) throw new IOException("create " + name);
        Os.chown(file.getPath(), owner, owner); Os.chmod(file.getPath(), 0600);
        return file;
    }
    private static Process spawn(String name, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(output(name))).start();
        children.add(process);
        if (stopping) process.destroyForcibly();
        return process;
    }
    private static String command(String name, int seconds, String... args) {
        boolean heavy=AudioCaptureMode.heavyCommand(args);
        if (stopping || (heavy && !heavyAudioEnabled(true))) return "";
        long start = SystemClock.elapsedRealtime();
        Process process = null;
        try {
            File file = output(name);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(("\nwallMs="+System.currentTimeMillis()+" elapsedMs="+start+" command="+Arrays.toString(args)+"\n").getBytes(StandardCharsets.UTF_8));
            }
            long before = file.length();
            process = spawn(name, args);
            boolean done = heavy ? AudioCaptureMode.await(process,seconds*1000L,()->stopping || !heavyAudioEnabled(true)) : process.waitFor(seconds, TimeUnit.SECONDS);
            String result="command="+Arrays.toString(args)+" durationMs="+(SystemClock.elapsedRealtime()-start)+" result="+(done?process.exitValue():(heavy && !heavyAudioEnabled()?"cancelled_mode_off":"timeout"))+" outputBytes="+Math.max(0,file.length()-before);
            event(result);
            String captured="";
            if(done && process.exitValue()==0) try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
                in.seek(before);
                byte[] bytes=new byte[(int)Math.min(65536,Math.max(0,in.length()-before))];
                in.readFully(bytes); captured=new String(bytes,StandardCharsets.UTF_8);
                if(in.length()-before>65536) event("command output parsing truncated at 65536 bytes: "+Arrays.toString(args));
            }
            try(FileOutputStream out=new FileOutputStream(file,true)) { CaptureEvidence.line(out,result); }
            return captured;
        } catch (Exception e) {
            String failure="command="+Arrays.toString(args)+" unavailable="+e;
            event(failure);
            try(FileOutputStream out=new FileOutputStream(output(name),true)) { CaptureEvidence.line(out,failure); }
            catch(Exception ignored) { }
        }
        finally { if(process!=null) {
            process.destroyForcibly();
            try { process.waitFor(2000, TimeUnit.MILLISECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(!process.isAlive()) children.remove(process);
        } }
        return "";
    }
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
    private static String identity(int pid) throws IOException {
        String stat=read("/proc/"+pid+"/stat");
        // Fields after the last ')' begin at process state (field 3); starttime is field 22.
        return stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void lightweight() {
        try (FileOutputStream out = new FileOutputStream(output("network-state.log"), true)) {
            while (!stopping) {
                out.write(("\nwallMs="+System.currentTimeMillis()+" elapsedMs="+SystemClock.elapsedRealtime()+"\n").getBytes(StandardCharsets.UTF_8));
                for(String path: new String[]{"/proc/net/tcp","/proc/net/tcp6","/proc/net/udp","/proc/net/udp6",
                        "/proc/net/arp","/proc/net/route","/proc/net/ipv6_route","/proc/net/dev","/proc/net/snmp","/proc/net/snmp6","/proc/net/netstat","/proc/net/stat/nf_conntrack",
                        "/proc/sys/net/ipv4/ip_forward","/proc/sys/net/ipv6/conf/all/forwarding","/proc/net/wireless","/proc/stat","/proc/loadavg","/proc/meminfo"}) {
                    try { out.write(("--- "+path+" ---\n"+read(path)).getBytes(StandardCharsets.UTF_8)); }
                    catch(Exception e) { out.write((path+" unavailable="+e+"\n").getBytes(StandardCharsets.UTF_8)); }
                }
                File[] ipv6Counters=new File("/proc/net/dev_snmp6").listFiles();
                if(ipv6Counters!=null) for(File counter:ipv6Counters) {
                    if(stopping) break;
                    CaptureEvidence.file(DEVICE_ROOT,out,counter.getPath());
                }
                sleep(1000);
            }
            out.getFD().sync();
        } catch(Exception e) { event("network-state failed="+e); }
    }
    private static void resourceState() {
        try(FileOutputStream out=new FileOutputStream(output("resource-state.log"),true)) {
            while(!stopping) {
                CaptureEvidence.line(out,"\nwallMs="+System.currentTimeMillis()+" elapsedMs="+SystemClock.elapsedRealtime()+" appPid="+appPid+" collectorPid="+Os.getpid());
                CaptureEvidence.resources(DEVICE_ROOT,out,appPid,Os.getpid(),()->stopping,RootCaptureMain::heavyAudioEnabled);
                for(int i=0;i<5&&!stopping;i++) sleep(1000);
            }
            out.getFD().sync();
        } catch(Exception e) { event("resource-state failed="+e); }
    }
    private static void returnRoutes(List<RouteCaptureEvents.Event> triggers) {
        String name="return-routes.log";
        long started=SystemClock.elapsedRealtime();
        // Event probes prioritize addresses/neighbours; expensive mark discovery belongs to periodic probes.
        boolean urgent=!triggers.isEmpty();
        long deadline=started+(urgent?8000:20000);
        int commandSeconds=urgent?1:2;
        routeContext.observe(triggers);
        RouteCaptureEvents.Event latest=routeContext.latest;
        String routeInterface=latest==null?"":latest.interfaceName;
        List<String> preferred=routeContext.peers();
        try(FileOutputStream out=new FileOutputStream(output(name),true)) {
            CaptureEvidence.line(out,"\nroute snapshot startWallMs="+System.currentTimeMillis()+" startElapsedMs="+started+
                " reason="+(urgent?"event":"periodic")+" contextAttempt="+(latest==null?"unknown":latest.attempt)+
                " triggers="+triggers.size()+" coalescedSnapshot="+(triggers.size()>1));
            for(RouteCaptureEvents.Event trigger:triggers) CaptureEvidence.line(out,trigger.evidence()+
                " snapshotDelayMs="+Math.max(0,started-trigger.elapsedMs)+" sampledContext="+trigger.attempt.equals(latest.attempt));
            CaptureEvidence.line(out,"route probe collectorUid="+Os.getuid()+" appUid="+owner+
                " context=local_output; baseline uses root UID, implicit source/oif/mark; scoped probes use neighbour interface, first same-scope local source (if present), explicit mark=0; uid option probes app UID if supported; rule/MARK-config-derived marks are hypothetical selectors, NOT observed socket marks; no iif/forwarded lookup; not proof of actual app socket route. Limits: 2 peers/family, 2 configured marks/family, 24 queries, event budget 8s/periodic 20s total; event probes omit configured mark discovery; command failures/unsupported options retained below.");
        } catch(Exception e) { event("route context unavailable="+e); }
        String[] evidence=new String[7]; Arrays.fill(evidence, "");
        String[][] discovery={{"ip","-4","neigh","show"},{"ip","-6","neigh","show"},
            { "ip","-o","addr","show"},{"ip","-4","rule","show"},{"ip","-6","rule","show"},
            {"iptables-save","-t","mangle"},{"ip6tables-save","-t","mangle"}};
        for(int i=0;i<(urgent?3:discovery.length);i++) {
            String stopReason=routeStopReason(urgent,deadline,commandSeconds);
            if(stopReason!=null) {
                routeSummary("route discovery partial completed="+i+" planned="+(urgent?3:discovery.length)+
                    " stop_reason="+stopReason+" selection=not_reached; NOT connectivity success");
                return;
            }
            evidence[i]=command(name,commandSeconds,discovery[i]);
        }
        ReturnRouteEvidence.Plan plan=ReturnRouteEvidence.plan(evidence[0],evidence[1],evidence[2],ReturnRouteEvidence.ingressMarks(evidence[5])+evidence[3],ReturnRouteEvidence.ingressMarks(evidence[6])+evidence[4],owner,routeInterface,preferred);
        List<String[]> queries=plan.queries;
        try(FileOutputStream out=new FileOutputStream(output(name),true)) {
            for(String record:plan.records) CaptureEvidence.line(out,record);
        } catch(Exception e) { event("route selection evidence unavailable="+e); }
        event("return-route plannedQueries="+queries.size()+" collectorUid=0 appUid="+owner);
        int executed=0;
        String stopReason="complete";
        for(String[] query:queries) {
            String cancelled=routeStopReason(urgent,deadline,commandSeconds);
            if(cancelled!=null) { stopReason=cancelled; break; }
            command(name,commandSeconds,query); executed++;
        }
        routeSummary("route probes executed="+executed+" planned="+queries.size()+
            " unexecuted="+(queries.size()-executed)+" stop_reason="+stopReason+
            " bounded=true; planned=0 means no selected valid target; NOT connectivity success");
    }
    private static String routeStopReason(boolean urgent,long deadline,int commandSeconds) {
        if(stopping) return "stopping";
        if(!urgent && routeEventWaiting()) return "preempted";
        if(SystemClock.elapsedRealtime()+commandSeconds*1000>deadline) return "time_budget";
        return null;
    }
    private static void routeSummary(String summary) {
        try(FileOutputStream out=new FileOutputStream(output("return-routes.log"),true)) {
            CaptureEvidence.line(out,summary+" endWallMs="+System.currentTimeMillis()+" endElapsedMs="+SystemClock.elapsedRealtime());
        } catch(Exception e) { event("route summary unavailable="+e); }
    }
    private static boolean routeEventWaiting() {
        return routeJournal.length()>routeJournalOffset;
    }
    private static void routeWorker() {
        File journal=routeJournal;
        new RouteCaptureWorker(() -> {
            RouteCaptureEvents.Pending pending=new RouteCaptureEvents.Pending();
            if(journal.isFile()) try(RandomAccessFile input=new RandomAccessFile(journal,"r")) {
                input.seek(routeJournalOffset);
                for(int i=0;i<256 && !stopping;i++) {
                    long before=input.getFilePointer();
                    String line=input.readLine();
                    if(line==null) break;
                    // An app append can be observed before its final newline. Retry that record later.
                    long after=input.getFilePointer(); input.seek(after-1); int last=input.read();
                    if(last!='\n') { input.seek(before); break; }
                    routeJournalOffset=after;
                    RouteCaptureEvents.Event parsed=RouteCaptureEvents.Event.parse(line);
                    if(parsed!=null) pending.offer(parsed); else event("route event rejected atOffset="+before);
                }
            } catch(IOException error) { event("route event journal unavailable="+error); }
            if(pending.coalesced>0 || pending.dropped>0) event("route worker batch "+pending.status());
            return pending.drain();
        },RootCaptureMain::returnRoutes,()->stopping,SystemClock::elapsedRealtime,()->Thread.sleep(100)).run();
    }
    private static void snapshots() {
        command("system-baseline.log",5,"getprop");
        command("system-baseline.log",5,"getenforce");
        command("system-baseline.log",5,"id");
        command("system-baseline.log",3,"cat","/proc/cpuinfo");
        try { event("resource-counter clockTicksPerSecond="+Os.sysconf(android.system.OsConstants._SC_CLK_TCK)); }
        catch(Exception e) { event("clock-tick-rate unavailable="+e); }
        String[][] commands={
            {"cmd","wifi","status"},{"ip","-details","addr","show"},{"ip","route","show","table","all"},
            {"ip","-6","route","show","table","all"},{"ip","rule","show"},{"ip","-6","rule","show"},
            {"ip","neigh","show"},{"ip","-6","neigh","show"},
            {"iptables-save","-c"},{"ip6tables-save","-c"},{"iw","dev"},
            {"iw","dev","ap0","station","dump"},{"iw","dev","wlan0","station","dump"},
            {"iw","dev","ap0","info"},{"iw","dev","ap0","survey","dump"},
            {"dumpsys","wifi"},{"dumpsys","connectivity"},{"dumpsys","tethering"},
            {"dumpsys","bluetooth_manager"},{"dumpsys","cpuinfo"},{"ps","-A"},{"dmesg"},
            {"dumpsys","media.audio_flinger"},{"dumpsys","media.audio_policy"},{"dumpsys","audio"},
            {"cat","/data/misc/dhcp/dnsmasq.leases","/data/misc/dhcp/dnsmasq.leases6","/data/misc/wifi/dnsmasq.leases","/data/vendor/dhcp/dnsmasq.leases"},
            {"cat","/data/misc/wifi/hostapd.conf","/data/vendor/wifi/hostapd/hostapd.conf"}
        };
        while(!stopping) {
            for(String[] endpoint:CaptureEvidence.hostapdEndpoints(DEVICE_ROOT)) {
                command("system-snapshots.log",3,"hostapd_cli","-p",endpoint[0],"-i",endpoint[1],"status");
                command("system-snapshots.log",3,"hostapd_cli","-p",endpoint[0],"-i",endpoint[1],"all_sta");
            }
            bluetoothEvidence(false);
            for(String[] args:commands) { if(stopping) return; command("system-snapshots.log",3,args); }
            for(int i=0;i<30&&!stopping;i++) sleep(1000);
        }
    }
    private static void bluetoothEvidence(boolean finalSnapshot) {
        long deadline=finalSnapshot ? SystemClock.elapsedRealtime()+2000 : Long.MAX_VALUE;
        java.util.function.BooleanSupplier cancelled=()->(stopping && !finalSnapshot) || SystemClock.elapsedRealtime()>=deadline;
        try(FileOutputStream out=new FileOutputStream(output("bluetooth-evidence.log"),true)) {
            CaptureEvidence.line(out,"\nwallMs="+System.currentTimeMillis()+" elapsedMs="+SystemClock.elapsedRealtime()+" finalSnapshot="+finalSnapshot);
            for(String config:CaptureEvidence.BLUETOOTH_CONFIGS) {
                if(cancelled.getAsBoolean()) break;
                CaptureEvidence.file(DEVICE_ROOT,out,config);
            }
            boolean found=false;
            for(String path:CaptureEvidence.snoopPaths(DEVICE_ROOT)) {
                if(cancelled.getAsBoolean()) break;
                try {
                    android.system.StructStat stat=Os.stat(path);
                    String result=snoop.copy(new File(path),stat.st_dev+":"+stat.st_ino,cancelled);
                    CaptureEvidence.line(out,"source="+path+" "+result); found=true;
                } catch(Exception e) { CaptureEvidence.line(out,"source="+path+" unavailable="+e); }
            }
            if(!found) CaptureEvidence.line(out,"HCI unavailable=no existing readable btsnoop capture; snooping was NOT enabled by this collector");
            String status=snoop.status()+"; latestSnapshotReadable="+found+" wallMs="+System.currentTimeMillis();
            CaptureEvidence.line(out,status);
            try(FileOutputStream statusOut=new FileOutputStream(output("bluetooth-status.txt"))) {
                CaptureEvidence.line(statusOut,status);
            }
            event(status);
            out.getFD().sync();
        } catch(Exception e) { event("bluetooth-evidence failed="+e); }
    }
    public static void main(String[] args) {
        if(args.length!=6) { System.err.println("expected directory uid parentPid parentStart nativeLibrary appRouteJournal"); System.exit(2); }
        Thread packets=null, light=null, slow=null, resources=null, routes=null, audioRead=null, audioProbe=null;
        try {
            if(Os.getuid()!=0) throw new SecurityException("root required; uid="+Os.getuid());
            directory=new File(args[0]).getCanonicalFile();
            routeJournal=RouteCaptureEvents.journal(directory,args[5]);
            audioJournal=AudioCaptureEvents.journal(directory,routeJournal.getParentFile()); owner=Integer.parseInt(args[1]);
            int parent=Integer.parseInt(args[2]); appPid=parent; String parentStart=args[3];
            if(owner<10000 || !directory.isDirectory() || !identity(parent).equals(parentStart)) throw new SecurityException("invalid capture owner");
            event("collector started parent="+parent+" parentStart="+parentStart+" uid="+Os.getuid()+" captureScope=all_interfaces no_size_rotation=true");
            File stop=new File(directory,"stop");
            if(stop.exists()) throw new IOException("launch cancelled before root initialization");
            heavyAudioEnabled();
            output("root-ready");
            snoop=new BluetoothSnoopCopy(RootCaptureMain::output);
            try(FileOutputStream statusOut=new FileOutputStream(output("bluetooth-status.txt"))) {
                CaptureEvidence.line(statusOut,"HCI pending; existing snoop files not yet checked; snooping NOT enabled by collector");
            }
            try {
                System.load(args[4]);
                packets=new Thread(() -> {
                    int result=capture(new File(directory,"network.pcap").getPath(),stop.getPath(),owner);
                    event("native_capture_exit errno="+result);
                },"root-packets");
                packets.start();
            } catch(Throwable e) { event("native_capture_unavailable="+e); }
            // Also collect without driver support or native capture permission.
            Process logcat=null, kernel=null;
            try { logcat=spawn("logcat-all.log","logcat","-b","all","-v","threadtime"); }
            catch(Exception e) { event("logcat start failed="+e); }
            try { kernel=spawn("kernel-follow.log","dmesg","-w"); }
            catch(Exception e) { event("kernel-follow unavailable="+e+" periodic dmesg remains enabled"); }
            light=new Thread(RootCaptureMain::lightweight,"root-network-state"); light.setDaemon(true); light.start();
            resources=new Thread(RootCaptureMain::resourceState,"root-resource-state"); resources.setDaemon(true); resources.start();
            routes=new Thread(RootCaptureMain::routeWorker,"root-return-routes"); routes.setDaemon(true); routes.start();
            audioRead=new Thread(RootCaptureMain::audioReader,"root-audio-reader"); audioRead.setDaemon(true); audioRead.start();
            audioProbe=new Thread(RootCaptureMain::audioSnapshots,"root-audio-snapshots"); audioProbe.setDaemon(true); audioProbe.start();
            slow=new Thread(RootCaptureMain::snapshots,"root-service-state"); slow.setDaemon(true); slow.start();
            // Generate a known loopback packet only after the capture has opened its socket/file.
            for(int i=0;i<20 && packets!=null && packets.isAlive() && !new File(directory,"network.pcap.complete").isFile();i++) sleep(100);
            try(java.net.DatagramSocket probe=new java.net.DatagramSocket()) {
                byte[] payload=("BuickPlay-capture-selftest "+directory.getName()).getBytes(StandardCharsets.UTF_8);
                probe.send(new java.net.DatagramPacket(payload,payload.length,java.net.InetAddress.getLoopbackAddress(),9));
                event("capture_selftest loopback_udp_sent=true port=9; confirms local capture only, not hotspot traffic");
            } catch(Exception e) { event("capture_selftest failed="+e); }
            long tick=0;
            while(!stop.exists() && identity(parent).equals(parentStart)) {
                if(tick++%5==0) event("health packetThread="+(packets!=null&&packets.isAlive())+
                    " logcat="+(logcat!=null&&logcat.isAlive())+" kernelFollow="+(kernel!=null&&kernel.isAlive())+
                    " resourceThread="+(resources!=null&&resources.isAlive())+" routeThread="+(routes!=null&&routes.isAlive())+" audioReader="+(audioRead!=null&&audioRead.isAlive())+" audioSnapshots="+(audioProbe!=null&&audioProbe.isAlive())+" snapshotThread="+(slow!=null&&slow.isAlive())+" freeBytes="+directory.getUsableSpace());
                sleep(1000);
            }
        } catch(Throwable e) { event("collector exiting="+e); }
        finally {
            stopping=true;
            audioPending.seal();
            audioStopBoundary("observed_at_stop");
            if(directory!=null) try { output("stop"); } catch(Exception e) { event("stop marker failed="+e); }
            synchronized(children) {
                for(Process child:children) child.destroyForcibly();
                for(Process child:children) try { child.waitFor(2000, TimeUnit.MILLISECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if(light!=null) try { light.join(3000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(resources!=null) try { resources.join(3000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(routes!=null) try { routes.join(5000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(audioRead!=null) try { audioRead.join(3000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(audioProbe!=null) try { audioProbe.join(5000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(slow!=null) try { slow.join(5000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(packets!=null) try { packets.join(2000); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if(snoop!=null && (slow==null || !slow.isAlive())) { bluetoothEvidence(true); snoop.close(); }
            audioStopBoundary("collector_stopped_observation");
            event("collector stopped");
            if(directory!=null && (packets==null || !packets.isAlive()) && (light==null || !light.isAlive()) && (slow==null || !slow.isAlive()) && (resources==null || !resources.isAlive()) && (routes==null || !routes.isAlive()) && (audioRead==null || !audioRead.isAlive()) && (audioProbe==null || !audioProbe.isAlive()) && children.stream().noneMatch(Process::isAlive)) try { output("collector-stopped"); } catch(Exception e) { event("stop confirmation failed="+e); }
            System.exit(0);
        }
    }
}
