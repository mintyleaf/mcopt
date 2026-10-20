package mcopt.metal.lod;

import java.util.Arrays;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryUtil;

public final class LodShadingChecks {
	public static void main(String[] args) throws Exception {
		long ctx=MetalBridge.createHeadlessContext();classification();
		var clip=new LodClip(ctx,256,300,true);var mesh=new LodMesh(ctx,clip);
		try {
			clip.recenter(0,0);if(LodPublish.ON)clip.publisher=new LodPublish(ctx);
			int[] g=new int[4096],cr=new int[4096];Arrays.fill(g,LodClip.geomWord(64,false));
			Arrays.fill(cr,LodClip.belowWord(0x123456));
			for(int x=0;x<2;x++)clip.put(0,x,0,g,new int[4096],cr,new int[LodClip.surfaceWords()],new int[4096],new int[8192]);
			long frame=settle(ctx,clip,mesh,0),initial=mesh.meshed.get();
			for(int update=1;update<=50;update++) {
				// Includes an edge column: tint, material and light must not remesh its resident neighbor.
				for(int x:new int[]{2,63})clip.putCell(0,x,2,g[0],update,LodClip.belowWord(0x123456+update),update,0,0,0,update);
				clip.refresh(0,0,0);frame=settle(ctx,clip,mesh,frame);
			}
			if(mesh.meshed.get()!=initial || mesh.shadingPublishes<50)throw new AssertionError("Shading enqueued native geometry or neighbors");
			int[] words=clip.cellWords(63,2);
			if(words[1]!=50 || words[4]!=50 || LodConfig.LIGHTING && words[7]!=50)throw new AssertionError("Fast path lost shaded planes");
			clip.putCell(0,2,2,LodClip.geomWord(90,false),50,0,50,0,0,0,50);clip.refresh(0,0,0);
			frame=settle(ctx,clip,mesh,frame);long interior=mesh.meshed.get();
			if(interior!=initial+1)throw new AssertionError("Interior geometry must rebuild only itself: "+(interior-initial));
			clip.putCell(0,63,2,LodClip.geomWord(91,false),50,0,50,0,0,0,50);clip.refresh(0,0,0);
			frame=settle(ctx,clip,mesh,frame);
			if(mesh.meshed.get()<interior+2)throw new AssertionError("Changed border did not rebuild both tiles");
			clip.putCell(0,2,2,LodClip.geomWord(90,false)|LodClip.plantBits(1),50,0,50,0,1,0,50);clip.refresh(0,0,0);
			frame=settle(ctx,clip,mesh,frame);
			if(mesh.meshed.get()<=interior+2)throw new AssertionError("Plant appearance bypassed geometry");
			System.out.println("PASS: 50 light/color/material edge updates perform zero native remeshes; interior, border and plant changes still rebuild required geometry ("+System.getProperty("mcopt.lod.publish")+")");
		}finally{mesh.close();clip.closeSnapshots();if(clip.snapshotPool.borrowedWords()!=0)throw new AssertionError("Shading leaked snapshot leases");for(long b:mesh.buffers())LodNative.release(b);clip.free();}
	}
	private static long settle(long ctx,LodClip clip,LodMesh mesh,long frame) throws Exception {
		long deadline=System.nanoTime()+5_000_000_000L;int quiet=0;
		while(quiet<12) {
			if(clip.publisher!=null)clip.publisher.begin(frame);clip.retireGpu(frame);mesh.integrate(frame++);
			if(clip.publisher!=null){long enc=MetalBridge.createHeadlessEncoder(ctx);clip.publisher.encode(enc,clip);MetalBridge.commitAndWait(enc);}
			quiet=mesh.pending()==0 && !mesh.growing()?quiet+1:0;
			if(System.nanoTime()>deadline)throw new AssertionError("Mesh did not settle");Thread.sleep(1);
		}
		return frame;
	}
	private static void classification() {
		long planes=MemoryUtil.nmemCalloc(5L*128*128,4),header=MemoryUtil.nmemAlloc(LodMesh.HDR_WORDS*4L),quads=MemoryUtil.nmemAlloc(131072*8L);
		try {
			long span=128L*128*4;for(int i=0;i<128*128;i++)MemoryUtil.memPutInt(planes+i*4L,LodClip.geomWord(64,false));
			int i=2*128+2;long[] at={planes+i*4L,planes+span+i*4L,planes+2*span+i*4L,planes+3*span+i*4L,planes+4*span+i*4L};
			int[][] cases={{LodClip.geomWord(64,false),0,0,0,0},
				{LodClip.crownGeomWord(100,8,false),LodClip.crownWord(64,0x123456),0,0,0},
				{LodClip.geomWord(64,false)|LodClip.plantBits(2),0,0,1,0x01230000}};
			int checked=0;
			for(int[] base:cases) {
				for(int p=0;p<5;p++)MemoryUtil.memPutInt(at[p],base[p]);
				byte[] reference=remesh(planes,span,header,quads);
				for(int p=0;p<5;p++)for(int bit=0;bit<32;bit++) {
					int[] changed=base.clone();changed[p]^=1<<bit;
					if(!LodClip.geometryChanged(base[0],base[1],base[2],base[3],base[4],changed[0],changed[1],changed[2],changed[3],changed[4])) {
						MemoryUtil.memPutInt(at[p],changed[p]);
						if(!Arrays.equals(reference,remesh(planes,span,header,quads)))throw new AssertionError("Classifier missed native geometry change plane="+p+" bit="+bit);
						MemoryUtil.memPutInt(at[p],base[p]);checked++;
					}
				}
			}
			if(checked==0)throw new AssertionError("No shading bits checked");
			System.out.println("PASS: native mesher equivalence for "+checked+" ignored shading-bit mutations across ground, crowns and plants");
		}finally{MemoryUtil.nmemFree(planes);MemoryUtil.nmemFree(header);MemoryUtil.nmemFree(quads);}
	}
	private static byte[] remesh(long p,long span,long header,long quads) {
		int n=LodNative.meshTile(p,p+span,p+2*span,p+3*span,p+4*span,7,0,0,0,15,LodMesh.BLOCK,header,quads,131072);
		if(n<0)throw new AssertionError("Reference overflow");
		byte[] result=new byte[LodMesh.HDR_WORDS*4+n*8];MemoryUtil.memByteBuffer(header,LodMesh.HDR_WORDS*4).get(result,0,LodMesh.HDR_WORDS*4);
		MemoryUtil.memByteBuffer(quads,n*8).get(result,LodMesh.HDR_WORDS*4,n*8);return result;
	}
}
