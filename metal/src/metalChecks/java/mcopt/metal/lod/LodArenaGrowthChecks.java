package mcopt.metal.lod;

import java.nio.file.*;
import java.util.Arrays;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryUtil;

/** Tiny arenas force repeated migrations while tiles edit, evict and reinstall. Compare live bytes with an independent remesh. */
public final class LodArenaGrowthChecks {
	public static void main(String[] args) throws Exception {
		long ctx=MetalBridge.createHeadlessContext();var clip=new LodClip(ctx,256,300,true);var mesh=new LodMesh(ctx,clip);
		Path file=Files.createTempFile("mcopt-lod-growth-",".jfr");
		try(var recording=new Recording()) {
			recording.enable("mcopt.LodArenaGrowth");recording.start();
			clip.recenter(0,0);if(LodPublish.ON)clip.publisher=new LodPublish(ctx);
			int[] g=new int[4096],c=new int[4096];
			for(int i=0;i<g.length;i++)g[i]=LodClip.geomWord(64+((i+(i>>6))&1)*24,false);
			for(int z=0;z<2;z++)for(int x=0;x<2;x++)put(clip,x,z,g,c);
			long frame=0,deadline=System.nanoTime()+20_000_000_000L;boolean edited=false,moved=false;
			while(true) {
				step(ctx,clip,mesh,frame++);
				if(mesh.growing() && mesh.installed>0 && !edited) {
					clip.putCell(0,2,2,LodClip.geomWord(117,false),0,0,0,0,0,0,0);clip.refresh(0,0,0);edited=true;
				} else if(mesh.growing() && edited && !moved) {
					clip.recenter(4*64,0);
					for(int z=0;z<2;z++)for(int x=3;x<5;x++)put(clip,x,z,g,c);
					moved=true;
				}
				boolean installed=true;
				for(int z=0;z<2;z++)for(int x=moved?3:0;x<(moved?5:2);x++)installed&=mesh.installed(0,x,z);
				if(installed && moved && !mesh.growing() && mesh.pending()==0 && frame>20)break;
				if(System.nanoTime()>deadline)throw new AssertionError("Migration failed to publish latest resident tiles; frame="+frame+", pending="+mesh.pending());
				Thread.sleep(1);
			}
			for(int i=0;i<8;i++){step(ctx,clip,mesh,frame++);Thread.sleep(1);}
			for(int z=0;z<2;z++)for(int x=3;x<5;x++)compare(clip,mesh,frame,x,z);
			recording.stop();recording.dump(file);
			boolean prepare=false,publish=false;long copied=0,capacity=0;
			for(var e:RecordingFile.readAllEvents(file))if(e.getEventType().getName().equals("mcopt.LodArenaGrowth")) {
				String phase=e.getString("phase");
				if(phase.equals("prepare")) {prepare=true;if(!e.getThread().getJavaName().equals("mcopt-lod-arena"))throw new AssertionError("Render thread allocated growing arena");}
				if(phase.equals("copy") && e.getLong("copiedBytes")>1024)throw new AssertionError("Copy byte budget exceeded");
				if(phase.equals("publish")){publish=true;copied+=e.getLong("copiedBytes");capacity+=e.getLong("oldBytes");}
			}
			if(!prepare || !publish || !edited || !moved || copied>=capacity)throw new AssertionError("Live-only migration/edit/eviction not exercised");
			System.out.println("PASS: repeated tiny-arena growth prepares off-thread, copies <=1024 bytes/step, survives edits/eviction, and matches four independent native remeshes; live bytes "+copied+" vs old capacity "+capacity);
		} finally {
			mesh.close();clip.closeSnapshots();for(long b:mesh.buffers())LodNative.release(b);clip.free();Files.deleteIfExists(file);
		}
	}
	private static void put(LodClip clip,int x,int z,int[] g,int[] c) {
		clip.put(0,x,z,g,c,new int[4096],new int[LodClip.surfaceWords()],new int[4096],new int[8192]);
	}
	private static void step(long ctx,LodClip clip,LodMesh mesh,long frame) {
		if(clip.publisher!=null)clip.publisher.begin(frame);clip.retireGpu(frame);mesh.integrate(frame);
		if(clip.publisher!=null){long enc=MetalBridge.createHeadlessEncoder(ctx);clip.publisher.encode(enc,clip);MetalBridge.commitAndWait(enc);}
	}
	private static void compare(LodClip clip,LodMesh mesh,long frame,int x,int z) {
		long header=MemoryUtil.nmemAlloc(LodMesh.HDR_WORDS*4L),quads=MemoryUtil.nmemAlloc(131072*8L);
		try {
			int neighbors=(clip.resident(0,x+1,z)?1:0)|(clip.resident(0,x-1,z)?2:0)|(clip.resident(0,x,z+1)?4:0)|(clip.resident(0,x,z-1)?8:0);
			int n=LodNative.meshTile(clip.geom,clip.crown,clip.crown+clip.runsOffset()*4,clip.tex+clip.levelWords*4,clip.tex+clip.levelWords*8,
				clip.logN,0,x,z,neighbors,LodMesh.BLOCK,header,quads,131072);
			long table=LodNative.contents(mesh.table(frame));long at=Integer.toUnsignedLong(MemoryUtil.memGetInt(table+clip.slot(x,z)*16L))-1;
			long actual=LodNative.contents(mesh.arena())+at*4;
			if(n<0 || MemoryUtil.memByteBuffer(header,LodMesh.HDR_WORDS*4).mismatch(MemoryUtil.memByteBuffer(actual,LodMesh.HDR_WORDS*4))>=0
				|| MemoryUtil.memByteBuffer(quads,n*8).mismatch(MemoryUtil.memByteBuffer(actual+LodMesh.HDR_WORDS*4L,n*8))>=0)
				throw new AssertionError("Migrated mesh differs from current native reference at "+x+","+z);
		}finally{MemoryUtil.nmemFree(header);MemoryUtil.nmemFree(quads);}
	}
}
