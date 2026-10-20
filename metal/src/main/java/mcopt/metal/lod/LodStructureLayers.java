package mcopt.metal.lod;

import java.util.function.IntPredicate;

/** Vertical stacks in one word: three exact intervals, or up to four sampled intervals when exact packing overflows. */
final class LodStructureLayers {
	static final int RANGE=252;
	private LodStructureLayers() { }
	static int ground(int start,int min,IntPredicate solid) {
		int y=start,last=start;
		while(y>min) {
			while(y>min && !solid.test(y)) y--;
			if(!solid.test(y)) break;
			last=y;int depth=0;
			while(y>=min && solid.test(y) && depth<6) {y--;depth++;}
			if(depth==6 || y<min) return last;
		}
		return last;
	}
	/** Thick intermediate shelves are not the ground when another exterior air gap lies underneath them. */
	static int exteriorGround(int start,int min,IntPredicate solid,IntPredicate exposed) {
		int floor=start,y=start;
		while(y>min) {
			while(y>=min && solid.test(y)) y--;
			boolean open=false;
			while(y>=min && !solid.test(y)) {open|=exposed.test(y);y--;}
			if(y<min || !open) break; // A sealed underground cave is not another exterior shelf.
			floor=y;
		}
		return floor;
	}
	static int encode(int base,int top,IntPredicate solid) {
		if(top-base>RANGE || top<=base) return 0;
		if(top-base>63) return sampled(base,top,solid);
		int count=0,word=0;
		for(int y=base;y<top;) {
			if(!solid.test(y)) {y++;continue;}
			int start=y;while(y<top && solid.test(y)) y++;
			int length=y-start;
			if(length>16 || count==3) return sampled(base,top,solid);
			word|=((start-base)|((length-1)<<6))<<(10*count++);
		}
		return count==0?0:word|(count<<30);
	}

	/** A zero count selects 30 occupancy bits at 1–9 block vertical resolution. Keep the widest air gaps. */
	private static int sampled(int base,int top,IntPredicate solid) {
		int span=top-base;
		if(span>63) span=Math.ceilDiv(span,4)*4;
		int unit=(span+29)/30,origin=top-span,mask=0;
		for(int y=base;y<top;y++) if(solid.test(y)) mask|=1<<((y-origin)/unit);
		while(Integer.bitCount(mask & ~(mask<<1))>4) {
			int bestStart=0,bestLength=31;
			for(int b=0;b<30;) {
				if((mask & 1<<b)!=0) {b++;continue;}
				int start=b;while(b<30 && (mask & 1<<b)==0) b++;
				if(start>0 && b<30 && b-start<bestLength) {bestStart=start;bestLength=b-start;}
			}
			mask|=((1<<bestLength)-1)<<bestStart;
		}
		return mask;
	}
}
