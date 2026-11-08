package com.shilapi.xcertplay.diagnostics;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;

/** Bounded numeric-only argv construction; never resolves hostnames or invokes a shell. */
final class ReturnRouteEvidence {
    static final int MAX_QUERIES = 24;
    private static boolean address(String value, boolean ipv6) {
        if (ipv6) {
            if (!value.contains(":") || !value.matches("[0-9a-fA-F:.]{2,45}")) return false;
            try { return InetAddress.getByName(value) instanceof Inet6Address; }
            catch (Exception ignored) { return false; }
        }
        if (!value.matches("[0-9.]{7,15}")) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) try { if (part.isEmpty() || Integer.parseInt(part) > 255) return false; }
        catch (NumberFormatException e) { return false; }
        return true;
    }
    private static boolean iface(String value) { return value.matches("[a-zA-Z0-9_.-]{1,15}"); }
    private static List<String> marks(String rules) {
        List<String> marks = new ArrayList<>();
        for (String line : rules.split("\n")) {
            String[] words = line.trim().split("\\s+");
            for (int i=0; i+1<words.length; i++) if (words[i].equals("fwmark")) {
                String mark=words[i+1].split("/",-1)[0];
                if (mark.matches("0x[0-9a-fA-F]{1,8}|[0-9]{1,10}")) {
                    try { if (Long.decode(mark)>0xffffffffL) continue; } catch(NumberFormatException e) { continue; }
                    if (!marks.contains(mark)) marks.add(mark);
                }
                if (marks.size() == 2) return marks;
            }
        }
        return marks;
    }
    // Values configured by MARK rules are candidate lookup contexts, not observed packet/socket marks.
    static String ingressMarks(String mangle) {
        StringBuilder result=new StringBuilder();
        for(String line:mangle.split("\n")) {
            String[] fields=line.trim().split("\\s+");
            if(!line.contains("-j MARK")) continue;
            for(int i=0;i+1<fields.length;i++) if(fields[i].equals("--set-xmark") || fields[i].equals("--set-mark")) {
                String value=fields[i+1].split("/",-1)[0];
                if(value.matches("0x[0-9a-fA-F]{1,8}|[0-9]{1,10}")) result.append("fwmark ").append(value).append('\n');
            }
        }
        return result.toString();
    }
    private static boolean linkLocal(String value) { return value.toLowerCase(Locale.ROOT).matches("fe[89ab][0-9a-f]:.*"); }
    static final class Plan {
        final List<String[]> queries;
        final List<String> records;
        Plan(List<String[]> queries, List<String> records) {
            this.queries = Collections.unmodifiableList(queries);
            this.records = Collections.unmodifiableList(records);
        }
    }
    private static final class Candidate {
        final String destination, device, raw, state;
        final boolean v6, explicit;
        Candidate(String destination, String device, String raw, String state, boolean v6, boolean explicit) {
            this.destination=destination; this.device=device; this.raw=raw; this.state=state;
            this.v6=v6; this.explicit=explicit;
        }
        String key() { return destination.toLowerCase(Locale.ROOT)+"%"+device; }
    }
    private static int stateRank(String state) {
        if (state.equals("REACHABLE")) return 0;
        if (state.equals("DELAY") || state.equals("PROBE")) return 1;
        if (state.equals("STALE")) return 2;
        if (state.equals("FAILED") || state.equals("INCOMPLETE")) return 4;
        return 3;
    }
    static List<String[]> queries(String v4, String v6, String addresses, String rules4, String rules6, int appUid) {
        return plan(v4,v6,addresses,rules4,rules6,appUid,"",Collections.emptyList()).queries;
    }
    /** Target hints must be observed peer IPs; fallback ranking does not establish phone identity. */
    static Plan plan(String v4, String v6, String addresses, String rules4, String rules6, int appUid,
                     String currentInterface, List<String> preferredTargets) {
        List<String> records=new ArrayList<>();
        List<Candidate> candidates=new ArrayList<>();
        int rejected=0, truncated=0, selected=0, validTargets=0;
        for (String target : preferredTargets) {
            boolean v6target=target!=null && address(target,true);
            if(target==null || (!v6target && !address(target,false))) {
                records.add("candidate outcome=rejected reason=invalid_target raw="+target); rejected++; continue;
            }
            if(currentInterface==null || !iface(currentInterface)) {
                records.add("candidate outcome=rejected reason=invalid_target_interface raw="+target+" interface="+currentInterface);
                rejected++; continue;
            }
            candidates.add(new Candidate(target,currentInterface,target,"observed_peer",v6target,true)); validTargets++;
        }
        records.add("target_identity="+(validTargets>0?"explicit_observed_peer":"unknown")+
                " interface="+currentInterface+" fallback_identity=unverified");
        for (int family=0; family<2; family++) {
            for(String line:(family==0?v4:v6).split("\n")) {
                if(line.trim().isEmpty()) continue;
                String[] words=line.trim().split("\\s+");
                if(words.length<3 || !address(words[0],family==1) || !words[1].equals("dev") || !iface(words[2])) {
                    records.add("candidate outcome=rejected reason=invalid_neighbour raw="+line); rejected++; continue;
                }
                String state="UNKNOWN";
                for(String word:words) if(Arrays.asList("REACHABLE","DELAY","PROBE","STALE","FAILED","INCOMPLETE","PERMANENT","NOARP").contains(word)) state=word;
                candidates.add(new Candidate(words[0],words[2],line,state,family==1,false));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate c)->c.explicit?0:1)
                .thenComparingInt(c->c.device.equals(currentInterface)?0:1)
                .thenComparingInt(c->stateRank(c.state))
                .thenComparingInt(c->c.device.equals("ap0")?0:c.device.equals("wlan0")?1:2));
        Set<String> seen=new HashSet<>();
        int[] peers={0,0};
        List<String[]> ipv4=new ArrayList<>(), ipv6=new ArrayList<>(), result=new ArrayList<>();
        for(Candidate candidate:candidates) {
            String details=" origin="+(candidate.explicit?"explicit_observed_peer":"fallback")+
                    " destination="+candidate.destination+" interface="+candidate.device+" state="+candidate.state+" raw="+candidate.raw;
            if(!seen.add(candidate.key())) {
                records.add("candidate outcome=rejected reason=duplicate"+details); rejected++; continue;
            }
            int family=candidate.v6?1:0;
            if(peers[family]>=2) {
                records.add("candidate outcome=truncated reason=peer_limit"+details); truncated++; continue;
            }
            peers[family]++; selected++;
            records.add("candidate outcome=selected reason="+(candidate.explicit?"explicit_target":"interface_state_rank")+details);
            family(candidate.v6?ipv6:ipv4,candidate,addresses,marks(candidate.v6?rules6:rules4),appUid);
        }
        // Interleave families so unsupported/slow IPv4 lookups cannot consume every IPv6 probe.
        for(int i=0;i<Math.max(ipv4.size(),ipv6.size()) && result.size()<MAX_QUERIES;i++) {
            if(i<ipv4.size()) result.add(ipv4.get(i));
            if(i<ipv6.size() && result.size()<MAX_QUERIES) result.add(ipv6.get(i));
        }
        records.add("selection_summary selected="+selected+" rejected="+rejected+" truncated="+truncated+
                " queries="+result.size()+" queries_truncated="+(ipv4.size()+ipv6.size()-result.size())+
                " peer_limit_per_family=2 query_limit="+MAX_QUERIES);
        return new Plan(result,records);
    }
    private static void family(List<String[]> result,Candidate candidate,String addresses,List<String> marks,int uid) {
        String destination=candidate.destination, device=candidate.device;
        boolean v6=candidate.v6;
        List<String> base=new ArrayList<>(Arrays.asList("ip",v6?"-6":"-4","route","get",destination));
        // Link-local destinations need an explicit scope even for the baseline lookup.
        if(v6 && linkLocal(destination)) base.addAll(Arrays.asList("oif",device));
        add(result,base);
        String source=null;
        for(String row:addresses.split("\n")) {
            String[] fields=row.trim().split("\\s+");
            if(fields.length<4 || !fields[1].split("@",2)[0].equals(device) || !fields[2].equals(v6?"inet6":"inet")) continue;
            String sourceCandidate=fields[3].split("/",2)[0];
            if(address(sourceCandidate,v6) && (!v6 || linkLocal(sourceCandidate)==linkLocal(destination))) { source=sourceCandidate; break; }
        }
        List<String> context=new ArrayList<>(Arrays.asList("ip",v6?"-6":"-4","route","get",destination));
        if(source!=null) context.addAll(Arrays.asList("from",source));
        context.addAll(Arrays.asList("oif",device,"mark","0"));
        add(result,context);
        List<String> app=new ArrayList<>(context); app.addAll(Arrays.asList("uid",Integer.toString(uid))); add(result,app);
        for(String mark:marks) {
            List<String> marked=new ArrayList<>(context); marked.set(marked.size()-1,mark); add(result,marked);
        }
    }
    private static void add(List<String[]> result,List<String> args) {
        if(result.size()<MAX_QUERIES) result.add(args.toArray(new String[0]));
    }
}
