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
			// A single continuous pillar must not inflate an otherwise open roof into a whole coarse cell.
			int roof=sparseRoof(fine,x*step,z*step,step,highest);
			if(roof>=0) selected=roof;
			int i=z*size+x;
			for(int plane=0;plane<LodCaptures.PLANES;plane++) result[plane][i]=fine[plane][selected];
			result[LodCaptures.GEOM][i]=LodClip.coarseGeom(result[LodCaptures.GEOM][i]);
			result[LodCaptures.COLOR][i]=fine[LodCaptures.COARSE_COLOR][selected];
			union(fine,result,i,x*step,z*step,step,highest,selected,roof>=0);
			if(knownLight && LodConfig.LIGHTING) result[LodCaptures.CROWN][i]=LodClip.litCrown(result[LodCaptures.CROWN][i],light);
		}
		return result;
	}

	/** Require a dominant matching roof/gap and at most 1/8 continuous supports. Boundary-spanning walls stay solid.
	 * This runs only during capture reduction, over at most 256 fine columns; no live chunks or connectivity walk. */
	private static int sparseRoof(int[][] fine,int x,int z,int step,int top) {
		if(step<4) return -1;
		int roof=-1,votes=0;
		for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
			int k=(z+dz)*16+x+dx,g=fine[LodCaptures.GEOM][k];
			if((g&(LodClip.GEOM_VALID|LodClip.GEOM_CROWN|4095))!=(LodClip.GEOM_VALID|LodClip.GEOM_CROWN|(top+512))) continue;
			if(votes==0) {roof=k;votes=1;}
			else votes+=sameRoof(fine,roof,k)?1:-1;
		}
		if(roof<0) return -1;
		int g=fine[LodCaptures.GEOM][roof],ground=(fine[LodCaptures.CROWN][roof]&4095)-512;
		if(top-LodClip.crownThickness(g)-ground<8) return -1;
		int matches=0,supports=0,xs=0,zs=0;
		for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
			int k=(z+dz)*16+x+dx;
			if(sameRoof(fine,roof,k)) matches++;
			if(support(fine,k,top,ground)) {supports++;xs|=1<<dx;zs|=1<<dz;}
		}
		int area=step*step;
		return matches*4>=area*3 && supports>0 && supports*8<=area
			&& Integer.bitCount(xs)<step && Integer.bitCount(zs)<step?roof:-1;
	}

	private static boolean sameRoof(int[][] fine,int a,int b) {
		int geometry=~(LodClip.GEOM_CLEAR|LodClip.GEOM_PLANT_BITS);
		int bounds=4095|LodClip.CROWN_WET|LodClip.CROWN_BOUNDED|LodClip.CROWN_SEGMENTED;
		return (fine[LodCaptures.GEOM][a]&geometry)==(fine[LodCaptures.GEOM][b]&geometry)
			&& (fine[LodCaptures.CROWN][a]&bounds)==(fine[LodCaptures.CROWN][b]&bounds)
			&& fine[LodCaptures.RUNS][a]==fine[LodCaptures.RUNS][b];
	}

	private static boolean support(int[][] fine,int k,int top,int ground) {
		int g=fine[LodCaptures.GEOM][k],cr=fine[LodCaptures.CROWN][k];
		return (g&(LodClip.GEOM_VALID|LodClip.GEOM_CROWN|LodClip.GEOM_WET|4095))==(LodClip.GEOM_VALID|(top+512))
			&& ((cr&LodClip.CROWN_BOUNDED)==0 || (cr&4095)-512<=ground);
	}

	private static void union(int[][] fine,int[][] out,int i,int x,int z,int step,int top,int selected,boolean sparse) {
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
		int[] bounds={Integer.MAX_VALUE,Integer.MIN_VALUE,0}; // Lowest solid, lower surface below the band, interval crossing its floor.
		LodIntervals.Span mark=(lo,hi)->{
			if(hi<=lo) return;
			bounds[0]=Math.min(bounds[0],lo);
			if(hi<=base) bounds[1]=Math.max(bounds[1],hi);
			if(lo<base && hi>base) bounds[2]=1;
			int a=Math.max(0,lo-base),b=Math.min(LodStructureLayers.RANGE,hi-base);
			if(a<b) occupied.set(a,b);
		};
		for(int dz=0;dz<step;dz++) for(int dx=0;dx<step;dx++) {
			int k=(z+dz)*16+x+dx;
			if(sparse && support(fine,k,top,(fine[LodCaptures.CROWN][selected]&4095)-512)) continue;
			LodIntervals.visit(fine[LodCaptures.GEOM][k],fine[LodCaptures.CROWN][k],fine[LodCaptures.RUNS][k],mark);
		}
		int first=occupied.nextSetBit(0);if(first<0) return;
		int end=occupied.nextClearBit(first),upper=occupied.nextSetBit(end);
		boolean bounded=bounds[0]>-100000;
		int ground=base+end;
		// A long exterior gap can put the actual ground below the upper interval budget. It is still a
		// separate lower surface; the first roof inside the band must not become infinite ground.
		if(bounds[1]!=Integer.MIN_VALUE && bounds[2]==0) {ground=bounds[1];upper=first;}
		if(upper<0) {
			out[LodCaptures.GEOM][i]=LodClip.geomWord(top,false)|flags;
			out[LodCaptures.CROWN][i]=bounded?((bounds[0]+512)&4095)|LodClip.CROWN_BOUNDED:0;
			out[LodCaptures.RUNS][i]=0;
			return;
		}
		int upperBase=base+upper;
		int packed=LodStructureLayers.encode(upperBase,top,y->occupied.get(y-base));
		if(packed==0) return;
		int old=fine[LodCaptures.CROWN][selected];
		int color=(fine[LodCaptures.GEOM][selected]&LodClip.GEOM_CROWN)!=0?LodClip.crownColor(old):fine[LodCaptures.COLOR][selected]&65535;
		out[LodCaptures.GEOM][i]=LodClip.crownGeomWord(top,top-upperBase,false)
			| flags | LodClip.depthBits(bounded?Math.clamp(ground-bounds[0],1,127):0);
		out[LodCaptures.CROWN][i]=((ground+512)&4095)|color<<12|LodClip.CROWN_SEGMENTED|(bounded?LodClip.CROWN_BOUNDED:0);
		out[LodCaptures.RUNS][i]=packed;
	}
}
