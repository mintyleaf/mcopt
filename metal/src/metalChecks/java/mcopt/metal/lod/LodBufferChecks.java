package mcopt.metal.lod;

import java.util.Arrays;
import mcopt.metal.MetalBridge;

/** Real shared Metal buffers, including Java packing -> GPU publication -> CPU readback. */
public final class LodBufferChecks {
	public static void main(String[] args) {
		long ctx = MetalBridge.createHeadlessContext();
		LodClip clip = new LodClip(ctx,256,300);
		LodClip.Snapshot[] staged = {null};
		try {
			clip.recenter(0,0);
			clip.listener = new LodClip.Listener() {
				public void tilePut(int l,int x,int z,boolean refreshed) { }
				public void tileCleared(int l,int slot) { }
				public void tileStaged(LodClip.Snapshot s) { LodClip.release(staged[0]); staged[0]=s.retain(); }
			};
			if (LodPublish.ON) clip.publisher = new LodPublish(ctx);
			int n = LodTile.CELLS;
			int[] g=new int[n], c=new int[n], cr=new int[n], tw=new int[LodClip.surfaceWords()], runs=new int[n], plants=new int[2*n];
			for (int i=0;i<n;i++) {
				g[i]=LodClip.crownGeomWord(81,1,false); c[i]=i|i<<16; cr[i]=LodClip.crownWord(64,0x008800);
				tw[i]=LodClip.TEX_OVERHANG|LodClip.TEX_LIGHT|LodPalette.word(1,2,3);
				if (LodConfig.LIGHTING) tw[n+i]=Integer.rotateLeft(0xF00123AB,i&31);
			}
			if (!clip.put(0,-1,0,g,c,cr,tw,runs,plants)) throw new AssertionError("Initial tile rejected");
			int[] rg=new int[n], rc=new int[n], rcr=new int[n], rtw=new int[tw.length], rruns=new int[n], rplants=new int[2*n];
			clip.read(0,-1,0,rg,rc,rcr,rtw,rruns,rplants);
			if (!Arrays.equals(tw,rtw) || !Arrays.equals(cr,rcr)) throw new AssertionError("Direct surface write/read lost data");
			int x=-1,z=5,index=z*64+63,light=0x0327EFF0;
			clip.putCell(0,x,z,g[index],c[index],cr[index],tw[index],0,0,0,light);
			clip.refresh(0,-1,0);
			clip.flushStaged();
			if (LodClip.PAIRS) {
				if (staged[0]==null) throw new AssertionError("No staged surface update");
				if (clip.publisher!=null) clip.publisher.begin(1);
				if (!clip.publish(staged[0])) throw new AssertionError("Staged surface rejected");
				if (clip.publisher!=null) {
					long enc=MetalBridge.createHeadlessEncoder(ctx);
					clip.publisher.encode(enc,clip);
					MetalBridge.commitAndWait(enc);
				}
			}
			clip.read(0,-1,0,rg,rc,rcr,rtw,rruns,rplants);
			if (LodConfig.LIGHTING) tw[n+index]=light;
			if (!Arrays.equals(tw,rtw) || !Arrays.equals(g,rg) || !Arrays.equals(c,rc) || !Arrays.equals(cr,rcr))
				throw new AssertionError("Publication changed geometry, flags or light words");
			if (LodConfig.LIGHTING && clip.cellWords(x,z)[7]!=light) throw new AssertionError("Surface light offset mismatch");
			for(int repeat=0;repeat<1000;repeat++) if(clip.putCell(0,x,z,g[index],c[index],cr[index],tw[index],0,0,0,light))
				throw new AssertionError("Identical captures still change the clipmap and trigger remeshing");
			if(LodPublish.ON) {
				clip.publisher.begin(2);
				int first=LodClip.crownGeomWord(95,3,false);
				clip.putCell(0,x,z,first,c[index],cr[index],tw[index],0,0,0,light);clip.refresh(0,-1,0);clip.flushStaged();
				clip.publish(staged[0]); // Deliberately leave the GPU copy unexecuted, as with frames in flight.
				clip.putCell(0,x-1,z,g[index-1],c[index-1]+1,cr[index-1],tw[index-1],0,0,0,light);clip.refresh(0,-1,0);clip.flushStaged();
				if(staged[0].g[index]!=first) throw new AssertionError("A later capture reads old GPU words and loses the preceding publication");
				clip.publish(staged[0]);
				try {
					var count=LodPublish.class.getDeclaredField("count");count.setAccessible(true);
					if(count.getInt(clip.publisher)!=1) throw new AssertionError("Two GPU workgroups can write different versions of one tile concurrently");
				} catch(ReflectiveOperationException e) {throw new AssertionError(e);}
				clip.put(0,0,0,g,c,cr,tw,runs,plants);
				LodMesh mesh=new LodMesh(ctx,clip);
				long scratch=org.lwjgl.system.MemoryUtil.nmemCalloc(5L*128*128,4);
				try {
					var copy=LodMesh.class.getDeclaredMethod("copyRegion",long.class,int.class,int.class,int.class,LodClip.Snapshot.class,boolean.class,boolean.class);
					copy.setAccessible(true);copy.invoke(mesh,scratch,0,0,0,null,true,clip.plants);
					if(org.lwjgl.system.MemoryUtil.memGetInt(scratch+(z*128+127)*4L)!=first) throw new AssertionError("Neighbor meshing still sees unpublished old GPU geometry");
				} catch(ReflectiveOperationException e) {throw new AssertionError(e);}
				finally {org.lwjgl.system.MemoryUtil.nmemFree(scratch);mesh.close();for(long resource:mesh.buffers()) LodNative.release(resource);}
				long enc=MetalBridge.createHeadlessEncoder(ctx);clip.publisher.encode(enc,clip);MetalBridge.commitAndWait(enc);
				clip.retireGpu(5);
				if(clip.pendingGpu(0,-1,0)==null) throw new AssertionError("CPU publication retired before four frames elapsed");
				clip.retireGpu(6);
				clip.read(0,-1,0,rg,rc,rcr,rtw,rruns,rplants);
				if(rg[index]!=first || rc[index-1]!=c[index-1]+1 || clip.pendingGpu(0,-1,0)!=null)
					throw new AssertionError("Coalesced GPU copy lost an update or retained its CPU snapshot after retirement");
			}
			System.out.println("PASS: actual Metal clipmap surface data survives direct writes and " + System.getProperty("mcopt.lod.publish","direct") + " publication");
		} finally { LodClip.release(staged[0]); clip.free(); }
	}
}
