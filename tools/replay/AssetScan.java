// Java25; replay-only first-use assets and slow window event pumps. Args: RUN_DIR.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class AssetScan {
 record Frame(String phase,double at,double end,double x){}
 static double epoch(Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);var frames=new ArrayList<Frame>();var firsts=new HashMap<String,Double>();
  var rows=Files.readAllLines(root.resolve("frames.csv"));
  for(var row:rows.subList(1,rows.size())){var c=row.split(",");double at=Double.parseDouble(c[1]);frames.add(new Frame(c[0],at,at+Double.parseDouble(c[2])/1000,Double.parseDouble(c[3])));firsts.putIfAbsent(c[0],at);}
  double anchor=Double.POSITIVE_INFINITY;var selected=new ArrayList<RecordedEvent>();
  try(var in=new RecordingFile(root.resolve("recording.jfr"))){while(in.hasMoreEvents()){
   var e=in.readEvent();var type=e.getEventType().getName();
   if(type.equals("mcopt.ReplayPhase")){var at=firsts.get(e.getString("phase"));if(at!=null)anchor=Math.min(anchor,epoch(e.getStartTime())-at);}
   if(type.equals("mcopt.ReplayAsset") || (type.equals("mcopt.ReplayClientWork")&&e.getString("operation").equals("pollEvents")&&e.getDuration().toNanos()>20_000_000))selected.add(e);
  }}
  if(!Double.isFinite(anchor))throw new IllegalStateException("Missing phase anchor");
  selected.sort(Comparator.comparing(RecordedEvent::getStartTime));
  for(var e:selected){double at=epoch(e.getStartTime())-anchor;var frame=frames.stream().filter(f->at>=f.at&&at<=f.end).findFirst().orElse(null);
   System.out.printf(Locale.ROOT,"%s +%.6fs x=%.3f %.3fms ",frame==null?"between":frame.phase,at,frame==null?Double.NaN:frame.x,e.getDuration().toNanos()/1e6);
   if(e.getEventType().getName().equals("mcopt.ReplayAsset")){
    System.out.printf("%s %s impl=%s registered=%s identity=%s cache=%d entries=%d state=%s%n",e.getString("kind"),e.getString("location"),e.getString("implementation"),e.getString("canonical"),e.getBoolean("registeredIdentity"),e.getInt("cache"),e.getInt("entries"),e.getString("state"));
    if(e.hasField("origin") && e.getString("origin")!=null)System.out.println("  CREATED "+e.getString("origin"));
    if(e.getStackTrace()!=null)System.out.println("  "+String.join(" > ",e.getStackTrace().getFrames().stream().limit(20).map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList()));
   } else System.out.printf(Locale.ROOT,"pollEvents cpu=%.3fms%n",e.getDouble("cpuMs"));
  }
 }
}
