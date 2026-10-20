package mcopt.metal.lod;

/** Packed-column intervals used when reducing populated geometry. Keep the union, including walls beside roof edges. */
final class LodIntervals {
	@FunctionalInterface interface Span {void accept(int lo,int hi);}
	private LodIntervals() { }
	static void visit(int g,int cr,int runs,Span output) {
		int top=(g&4095)-512,ground=(cr&4095)-512;
		if((g&LodClip.GEOM_VALID)==0 || top==LodIslands.EMPTY && (g&LodClip.GEOM_CROWN)==0) return;
		if((g&LodClip.GEOM_CROWN)==0) {output.accept((cr&LodClip.CROWN_BOUNDED)!=0?ground:-100000,top);return;}
		int base=top-LodClip.crownThickness(g);
		if((cr&LodClip.CROWN_SEGMENTED)!=0) {
			if(ground>LodIslands.EMPTY) output.accept((cr&LodClip.CROWN_BOUNDED)!=0?ground-Math.max(1,g>>>25):-100000,ground);
			int count=runs>>>30;
			if(count!=0) for(int k=0;k<count;k++) {
				int r=runs>>>(10*k)&1023,lo=base+(r&63);output.accept(lo,lo+(r>>>6)+1);
			} else {
				int unit=Math.ceilDiv(top-base,30);
				for(int b=0;b<30;) {
					if((runs&1<<b)==0) {b++;continue;}
					int start=b;while(b<30 && (runs&1<<b)!=0) b++;
					output.accept(base+start*unit,Math.min(top,base+b*unit));
				}
			}
		} else if((cr&LodClip.CROWN_BOUNDED)!=0) {
			output.accept((runs&4095)-512,ground);output.accept((runs>>>12&4095)-512,top);
		} else {
			output.accept(-100000,ground);
			if(runs==0) output.accept(base,top);
			else for(int k=0;k<4;k++) {
				int r=runs>>>(8*k)&255;if(r>>>3==0) break;
				base+=r&7;output.accept(base,base+(r>>>3));base+=r>>>3;
			}
		}
	}
}
