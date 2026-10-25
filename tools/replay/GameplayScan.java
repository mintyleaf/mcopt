// Streaming named gameplay/asset/class-load spans. Java25, -Xmx128m; RECORDING OUT_TSV.
import java.nio.file.*;
import java.io.*;
import java.util.*;
import jdk.jfr.consumer.*;

class GameplayScan {
 static String name(RecordedThread t){return t==null?"unknown":Objects.toString(t.getJavaName(),t.getOSName());}
 static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ');}
 public static void main(String[] args)throws Exception {
  try(var in=new RecordingFile(Path.of(args[0]));var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))){
   out.println("startEpoch\tdurationMs\tkind\tthread\toperation\tsubject\tstack");
   while(in.hasMoreEvents()){
    var e=in.readEvent();String kind=e.getEventType().getName(),thread=name(e.getThread()),operation="",subject="";
    double ms=e.getDuration().toNanos()/1e6;
    switch(kind){
     case "mcopt.GameplayWork" -> {operation=e.getString("operation");subject=e.getString("subject");}
     case "mcopt.GameplayAction" -> operation=e.getString("operation");
     case "mcopt.ReplayGameplayEffect" -> {operation=e.getString("action");subject="cycle="+e.getInt("cycle")+" index="+e.getInt("index")+" measuredSeconds="+e.getDouble("actualSeconds");}
     case "mcopt.ReplayPhase" -> operation=e.getString("phase");
     case "mcopt.ReplayFirstStarDraw" -> {operation="colored-star-pipeline";subject="measuredSeconds="+e.getDouble("measuredSeconds");}
     case "jdk.ClassLoad" -> subject=e.getClass("loadedClass").getName();
     case "jdk.ThreadPark","jdk.JavaMonitorEnter" -> {if(!thread.equals("Render thread")||ms<5)continue;}
     case "jdk.ExecuteVMOperation" -> {if(ms<1)continue;operation=e.getString("operation");subject="caller="+name(e.getThread("caller"))+" safepoint="+e.getBoolean("safepoint");}
     case "jdk.SafepointBegin","jdk.GCPhasePause" -> {if(ms<1)continue;}
     case "mcopt.MetalPipelineCompile" -> subject=e.getString("pipeline");
     default -> {continue;}
    }
    var at=e.getStartTime();String stack=e.getStackTrace()==null?"":String.join(" > ",e.getStackTrace().getFrames().stream().limit(16).map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList());
    out.printf(Locale.ROOT,"%.9f\t%.6f\t%s\t%s\t%s\t%s\t%s%n",at.getEpochSecond()+at.getNano()/1e9,ms,kind,clean(thread),clean(operation),clean(subject),clean(stack));
   }
  }
 }
}
