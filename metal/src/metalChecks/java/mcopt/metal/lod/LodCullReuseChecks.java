package mcopt.metal.lod;

import org.lwjgl.system.MemoryUtil;

/** Every visibility input invalidates reuse; changing only shading does not. No GPU or game launch. */
public final class LodCullReuseChecks {
	public static void main(String[] args) {
		long mesh=MemoryUtil.nmemCalloc(1,LodMesh.FRAME_BYTES),comp=MemoryUtil.nmemCalloc(1,Lod.COMP_FRAME_BYTES);
		int[] mask=new int[512];LodCullReuse cache=new LodCullReuse();
		try {
			if(cache.matches(mesh,comp,1,2,3,mask,512)) throw new AssertionError("Uninitialized cull was reused");
			cache.remember(mesh,comp,1,2,3,mask,512);
			if(!cache.matches(mesh,comp,1,2,3,mask,512)) throw new AssertionError("Stationary cull was rebuilt");
			for(int at=0;at<LodMesh.FRAME_BYTES;at+=4) {
				MemoryUtil.memPutInt(mesh+at,1);
				if(cache.matches(mesh,comp,1,2,3,mask,512)) throw new AssertionError("Camera/projection/window/tier input ignored at "+at);
				MemoryUtil.memPutInt(mesh+at,0);
			}
			for(int at=0;at<Lod.COMP_FRAME_BYTES;at+=4) {
				MemoryUtil.memPutInt(comp+at,1);
				boolean geometry=at<128 || at==172 || at>=192&&at<256 || at>=Lod.COMP_FRAME_BYTES-32;
				if(cache.matches(mesh,comp,1,2,3,mask,512)==geometry) throw new AssertionError("Wrong geometry/shading key at "+at);
				MemoryUtil.memPutInt(comp+at,0);
			}
			for(int i=0;i<mask.length;i++) {
				mask[i]=1<<31;
				if(cache.matches(mesh,comp,1,2,3,mask,512)) throw new AssertionError("Near coverage bit ignored at "+i);
				mask[i]=0;
			}
			if(cache.matches(mesh,comp,2,2,3,mask,512) || cache.matches(mesh,comp,1,3,3,mask,512)
				|| cache.matches(mesh,comp,1,2,4,mask,512) || cache.matches(mesh,comp,1,2,3,mask,511))
				throw new AssertionError("Mesh/clip revision, arena relocation or mask size ignored");
			cache.invalidate();
			if(cache.matches(mesh,comp,1,2,3,mask,512)) throw new AssertionError("Disabled/alternate cull retained reusable state");
			System.out.println("PASS: exact stationary visibility reuse; camera/projection/resize/tier/handoff/mask/geometry/arena changes invalidate; fog, daylight and lightmap changes retain visibility");
		} finally {MemoryUtil.nmemFree(mesh);MemoryUtil.nmemFree(comp);}
	}
}
