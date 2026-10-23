// Streaming Java25 analysis for long normal-game JFRs. No full-file JSON expansion.
// java -Xmx256m SessionScan.java RECORDING OUT START_EPOCH END_EPOCH
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.io.*;
import java.util.*;

class SessionScan {
 record Gap(long submit,double from,double to){double ms(){return (to-from)*1000;}}
 record Gpu(double at,double ms){}
 static final class Values {
  final List<Double> v=new ArrayList<>();
  void add(double d){if(Double.isFinite(d))v.add(d);}
  String report(){if(v.isEmpty())return "count=0";v.sort(Double::compare);return String.format(Locale.ROOT,"n=%d mean=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f",v.size(),v.stream().mapToDouble(x->x).average().orElse(0),q(.5),q(.95),q(.99),q(1));}
  double q(double q){return v.get(Math.max(0,(int)Math.ceil(v.size()*q)-1));}
 }
 static final class Bin {
  final Values cadence=new Values(),gpu=new Values(),machine=new Values(),jvm=new Values();
  int stalls;double stallMs;
 }
 static final class Detail {
  final Gap gap;final Map<String,Integer> samples=new HashMap<>();final List<String> spans=new ArrayList<>();
  Detail(Gap g){gap=g;}
 }
 static double epoch(Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 static String name(RecordedThread t){return t==null?"unknown":t.getJavaName()!=null?t.getJavaName():Objects.toString(t.getOSName(),"native");}
 static String thread(RecordedEvent e){return name(e.getThread());}
 static String stack(RecordedEvent e,int n){if(e.getStackTrace()==null)return "";return String.join(" > ",e.getStackTrace().getFrames().stream().limit(n).map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList());}
 static <N extends Number> void top(PrintWriter out,String label,Map<String,N> values,int limit){out.println(label);values.entrySet().stream().sorted((a,b)->Double.compare(b.getValue().doubleValue(),a.getValue().doubleValue())).limit(limit).forEach(e->out.println("  "+e.getValue()+"\t"+e.getKey()));}
 public static void main(String[] args)throws Exception {
  Path file=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);double start=Double.parseDouble(args[2]),end=Double.parseDouble(args[3]);
  var counts=new TreeMap<String,Long>();var operations=new TreeMap<String,Values>();var threads=new HashMap<String,Integer>();var stacks=new HashMap<String,Integer>();var inclusive=new HashMap<String,Integer>();
  var threadCpu=new TreeMap<String,Values>();var memory=new TreeMap<String,Values>();var cpuMachine=new Values();var cpuJvm=new Values();var pauses=new Values();var stalls=new TreeMap<String,Values>();var gpuStats=new Values();var presentationStats=new Values();
  var commits=new TreeMap<Long,Double>();var gpuBySubmit=new HashMap<Long,Gpu>();var presented=new TreeSet<Double>();var bins=new TreeMap<Integer,Bin>();var special=new ArrayList<String>();
  try(var in=new RecordingFile(file)){while(in.hasMoreEvents()){
   var e=in.readEvent();double at=epoch(e.getStartTime());if(at<start||at>end)continue;String kind=e.getEventType().getName();counts.merge(kind,1L,Long::sum);double ms=e.getDuration().toNanos()/1e6;
   int minute=(int)((at-start)/60);var bin=bins.computeIfAbsent(minute,k->new Bin());
   switch(kind){
    case "jdk.ExecutionSample","jdk.NativeMethodSample" -> {
     var t=e.getThread("sampledThread");String name=name(t);threads.merge(name,1,Integer::sum);
     String key=name+" | "+stack(e,8);
     if(stacks.size()<50000||stacks.containsKey(key))stacks.merge(key,1,Integer::sum);
     if(e.getStackTrace()!=null&&name.equals("Render thread")){
      var seen=new HashSet<String>();for(var f:e.getStackTrace().getFrames()){var m=f.getMethod();String s=m.getType().getName()+"."+m.getName();if(seen.add(s))inclusive.merge(s,1,Integer::sum);}
     }
    }
    case "jdk.CPULoad" -> {double machine=e.getFloat("machineTotal")*100,jvm=(e.getFloat("jvmUser")+e.getFloat("jvmSystem"))*100;cpuMachine.add(machine);cpuJvm.add(jvm);bin.machine.add(machine);bin.jvm.add(jvm);}
    case "jdk.ThreadCPULoad" -> threadCpu.computeIfAbsent(thread(e),k->new Values()).add((e.getFloat("user")+e.getFloat("system"))*100);
    case "jdk.GCPhasePause" -> pauses.add(ms);
    case "jdk.ResidentSetSize" -> memory.computeIfAbsent("JVM resident MiB",k->new Values()).add(e.getLong("size")/1048576.0);
    case "jdk.NativeMemoryUsageTotal" -> memory.computeIfAbsent("NMT committed MiB (not physical residency)",k->new Values()).add(e.getLong("committed")/1048576.0);
    case "jdk.SystemGC" -> {if(special.size()<500)special.add(String.format(Locale.ROOT,"+%.3fs explicitGC %.3fms thread=%s concurrent=%s %s",at-start,ms,thread(e),e.getBoolean("invokedConcurrent"),stack(e,12)));}
    case "jdk.ZAllocationStall" -> {stalls.computeIfAbsent(thread(e),k->new Values()).add(ms);bin.stalls++;bin.stallMs+=ms;if(special.size()<500)special.add(String.format(Locale.ROOT,"+%.3fs allocationStall %.3fms %s %s",at-start,ms,thread(e),stack(e,8)));}
    case "mcopt.MetalOperation" -> {String op=e.getString("operation");operations.computeIfAbsent(op,k->new Values()).add(ms);if(op.equals("commit")&&e.getLong("submit")>=0)commits.put(e.getLong("submit"),at);}
    case "mcopt.MetalCommandBuffer" -> {if(e.getString("kind").equals("main")){double gs=e.getDouble("gpuStart"),ge=e.getDouble("gpuEnd");if(gs>0&&ge>=gs){double g=(ge-gs)*1000;gpuBySubmit.put(e.getLong("submit"),new Gpu(at,g));gpuStats.add(g);bin.gpu.add(g);}}}
    case "mcopt.MetalPresentation" -> {double p=e.getDouble("presentedAt");if(p>0&&!e.getBoolean("missing"))presented.add(p);}
    case "mcopt.MetalPipelineCompile" -> {if(special.size()<500)special.add(String.format(Locale.ROOT,"+%.3fs pipeline %.3fms %s",at-start,ms,e.getString("pipeline")));}
    case "mcopt.OwnArenaGrowth","mcopt.LodArenaGrowth" -> {if(ms>10&&special.size()<500)special.add(String.format(Locale.ROOT,"+%.3fs %s %.3fms",at-start,kind,ms));}
   }
  }}
  Double lastPresentation=null;for(var p:presented){if(lastPresentation!=null)presentationStats.add((p-lastPresentation)*1000);lastPresentation=p;}
  var gaps=new ArrayList<Gap>();Long lastSubmit=null;Double lastAt=null;
  try(var csv=new PrintWriter(Files.newBufferedWriter(output.resolve("frame-times.csv")))){
   csv.println("submit,startEpoch,elapsedSeconds,intervalMs,submittedGpuSpanMs");
   for(var entry:commits.entrySet()){
    if(lastSubmit!=null&&entry.getKey()==lastSubmit+1){var g=new Gap(entry.getKey(),lastAt,entry.getValue());gaps.add(g);bins.computeIfAbsent((int)((g.from-start)/60),k->new Bin()).cadence.add(g.ms());var gpu=gpuBySubmit.get(entry.getKey());csv.printf(Locale.ROOT,"%d,%.9f,%.6f,%.6f,%s%n",g.submit,g.from,g.from-start,g.ms(),gpu==null?"":Double.toString(gpu.ms));}
    lastSubmit=entry.getKey();lastAt=entry.getValue();
   }
  }
  var selected=new ArrayList<Gap>();gaps.stream().filter(g->g.ms()>=100).sorted(Comparator.comparingDouble(Gap::ms).reversed()).limit(20).forEach(selected::add);
  gaps.stream().filter(g->g.ms()>25&&g.ms()<100).sorted(Comparator.comparingDouble(Gap::ms).reversed()).limit(20).forEach(selected::add);selected.sort(Comparator.comparingDouble(Gap::from));var details=selected.stream().map(Detail::new).toList();
  Set<String> spanKinds=Set.of("mcopt.MetalOperation","mcopt.LodClientCapture","mcopt.MetalPipelineCompile","mcopt.OwnArenaGrowth","mcopt.LodArenaGrowth","jdk.ThreadPark","jdk.JavaMonitorEnter","jdk.ZAllocationStall","jdk.GCPhasePause","jdk.SystemGC","jdk.FileRead");
  try(var in=new RecordingFile(file)){while(in.hasMoreEvents()){
   var e=in.readEvent();String kind=e.getEventType().getName();boolean sample=kind.equals("jdk.ExecutionSample")||kind.equals("jdk.NativeMethodSample");if(!sample&&!spanKinds.contains(kind))continue;
   double at=epoch(e.getStartTime()),to=epoch(e.getEndTime());if(to<start||at>end)continue;String name=sample?name(e.getThread("sampledThread")):thread(e);double ms=e.getDuration().toNanos()/1e6;
   if(sample&&!name.equals("Render thread"))continue;if(!sample&&!name.equals("Render thread")&&!kind.equals("jdk.GCPhasePause")&&!kind.equals("mcopt.MetalPipelineCompile"))continue;if(!sample&&ms<.5)continue;
   for(var d:details){if(d.gap.from>to)break;if(d.gap.to<at)continue;
    if(sample)d.samples.merge(stack(e,12),1,Integer::sum);
    else if(d.spans.size()<35){String extra=kind.equals("mcopt.MetalOperation")?e.getString("operation"):kind.equals("mcopt.MetalPipelineCompile")?e.getString("pipeline"):"";d.spans.add(String.format(Locale.ROOT,"%.3f..%.3fms %s %s %s",(at-d.gap.from)*1000,(to-d.gap.from)*1000,kind,extra,stack(e,8)));}
   }
  }}
  try(var out=new PrintWriter(Files.newBufferedWriter(output.resolve("summary.txt")))){
   out.printf(Locale.ROOT,"Window %s .. %s (%.1fs); submit cadence is not scanout FPS.%n",Instant.ofEpochMilli((long)(start*1000)),Instant.ofEpochMilli((long)(end*1000)),end-start);
   var cadence=new Values();for(var g:gaps)cadence.add(g.ms());out.println("Main submit interval ms: "+cadence.report());for(double limit:new double[]{18,20,25,33.4,50,100,250})out.println("Intervals >"+limit+"ms: "+gaps.stream().filter(g->g.ms()>limit).count());
   out.println("Presented intervals ms: "+presentationStats.report());out.println("Main GPU span ms (dependencies can overlap; not additive GPU busy): "+gpuStats.report());out.println("Machine CPU percent: "+cpuMachine.report());out.println("JVM CPU percent (machine normalized): "+cpuJvm.report());out.println("GC pauses ms: "+pauses.report());
   out.println("Memory snapshots (different accounting, do not add):");memory.forEach((k,v)->out.println(k+" "+v.report()));
   out.println("Allocation stalls by thread ms:");stalls.forEach((k,v)->out.println(k+" "+v.report()));out.println("Operations ms:");operations.forEach((k,v)->out.println(k+" "+v.report()));
   out.println("Minute bins (relative to analysis start):");bins.forEach((k,b)->out.printf(Locale.ROOT,"%d cadence[%s] gpu[%s] machine[%s] jvm[%s] stalls=%d/%.1fms%n",k,b.cadence.report(),b.gpu.report(),b.machine.report(),b.jvm.report(),b.stalls,b.stallMs));
   top(out,"CPU samples by thread:",threads,35);top(out,"Top sampled stacks (statistical counts, 8 frames):",stacks,50);top(out,"Render inclusive frames (counts overlap):",inclusive,45);
   out.println("Thread CPU percent as reported by JFR:");threadCpu.entrySet().stream().sorted((a,b)->Double.compare(b.getValue().v.stream().mapToDouble(x->x).average().orElse(0),a.getValue().v.stream().mapToDouble(x->x).average().orElse(0))).limit(35).forEach(e->out.println(e.getKey()+" "+e.getValue().report()));
   out.println("Special events (first500):");special.forEach(out::println);out.println("Event counts:");counts.forEach((k,v)->out.println(k+" "+v));
  }
  try(var out=new PrintWriter(Files.newBufferedWriter(output.resolve("hitches.txt")))){
   out.println("20 largest >=100ms gaps and20 largest25..100ms gaps. Nested spans are not additive; absent samples do not establish idleness.");
   for(var d:details){out.printf(Locale.ROOT,"%n+%.3fs %s submit=%d gap=%.3fms%n",d.gap.from-start,Instant.ofEpochMilli((long)(d.gap.from*1000)),d.gap.submit,d.gap.ms());d.spans.forEach(out::println);top(out,"Samples:",d.samples,8);}
  }
  System.out.println("Wrote bounded-memory session analysis: "+output);
 }
}
