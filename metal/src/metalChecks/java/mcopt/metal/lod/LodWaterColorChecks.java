package mcopt.metal.lod;

import java.util.Arrays;

public final class LodWaterColorChecks {
	public static void main(String[] args) {
		if (LodWaterSurface.find(20,63,y->y>=20&&y<25 || y>=46&&y<63)!=63
			|| LodWaterSurface.find(20,63,y->y>=20&&y<25)!=25
			|| LodWaterSurface.find(20,63,y->false)!=LodTile.DRY
			|| LodWaterSurface.find(70,63,y->true)!=LodTile.DRY
			|| LodWaterSurface.find(45,63,y->y>=46&&y<63)!=63)
			throw new AssertionError("Ocean surface replaced by lower cave aquifer, or dry/local pools filled incorrectly");
		int water=0x234A8B, floor=0xDFCFA5;
		int shallow=LodWaterColor.surface(floor,water,1), deep=LodWaterColor.surface(floor,water,96);
		int old=LodColors.mix(floor,water,0.55F+1/24.0F);
		for (int shift : new int[] {0,8,16}) {
			int difference=((shallow>>shift)&255)-((deep>>shift)&255);
			int previous=((old>>shift)&255)-((water>>shift)&255);
			if (difference<0 || difference>previous/4+1) throw new AssertionError("Cave depth still dominates surface color");
		}
		if (deep!=water || LodWaterColor.surface(floor,water,-1)!=LodWaterColor.surface(floor,water,0))
			throw new AssertionError("Deep-water tint or shallow-depth bounds changed");
		for (int level=0;level<5;level++) {
			int[] g=new int[4096], c=new int[4096], cr=new int[4096];
			g[0]=LodClip.geomWord(63,true); c[0]=LodClip.colorWord(old,0x999999);
			g[1]=LodClip.crownGeomWord(81,1,false); c[1]=0x12345678; cr[1]=LodClip.crownWord(63,old,true);
			g[2]=LodClip.geomWord(64,false); c[2]=0x456789AB;
			g[3]=LodClip.GEOM_WET; c[3]=0x789ABCDE; // Invalid cell.
			g[4]=LodClip.geomWord(63,true); c[4]=0xDEF01234; // Ice marker returned by the biome/material lookup.
			int[] geometry=g.clone(), colors=c.clone(), crowns=cr.clone();
			int step=1<<level, x0=-2*(64<<level), z0=3*(64<<level); int[] queries={0};
			LodWaterColor.migrate(LodTile.key(level,-2,3),g,c,cr,(x,y,z)-> {
				if (y!=63 || z!=z0+step/2 || x<x0 || x>=x0+5*step) throw new AssertionError("Wrong biome position during cache migration");
				queries[0]++; return x==x0+4*step+step/2?-1:water;
			});
			if (queries[0]!=3 || !Arrays.equals(g,geometry) || c[1]!=colors[1] || c[2]!=colors[2] || c[3]!=colors[3] || c[4]!=colors[4])
				throw new AssertionError("Water migration changed ice, invalid/dry cells or roof/build geometry");
			if ((cr[1]&~0x0FFFF000)!=(crowns[1]&~0x0FFFF000) || (c[0]&65535)!=(c[0]>>>16)
				|| (c[0]&65535)!=((cr[1]>>12)&65535)) throw new AssertionError("Water sides or water beneath a roof differ from its top");
			for (int shift : new int[] {0,8,16}) {
				int rgb=c[0]&65535;
				int channel=shift==16?(rgb>>11)*255/31:shift==8?((rgb>>5)&63)*255/63:(rgb&31)*255/31;
				if (Math.abs(channel-((shallow>>shift)&255))>9) throw new AssertionError("Migrated and freshly captured water differ beyond RGB565 precision");
			}
		}
		System.out.println("PASS: ocean wins over a separate cave pool; dry/local pools preserved; depth color and five-level cache migration retain geometry/ice");
	}
}
