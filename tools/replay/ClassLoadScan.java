// Java25 offline class-load attribution. Args: RUN_DIR [PHASE X_MIN X_MAX]. Inclusive/nested durations must not be added.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class ClassLoadScan {
 record Frame(String phase,double at,double ms,double x){}
 record Load(double at,double end,String name,String thread,List<String> stack){}
 static double epoch(Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 public static void main(String[] args)throws Exception {
  String phase=args.length>1?args[1]:"cold";double minX=args.length>2?Double.parseDouble(args[2]):-195,maxX=args.length>3?Double.parseDouble(args[3]):-130;
  Path root=Path.of(args[0]);Map<String,Double> firsts=new HashMap<>();Frame target=null;
  for(String row:Files.readAllLines(root.resolve("frames.csv")).subList(1,Files.readAllLines(root.resolve("frames.csv")).size())){
   String[] c=row.split(",");double at=Double.parseDouble(c[1]),ms=Double.parseDouble(c[2]),x=Double.parseDouble(c[3]);firsts.putIfAbsent(c[0],at);
   if(c[0].equals(phase)&&x>minX&&x<maxX&&(target==null||ms>target.ms))target=new Frame(c[0],at,ms,x);
  }
  if(target==null)throw new IllegalArgumentException("No first-chest frame");
  double anchor=Double.POSITIVE_INFINITY;var loads=new ArrayList<Load>();
  try(var in=new RecordingFile(root.resolve("recording.jfr"))){while(in.hasMoreEvents()){
   var e=in.readEvent();String type=e.getEventType().getName();
   if(type.equals("mcopt.ReplayPhase")){Double at=firsts.get(e.getString("phase"));if(at!=null)anchor=Math.min(anchor,epoch(e.getStartTime())-at);}
   if(type.equals("jdk.ClassLoad")){
    var stack=new ArrayList<String>();if(e.getStackTrace()!=null)for(var f:e.getStackTrace().getFrames()){var m=f.getMethod();stack.add(m.getType().getName()+"."+m.getName());}
    loads.add(new Load(epoch(e.getStartTime()),epoch(e.getEndTime()),e.getClass("loadedClass").getName(),e.getThread()==null?"unknown":e.getThread().getJavaName(),stack));
   }
  }}
  double start=anchor+target.at,end=start+target.ms/1000;
  System.out.printf(Locale.ROOT,"FIRST CHEST +%.6fs x=%.3f %.3fms; overlapping class loads (inclusive/nested, do not sum):%n",target.at,target.x,target.ms);
  var selected=loads.stream().filter(l->l.at<=end&&l.end>=start).sorted(Comparator.comparingDouble((Load l)->l.end-l.at).reversed()).toList();
  for(var l:selected)System.out.printf(Locale.ROOT,"%.3fms\t%s\t%s\t%s%n",(l.end-l.at)*1000,l.thread,l.name,String.join(" > ",l.stack.subList(0,Math.min(10,l.stack.size()))));
  Files.write(root.resolve("first-chest-classes.txt"),selected.stream().filter(l->l.thread!=null&&l.thread.equals("Render thread")&&!l.name.contains("$$Lambda")&&!l.name.contains("/")&&!l.name.contains(".0x")&&!l.name.startsWith("[")).map(Load::name).distinct().sorted().toList());
 }
}
