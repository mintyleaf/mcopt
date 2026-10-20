package mcopt.metal.lod;

import java.util.Arrays;

/** Small structures/light sources must survive downsampling, with bounded interval and buffer costs. */
public final class LodScoutingChecks {
	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
		int[][] p=new int[LodCaptures.PLANES][256];
		Arrays.fill(p[LodCaptures.GEOM],LodClip.geomWord(64,false));
		Arrays.fill(p[LodCaptures.COLOR],0x12345678);Arrays.fill(p[LodCaptures.COARSE_COLOR],0x12345678);
		Arrays.fill(p[LodCaptures.CROWN],LodClip.crownWord(32,0x88AA44)|LodClip.CROWN_BOUNDED);
		p[LodCaptures.GEOM][17]=LodClip.geomWord(100,false); // tower between the old sample corners
		p[LodCaptures.CROWN][17]=LodClip.crownWord(80,0xAABBCC)|LodClip.CROWN_BOUNDED;
		p[LodCaptures.LIGHT][16]=15;p[LodCaptures.MATERIAL][16]=LodClip.TEX_LIGHT; // tiny light beside it
		for(int level=1;level<=4;level++) {
			int[][] coarse=LodCaptureMips.reduce(p,level);int cr=coarse[LodCaptures.CROWN][0];
			boolean[] tower={false},gap={false};LodIntervals.visit(coarse[0][0],coarse[2][0],coarse[5][0],(lo,hi)->{tower[0]|=lo<=90&&hi>90;gap[0]|=lo<=70&&hi>70;});
			if((coarse[LodCaptures.GEOM][0]&4095)-512!=100 || (cr&LodClip.CROWN_BOUNDED)==0 || !tower[0] || gap[0]
				|| (cr&LodClip.CROWN_LIT)==0 || (cr>>>24&15)!=15) throw new AssertionError("Tower or small light disappeared at level "+level);
		}
		p[LodCaptures.MATERIAL][16]=0;
		if((LodCaptureMips.reduce(p,4)[LodCaptures.CROWN][0]&LodClip.CROWN_LIT)!=0) throw new AssertionError("Unready light became valid");
		for(int flags:new int[]{0,LodClip.CROWN_BOUNDED,LodClip.CROWN_SEGMENTED,LodClip.CROWN_BOUNDED|LodClip.CROWN_SEGMENTED}) {
			int original=LodClip.crownWord(64,0xAABBCC)|flags,lit=LodClip.litCrown(original,13);
			if((lit&0x70000FFF)!=(original&0x70000FFF) || (lit>>>24&15)!=13) throw new AssertionError("Coarse light overwrote interval metadata");
		}
		int ground=LodStructureLayers.ground(90,38,y->y<=63 || y==80 || y==90);
		if(ground!=63) throw new AssertionError("Intermediate floor became infinite lower terrain");
		if(LodStructureLayers.exteriorGround(136,-64,y->y<=90 || y>=126&&y<=136,y->y>90)!=90
			|| LodStructureLayers.exteriorGround(136,-64,y->y<=90 || y>=126&&y<=136,y->false)!=136)
			throw new AssertionError("Thick exterior shelf becomes ground, or sealed cavity is exposed");
		int runs=LodStructureLayers.encode(80,121,y->y==80 || y==100 || y==120);
		if((runs>>>30)!=3 || (runs&63)!=0 || ((runs>>>10)&63)!=20 || ((runs>>>20)&63)!=40)
			throw new AssertionError("Stack collapsed a wide air gap or lost a floor");
		if(LodStructureLayers.encode(0,300,y->true)!=0) throw new AssertionError("Unrepresentable interval silently truncated");
		int extended=LodClip.crownGeomWord(220,80,false);
		if(LodClip.crownThickness(extended)!=80 || LodClip.crownThickness(LodClip.coarseGeom(extended))!=80
			|| (LodClip.withoutCrown(extended)&LodClip.GEOM_FRINGE)!=0) throw new AssertionError("Extended range lost in packing/coarsening");
		// A roof edge is higher than the wall beside it. Coarse sampling must retain the wall's whole occupied span.
		Arrays.fill(p[LodCaptures.GEOM],LodClip.crownGeomWord(200,4,false));
		Arrays.fill(p[LodCaptures.CROWN],LodClip.crownWord(64,0x889977)|LodClip.CROWN_BOUNDED);
		Arrays.fill(p[LodCaptures.RUNS],(32+512)|((196+512)<<12));
		p[LodCaptures.GEOM][1]=LodClip.geomWord(199,false)|LodClip.GEOM_CLEAR;p[LodCaptures.CROWN][1]=(32+512)|LodClip.CROWN_BOUNDED;
		for(int level=1;level<=4;level++) {
			var coarse=LodCaptureMips.reduce(p,level);boolean[] wall={false};
			LodIntervals.visit(coarse[0][0],coarse[2][0],coarse[5][0],(lo,hi)->{wall[0]|=lo<=100&&hi>100;});
			if(!wall[0]) throw new AssertionError("Roof edge erased neighboring wall at level "+level);
			if((coarse[0][0]&LodClip.GEOM_CLEAR)==0) throw new AssertionError("Non-occluding fine geometry became a trusted coarse occluder");
		}
		// Four separated shelves and a thick floating roof previously fell back to a single solid column.
		int sampled=LodStructureLayers.encode(80,140,y->y<100 || y==112 || y==124 || y>=136);
		if(sampled==0 || (sampled>>>30)!=0 || (sampled & 1<<13)!=0 || (sampled & 1<<20)!=0
			|| Integer.bitCount(sampled & ~(sampled<<1))!=4)
			throw new AssertionError("Sampled vertical stack fills large gaps or loses its four intervals");
		for(int span=17;span<=63;span++) {
			int last=span-1;
			int word=LodStructureLayers.encode(0,span,y->y<17 || y%4==0 || y==last);
			if(word==0 || word>>>30!=0 || Integer.bitCount(word & ~(word<<1))>4)
				throw new AssertionError("Vertical overflow exceeds the native interval budget");
			int unit=(span+29)/30;
			for(int y=0;y<span;y++) if((y<17 || y%4==0 || y==span-1) && (word & 1<<(y/unit))==0)
				throw new AssertionError("Vertical quantization removed a solid surface");
		}
		for(int span=64;span<=252;span++) {
			int last=span-1,word=LodStructureLayers.encode(0,span,y->y<17 || y%20==0 || y==last);
			var occupied=new java.util.BitSet();
			LodIntervals.visit(LodClip.crownGeomWord(span,span,false),LodClip.CROWN_SEGMENTED|LodClip.CROWN_BOUNDED,word,
				(lo,hi)->{if(hi>0) occupied.set(Math.max(0,lo),hi);});
			for(int y=0;y<span;y++) if((y<17 || y%20==0 || y==last) && !occupied.get(y))
				throw new AssertionError("Extended vertical quantization removes a solid at "+y+" span "+span);
		}
		if(LodRefinement.probeTicketRadius()>=0) throw new AssertionError("Structure probe requests FULL terrain");
		System.out.println("PASS: off-corner towers and small lights survive four reductions; invalid light stays unknown; interval metadata and three widely separated floors survive compact packing; probes use partial tickets");
	}
}
