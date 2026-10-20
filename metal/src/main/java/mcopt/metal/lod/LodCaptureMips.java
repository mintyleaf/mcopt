package mcopt.metal.lod;

/** Compact capture reductions. Union the solid intervals so high roof edges cannot erase neighboring walls. */
final class LodCaptureMips {
	private LodCaptureMips() { }
	static int[][] reduce(int[][] fine, int level) {
		int step=1<<Math.min(level,4), size=16/step;
		int[][] result=new int[LodCaptures.PLANES][size*size];
		for(int z=0;z<size;z++) for(int x=0;x<size;x++) {
			int selected=(z*step)*16+x*step, highest=Integer.MIN_VALUE, light=0;
			boolean knownLight=false;
			for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
				int k=(z*step+dz)*16+x*step+dx, g=fine[LodCaptures.GEOM][k];
				int top=(g&4095)-512;
				if(top>highest) { highest=top;selected=k; }
				if((fine[LodCaptures.MATERIAL][k]&LodClip.TEX_LIGHT)!=0) {
					int p=fine[LodCaptures.LIGHT][k];knownLight=true;
					light=Math.max(light,Math.max(Math.max(p&15,p>>>8&15),Math.max(p>>>16&15,p>>>24&15)));
				}
			}
			int i=z*size+x;
			for(int plane=0;plane<LodCaptures.PLANES;plane++) result[plane][i]=fine[plane][selected];
			result[LodCaptures.GEOM][i]=LodClip.coarseGeom(result[LodCaptures.GEOM][i]);
			result[LodCaptures.COLOR][i]=fine[LodCaptures.COARSE_COLOR][selected];
			union(fine,result,i,x*step,z*step,step,highest,selected);
			if(knownLight && LodConfig.LIGHTING) result[LodCaptures.CROWN][i]=LodClip.litCrown(result[LodCaptures.CROWN][i],light);
		}
		return result;
	}

	private static void union(int[][] fine,int[][] out,int i,int x,int z,int step,int top,int selected) {
		boolean split=false;
		int flags=0;
		for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
			int k=(z+dz)*16+x+dx;
			if((fine[LodCaptures.GEOM][k]&LodClip.GEOM_WET)!=0 || (fine[LodCaptures.GEOM][k]&LodClip.GEOM_CROWN)!=0
				&& (fine[LodCaptures.CROWN][k]&LodClip.CROWN_WET)!=0) return; // Keep the existing water/bank approximation.
			split|=(fine[LodCaptures.GEOM][k]&LodClip.GEOM_CROWN)!=0 || (fine[LodCaptures.CROWN][k]&LodClip.CROWN_BOUNDED)!=0;
			flags|=fine[LodCaptures.GEOM][k]&LodClip.GEOM_CLEAR;
		}
		if(!split) return;
		int base=top-LodStructureLayers.RANGE;
		var occupied=new java.util.BitSet(LodStructureLayers.RANGE);
		int[] lowest={Integer.MAX_VALUE};
		LodIntervals.Span mark=(lo,hi)->{
			if(hi<=lo) return;
			lowest[0]=Math.min(lowest[0],lo);
			int a=Math.max(0,lo-base),b=Math.min(LodStructureLayers.RANGE,hi-base);
			if(a<b) occupied.set(a,b);
		};
		for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
			int k=(z+dz)*16+x+dx;
			LodIntervals.visit(fine[LodCaptures.GEOM][k],fine[LodCaptures.CROWN][k],fine[LodCaptures.RUNS][k],mark);
		}
		int first=occupied.nextSetBit(0);if(first<0) return;
		int end=occupied.nextClearBit(first),upper=occupied.nextSetBit(end);
		boolean bounded=lowest[0]>-100000;
		int ground=base+end;
		if(upper<0) {
			out[LodCaptures.GEOM][i]=LodClip.geomWord(top,false)|flags;
			out[LodCaptures.CROWN][i]=bounded?((lowest[0]+512)&4095)|LodClip.CROWN_BOUNDED:0;
			out[LodCaptures.RUNS][i]=0;
			return;
		}
		int upperBase=base+upper;
		int packed=LodStructureLayers.encode(upperBase,top,y->occupied.get(y-base));
		if(packed==0) return;
		int old=fine[LodCaptures.CROWN][selected];
		int color=(fine[LodCaptures.GEOM][selected]&LodClip.GEOM_CROWN)!=0?LodClip.crownColor(old):fine[LodCaptures.COLOR][selected]&65535;
		out[LodCaptures.GEOM][i]=LodClip.crownGeomWord(top,top-upperBase,false)
			| flags | LodClip.depthBits(bounded?Math.clamp(ground-lowest[0],1,127):0);
		out[LodCaptures.CROWN][i]=((ground+512)&4095)|color<<12|LodClip.CROWN_SEGMENTED|(bounded?LodClip.CROWN_BOUNDED:0);
		out[LodCaptures.RUNS][i]=packed;
	}
}
