// Bounded-memory allocation summary for Java 25 JFR files.
// java -Xmx256m tools/replay/AllocationScan.java recording.jfr [START_ISO END_ISO]
// Prefer measured phase bounds: startup sampling weights can include earlier allocation.
// Sample weights are statistical estimates; inclusive contexts overlap. No process command lines are printed.
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;

class AllocationScan {
 static final Map<String,Long> threads=new HashMap<>(), sites=new HashMap<>(), contexts=new HashMap<>();
 static void add(Map<String,Long> m,String k,long v){m.merge(k,v,Long::sum);}
 static void print(String title,Map<String,Long> m,int limit){
  System.out.println(title);
  m.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(limit)
   .forEach(e->System.out.printf(Locale.ROOT,"%.3f GiB\t%s%n",e.getValue()/1073741824.0,e.getKey()));
 }
 public static void main(String[] args)throws Exception {
  Instant from=args.length>1?Instant.parse(args[1]):Instant.MIN,to=args.length>2?Instant.parse(args[2]):Instant.MAX;
  long count=0,total=0;List<String> heaps=new ArrayList<>(),gcs=new ArrayList<>();
  try(var input=new RecordingFile(Path.of(args[0]))){while(input.hasMoreEvents()){
   var e=input.readEvent();if(e.getStartTime().isBefore(from)||!e.getStartTime().isBefore(to))continue;String type=e.getEventType().getName();
   if(type.equals("jdk.ObjectAllocationSample")){
    long bytes=e.getLong("weight");count++;total+=bytes;String thread=e.getThread()==null?"unknown":e.getThread().getJavaName();
    String object=e.getClass("objectClass").getName(),site="unknown";boolean lod=false,own=false,atmo=false;
    if(e.getStackTrace()!=null)for(var f:e.getStackTrace().getFrames()){
     var method=f.getMethod();String name=method.getType().getName()+"."+method.getName();
     if(site.equals("unknown")&&(name.startsWith("mcopt.")||name.startsWith("net.minecraft.")||name.startsWith("com.beash.")||name.startsWith("com.ishland.")))site=name;
     lod|=name.startsWith("mcopt.metal.lod.");own|=name.startsWith("mcopt.metal.own.");atmo|=name.startsWith("com.beash.atmospherics.")||name.contains("ambientfog$")||name.startsWith("io.github.mintyleaf.atmosphericsfixes.");
    }
    add(threads,thread,bytes);add(sites,thread+" | "+site+" | "+object,bytes);
    if(lod)add(contexts,"LOD stack (inclusive)",bytes);if(own)add(contexts,"Own renderer stack (inclusive)",bytes);if(atmo)add(contexts,"Atmospherics stack (inclusive)",bytes);
   } else if(type.equals("jdk.GCHeapSummary"))heaps.add(e.getStartTime()+" "+e.getString("when")+" usedMiB="+(e.getLong("heapUsed")/1048576.0));
   else if(type.equals("jdk.GarbageCollection"))gcs.add(e.getStartTime()+" "+e.getString("name")+" "+e.getString("cause")+" durationMs="+(e.getDuration().toNanos()/1e6));
  }}
  System.out.printf(Locale.ROOT,"%d allocation samples, %.3f GiB estimated total%n",count,total/1073741824.0);
  print("THREADS",threads,20);print("SITES",sites,30);print("INCLUSIVE CONTEXTS (overlap)",contexts,10);
  System.out.println("HEAPS");heaps.forEach(System.out::println);System.out.println("COLLECTIONS");gcs.forEach(System.out::println);
 }
}
