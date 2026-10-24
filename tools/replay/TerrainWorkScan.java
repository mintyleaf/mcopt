// Java25 streaming phase CPU/allocation attribution. Args: RUN_DIR. Allocation weights are estimates.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
class TerrainWorkScan {
 record Phase(String name,double from,double to){}
 static double epoch(java.time.Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 static final class Stats {
  int frames,snowSamples,batches;double frameCpu,frameWall,cpuMachine,cpuJvm;int cpuSamples;
  long alloc,snowAlloc;final Map<String,Long> threads=new HashMap<>();final Map<String,Integer> samples=new HashMap<>();
 }
 public static void main(String[]args)throws Exception {
  Path dir=Path.of(args[0]);var phases=new ArrayList<Phase>();var stats=new LinkedHashMap<String,Stats>();double clock=Double.POSITIVE_INFINITY;
  try(var in=new RecordingFile(dir.resolve("recording.jfr"))){while(in.hasMoreEvents()){
   var e=in.readEvent();if(e.getEventType().getName().equals("mcopt.ReplayPhase"))phases.add(new Phase(e.getString("phase"),epoch(e.getStartTime()),epoch(e.getEndTime())));
   if(e.getEventType().getName().equals("mcopt.ReplayTerrainChange"))clock=Math.min(clock,epoch(e.getStartTime())-e.getDouble("actualSeconds"));
  }}
  phases.sort(Comparator.comparingDouble(Phase::from));for(var p:phases){stats.computeIfAbsent(p.name.startsWith("edits")?"edits":p.name,k->new Stats());System.out.printf("BOUND\t%s\t%s\t%s%n",p.name,java.time.Instant.ofEpochMilli((long)(p.from*1000)),java.time.Instant.ofEpochMilli((long)(p.to*1000)));}
  if(Double.isFinite(clock))System.out.printf("WORKLOAD_BOUND\t%s\t%s%n",java.time.Instant.ofEpochMilli((long)((clock+10)*1000)),java.time.Instant.ofEpochMilli((long)((clock+50)*1000)));
  try(var in=new RecordingFile(dir.resolve("recording.jfr"))){while(in.hasMoreEvents()){
   var e=in.readEvent();double at=epoch(e.getStartTime());Phase found=null;for(var p:phases)if(at>=p.from&&at<p.to){found=p;break;}if(found==null)continue;
   // A server batch can precede the next client phase marker by a few milliseconds. Use its independent clock.
   String label=found.name.startsWith("edits")?"edits":found.name;
   if(Double.isFinite(clock))label=at<clock+10?"idle":at<clock+50?"edits":"settle";
   var s=stats.computeIfAbsent(label,k->new Stats());String type=e.getEventType().getName();
   if(type.equals("mcopt.ReplayClientWork")&&e.getString("operation").equals("frame")){s.frames++;s.frameCpu+=e.getDouble("cpuMs");s.frameWall+=e.getDuration().toNanos()/1e6;}
   if(type.equals("jdk.CPULoad")){s.cpuSamples++;s.cpuMachine+=e.getFloat("machineTotal")*100;s.cpuJvm+=(e.getFloat("jvmUser")+e.getFloat("jvmSystem"))*100;}
   if(type.equals("mcopt.ReplayTerrainChange"))s.batches++;
   boolean allocation=type.equals("jdk.ObjectAllocationSample"),sample=type.equals("jdk.ExecutionSample")||type.equals("jdk.NativeMethodSample");
   if(!allocation&&!sample)continue;
   var thread=sample?e.getThread("sampledThread"):e.getThread();String name=thread==null?"unknown":Objects.toString(thread.getJavaName(),"native");boolean snow=false;
   if(e.getStackTrace()!=null)for(var f:e.getStackTrace().getFrames()){String cls=f.getMethod().getType().getName();if(cls.startsWith("mcopt.metal.compat.BetterGrassSnow")||cls.startsWith("dev.ultimatchamp.bettergrass"))snow=true;}
   if(allocation){long bytes=e.getLong("weight");s.alloc+=bytes;s.threads.merge(name,bytes,Long::sum);if(snow)s.snowAlloc+=bytes;}
   if(sample){s.samples.merge(name,1,Integer::sum);if(snow)s.snowSamples++;}
  }}
  System.out.println("phase\tframes\tmeanFrameCpuMs\tmeanFrameWallMs\tmeanMachineCpuPercent\tmeanJvmCpuPercent\testimatedAllocMiB\testimatedSnowAllocMiB\tsnowStackSamples\tbatches");
  for(var entry:stats.entrySet()){
   var s=entry.getValue();System.out.printf(Locale.ROOT,"%s\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%d%n",entry.getKey(),s.frames,s.frameCpu/Math.max(1,s.frames),s.frameWall/Math.max(1,s.frames),s.cpuMachine/Math.max(1,s.cpuSamples),s.cpuJvm/Math.max(1,s.cpuSamples),s.alloc/1048576.0,s.snowAlloc/1048576.0,s.snowSamples,s.batches);
   s.threads.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(12).forEach(e->System.out.printf(Locale.ROOT,"ALLOC\t%s\t%s\t%.3f MiB%n",entry.getKey(),e.getKey(),e.getValue()/1048576.0));
   s.samples.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(12).forEach(e->System.out.printf("CPU_SAMPLES\t%s\t%s\t%d%n",entry.getKey(),e.getKey(),e.getValue()));
  }
 }
}
