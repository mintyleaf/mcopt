// Offline Java25 JFR/frames.csv correlation; retains spans only for the15 largest gaps over50ms.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class HitchScan {
 record Gap(String phase,double elapsed,double ms){}
 record Span(double at,double end,String kind,String thread,List<String> stack,String detail){}
 static double epoch(Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 public static void main(String[] args)throws Exception {
  Path dir=Path.of(args[0]);List<Gap> gaps=new ArrayList<>();Map<String,Double> phaseStarts=new HashMap<>();
  var rows=Files.readAllLines(dir.resolve("frames.csv"));
  for(String line:rows.subList(1,rows.size())){
   var c=line.split(",");double at=Double.parseDouble(c[1]),ms=Double.parseDouble(c[2]);phaseStarts.putIfAbsent(c[0],at);if(ms>50)gaps.add(new Gap(c[0],at,ms));
  }
  double anchor=Double.POSITIVE_INFINITY;
  // Later boundaries avoid the first-frame setup cost between the frame clock sample and JFR marker.begin().
  try(var input=new RecordingFile(dir.resolve("recording.jfr"))){while(input.hasMoreEvents()){
   var e=input.readEvent();if(e.getEventType().getName().equals("mcopt.ReplayPhase")){
    Double at=phaseStarts.get(e.getString("phase"));if(at!=null)anchor=Math.min(anchor,epoch(e.getStartTime())-at);
   }
  }}
  if(!Double.isFinite(anchor))throw new IllegalStateException("Missing anchor");
  gaps.sort(Comparator.comparingDouble(Gap::ms).reversed());
  if(gaps.size()>15)gaps=new ArrayList<>(gaps.subList(0,15));
  List<Span> spans=new ArrayList<>();
  Set<String> kinds=Set.of("jdk.ExecutionSample","jdk.NativeMethodSample","jdk.ThreadPark","jdk.JavaMonitorEnter","jdk.ZAllocationStall","jdk.GCPhasePause","mcopt.MetalOperation","mcopt.LodClientCapture","mcopt.OwnArenaGrowth","mcopt.LodArenaGrowth","mcopt.MetalPipelineCompile","mcopt.ReplayClientWork");
  try(var input=new RecordingFile(dir.resolve("recording.jfr"))){while(input.hasMoreEvents()){
   var e=input.readEvent();String kind=e.getEventType().getName();double at=epoch(e.getStartTime());
   if(!kinds.contains(kind))continue;
   var t=kind.endsWith("Sample")?e.getThread("sampledThread"):e.getThread();String thread=t==null || t.getJavaName()==null?"unknown":t.getJavaName();
   if(kind.endsWith("Sample") && !thread.equals("Render thread"))continue;
   List<String> stack=new ArrayList<>();if(e.getStackTrace()!=null)for(var f:e.getStackTrace().getFrames()){var m=f.getMethod();stack.add(m.getType().getName()+"."+m.getName());}
   String detail=kind.equals("mcopt.ReplayClientWork")?e.getString("operation")+" packets="+e.getInt("packets")+" yielded="+e.getBoolean("yielded")+" cpuMs="+e.getDouble("cpuMs"):kind.equals("mcopt.MetalOperation")?e.getString("operation"):kind.equals("mcopt.LodClientCapture")?e.getString("reason")+" freeze="+e.getDouble("freezeMs"):"";
   if(anchor!=0){double end=epoch(e.getEndTime());boolean near=false;for(var g:gaps)if(at<=anchor+g.elapsed+g.ms/1000 && end>=anchor+g.elapsed){near=true;break;}if(near)spans.add(new Span(at,end,kind,thread,stack,detail));}
  }}
  if(!Double.isFinite(anchor))throw new IllegalStateException("Missing anchor");
  gaps.sort(Comparator.comparingDouble(Gap::ms).reversed());
  for(var g:gaps.subList(0,Math.min(15,gaps.size()))){double start=anchor+g.elapsed,end=start+g.ms/1000;
   System.out.printf(Locale.ROOT,"\nGAP %s +%.6fs %.3fms [%s]\n",g.phase,g.elapsed,g.ms,Instant.ofEpochSecond((long)start,(long)((start%1)*1e9)));
   Map<String,Integer> samples=new LinkedHashMap<>();
   for(var s:spans){if(s.at>end || s.end<start)continue;
    if(s.kind.endsWith("Sample"))samples.merge(String.join(" > ",s.stack.subList(0,Math.min(12,s.stack.size()))),1,Integer::sum);
    else if((s.thread.equals("Render thread") || s.kind.equals("jdk.GCPhasePause")) && (s.end-s.at>=.001 || s.kind.equals("mcopt.ReplayClientWork") || s.kind.equals("jdk.GCPhasePause")))System.out.printf(Locale.ROOT,"  %.3fms..%.3fms %s %s %s\n",(s.at-start)*1000,(s.end-start)*1000,s.kind,s.detail,String.join(" > ",s.stack));
   }
   samples.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(8).forEach(e->System.out.println("  SAMPLE x"+e.getValue()+" "+e.getKey()));
  }
 }
}
