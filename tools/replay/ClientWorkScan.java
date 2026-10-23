import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
class ClientWorkScan {
 record Work(long at,String operation,double wall,double cpu,int packets,boolean yielded){}
 record Phase(String name,long from,long to){}
 static long nanos(java.time.Instant at){return at.getEpochSecond()*1_000_000_000L+at.getNano();}
 static double percentile(List<Double> values,double q){values.sort(Double::compare);return values.isEmpty()?0:values.get((int)Math.ceil(values.size()*q)-1);}
 public static void main(String[] args)throws Exception{
  var work=new ArrayList<Work>();var phases=new ArrayList<Phase>();
  try(var in=new RecordingFile(Path.of(args[0]).resolve("recording.jfr"))){while(in.hasMoreEvents()){
   var e=in.readEvent();String type=e.getEventType().getName();
   if(type.equals("mcopt.ReplayPhase"))phases.add(new Phase(e.getString("phase"),nanos(e.getStartTime()),nanos(e.getEndTime())));
   if(type.equals("mcopt.ReplayClientWork"))work.add(new Work(nanos(e.getStartTime()),e.getString("operation"),e.getDuration().toNanos()/1e6,e.getDouble("cpuMs"),e.getInt("packets"),e.getBoolean("yielded")));
  }}
  System.out.println("phase\toperation\tcalls\tpackets\tmaxPackets\tyields\twallTotalMs\tcpuTotalMs\twallP95Ms\twallMaxMs");
  for(var p:phases)for(String operation:List.of("frame","packetDrain","lightUpdates","animateTick","pollEvents")){
   var selected=work.stream().filter(w->w.at>=p.from&&w.at<p.to&&w.operation.equals(operation)).toList();
   var durations=new ArrayList<Double>();for(var w:selected)durations.add(w.wall);
   System.out.printf(Locale.ROOT,"%s\t%s\t%d\t%d\t%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f%n",p.name,operation,selected.size(),selected.stream().mapToInt(Work::packets).sum(),selected.stream().mapToInt(Work::packets).max().orElse(0),selected.stream().filter(Work::yielded).count(),selected.stream().mapToDouble(Work::wall).sum(),selected.stream().mapToDouble(Work::cpu).sum(),percentile(durations,.95),percentile(durations,1));
  }
 }
}
