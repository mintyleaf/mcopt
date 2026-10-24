// Java25; stream VM coordination spans and replay phase anchors for profiler-interference audits.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
class VmOperationScan {
 static double epoch(java.time.Instant t){return t.getEpochSecond()+t.getNano()/1e9;}
 static String name(RecordedThread t){return t==null?"unknown":Objects.toString(t.getJavaName(),Objects.toString(t.getOSName(),"native"));}
 public static void main(String[]args)throws Exception {
  System.out.println("kind\tstartEpoch\tdurationMs\toperation\tcaller\tsafepoint");
  try(var in=new RecordingFile(Path.of(args[0]))){while(in.hasMoreEvents()){
   var e=in.readEvent();String type=e.getEventType().getName();
   if(type.equals("mcopt.ReplayPhase"))System.out.printf(Locale.ROOT,"phase\t%.9f\t%.6f\t%s\t-\tfalse%n",epoch(e.getStartTime()),e.getDuration().toNanos()/1e6,e.getString("phase"));
   if(type.equals("jdk.ExecuteVMOperation"))System.out.printf(Locale.ROOT,"operation\t%.9f\t%.6f\t%s\t%s\t%s%n",epoch(e.getStartTime()),e.getDuration().toNanos()/1e6,e.getString("operation"),name(e.getThread("caller")),e.getBoolean("safepoint"));
   if(type.equals("jdk.SafepointBegin"))System.out.printf(Locale.ROOT,"reachingSafepoint\t%.9f\t%.6f\t%d\t%s\ttrue%n",epoch(e.getStartTime()),e.getDuration().toNanos()/1e6,e.getLong("safepointId"),name(e.getThread()));
  }}
 }
}
