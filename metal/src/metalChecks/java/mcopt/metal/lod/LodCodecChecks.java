package mcopt.metal.lod;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.zip.*;

/** Original compressed format, nested leases, failure recovery, and measured temporary-byte reduction. */
public final class LodCodecChecks {
	private static long read(Path file, boolean reused) throws Exception {
		try (var inflater = new Inflater(); var in = reused ? LodCodecStreams.input(file)
			: new DataInputStream(new BufferedInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file)),inflater,65536),65536))) {
			long sum=0;for(int i=0;i<20000;i++)sum+=in.readInt();return sum;
		}
	}
	public static void main(String[] args) throws Exception {
		Path root=Files.createTempDirectory("mcopt-codec-");Path file=root.resolve("words.z");
		try {
			try(var deflater=new Deflater(Deflater.BEST_SPEED);var out=LodCodecStreams.output(file,deflater)) {for(int i=0;i<20000;i++)out.writeInt(i*31);}
			long expected=read(file,false);if(read(file,true)!=expected)throw new AssertionError("Codec format changed");
			try(var outer=LodCodecStreams.input(file)) {
				if(outer.readInt()!=0)throw new AssertionError();
				if(read(file,true)!=expected)throw new AssertionError("Nested decoder corrupted data");
				for(int i=1;i<20000;i++)if(outer.readInt()!=i*31)throw new AssertionError("Outer unread buffer overwritten");
			}
			try {LodCodecStreams.input(root.resolve("missing"));throw new AssertionError();}catch(IOException correct){}
			Path bad=root.resolve("bad");Files.write(bad,new byte[]{1,2,3});
			try(var in=LodCodecStreams.input(bad)){in.readInt();throw new AssertionError();}catch(IOException correct){}
			var old=LodCodecStreams.input(file);old.close();
			try(var current=LodCodecStreams.input(file)) {old.close();if(read(file,true)!=expected || current.readInt()!=0)throw new AssertionError("Double close returned an active lease");}
			for(int i=0;i<20;i++)read(file,true);
			var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
			long before=bean.getThreadAllocatedBytes(thread);for(int i=0;i<100;i++)read(file,false);long original=bean.getThreadAllocatedBytes(thread)-before;
			before=bean.getThreadAllocatedBytes(thread);for(int i=0;i<100;i++)read(file,true);long reused=bean.getThreadAllocatedBytes(thread)-before;
			if(reused*2>=original)throw new AssertionError("Temporary allocation not reduced: "+original+" -> "+reused);
			System.out.printf("PASS: format/nested reads/failures/double close;100 reads allocated %d -> %d bytes%n",original,reused);
		} finally {try(var files=Files.walk(root)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
	}
}
