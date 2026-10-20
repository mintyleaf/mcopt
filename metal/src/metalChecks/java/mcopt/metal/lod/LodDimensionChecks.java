package mcopt.metal.lod;

import java.util.BitSet;
import java.util.Arrays;
import mcopt.metal.MetalBridge;

/** Portal callback ordering and interior floor/ceiling geometry, including all coarse clipmap levels. */
public final class LodDimensionChecks {
	public static void main(String[] args) {
		Object overworld=new Object(),nether=new Object(),end=new Object(),rejoinedOverworld=new Object();
		// A new level sends chunks before its first frame binds the new field; old levels can also deliver late callbacks.
		var captured=new java.util.ArrayList<String>();
		capture(captured,overworld,overworld,overworld,"overworld:stone");
		capture(captured,overworld,nether,nether,"overworld:WRONG_netherrack_before_first_frame");
		capture(captured,nether,nether,overworld,"nether:WRONG_old_stone");
		capture(captured,nether,nether,nether,"nether:netherrack");
		capture(captured,nether,rejoinedOverworld,rejoinedOverworld,"nether:WRONG_returning_stone");
		capture(captured,rejoinedOverworld,rejoinedOverworld,nether,"overworld:WRONG_late_netherrack");
		capture(captured,rejoinedOverworld,rejoinedOverworld,overworld,"overworld:WRONG_previous_client_instance");
		capture(captured,rejoinedOverworld,rejoinedOverworld,rejoinedOverworld,"overworld:stone_again");
		capture(captured,rejoinedOverworld,end,end,"overworld:WRONG_end_stone");
		capture(captured,end,end,end,"end:end_stone");
		if(!captured.equals(java.util.List.of("overworld:stone","nether:netherrack","overworld:stone_again","end:end_stone")))
			throw new AssertionError("Dimension callbacks contaminated captured terrain: "+captured);
		if(LodWorldScope.accepts(null,null,null)) throw new AssertionError("Unowned callback accepted");
		BitSet solid=new BitSet();solid.set(0,32);solid.set(96,128);
		var cave=LodCaverns.find(0,127,64,y->!solid.get(y));
		if(cave==null || cave.floor()!=31 || cave.ceiling()!=96) throw new AssertionError("Bedrock roof filled cavern");
		solid.set(60,70);
		if(LodCaverns.find(0,127,45,y->!solid.get(y)).ceiling()!=60 || LodCaverns.find(0,127,80,y->!solid.get(y)).floor()!=69)
			throw new AssertionError("Stacked caverns ignore the view band");
		if(LodCaverns.find(-64,319,64,y->false)!=null || LodCaverns.find(0,127,64,y->y>=64 && y<66)!=null)
			throw new AssertionError("Solid columns or tiny cracks create fake caverns");
		if(LodCaverns.band(63,64,0,127)!=64 || LodCaverns.band(70,64,0,127)!=64
			|| LodCaverns.band(100,64,0,127)==64 || LodCaverns.band(128,64,0,127)!=LodCaverns.NONE)
			throw new AssertionError("Cavern band jitters or captures the roof as interior terrain");
		var selection=new LodCaverns.Selection();
		int band=selection.select(64,LodCaverns.NONE,0,127,0);
		// Continually changing flight altitude must not rebuild the field at every crossed band.
		for(int i=1;i<=3;i++) {
			band=selection.select(64+16*i,band,0,127,i*200_000_000L);
			if(band!=64) throw new AssertionError("Vertical flight repeatedly rebuilds the field");
		}
		band=selection.select(96,band,0,127,1_000_000_000L);
		if(selection.select(96,band,0,127,2_000_000_000L)!=64) throw new AssertionError("Rebuild cooldown ignored");
		band=selection.select(96,band,0,127,3_000_000_000L);
		if(band!=96) throw new AssertionError("Settled altitude did not update the cavern");
		if(selection.select(128,band,0,127,3_100_000_000L)!=LodCaverns.NONE
			|| selection.select(64,LodCaverns.NONE,0,127,3_200_000_000L)!=64)
			throw new AssertionError("Roof crossing delayed its geometry-mode switch");
		selection.reset();
		if(selection.select(32,LodCaverns.NONE,0,127,4_000_000_000L)!=32) throw new AssertionError("Previous dimension delayed the new view");
		long ctx=MetalBridge.createHeadlessContext();
		var clip=new LodClip(ctx,256,1024,true);
		try {
			if(clip.crownLevels!=clip.levels) throw new AssertionError("Coarse Nether levels fill ceilings down to ground");
			clip.recenter(0,0);
			for(int level=0;level<clip.levels;level++) {
				int n=LodTile.CELLS;
				int[] g=new int[n],c=new int[n],cr=new int[n],runs=new int[n];
				Arrays.fill(g,LodClip.crownGeomWord(112,16,false));Arrays.fill(cr,LodClip.crownWord(32,0xFF6600));
				if(!clip.put(level,0,0,g,c,cr,null,runs,null)) throw new AssertionError("Cavern tile rejected");
				int[] rg=new int[n],rc=new int[n],rr=new int[n],rrun=new int[n];
				clip.read(level,0,0,rg,rc,rr,null,rrun,null);
				if(!Arrays.equals(g,rg)||!Arrays.equals(cr,rr)) throw new AssertionError("Floor/ceiling lost at LOD level "+level);
			}
			var mesh=new LodMesh(ctx,clip);
			for(int level=0;level<clip.levels;level++) mesh.tilePut(level,0,0,true);
			mesh.close();mesh.close();
			if(mesh.pending()!=0) throw new AssertionError("Dimension switch retained pending native mesh results");
			for(long buffer:mesh.buffers()) LodNative.release(buffer);
		} finally { clip.free(); }
		System.out.println("PASS: portal/rejoin ownership, cavern floor/ceiling selection, vertical hysteresis and actual Metal clipmap at every Nether LOD level");
	}

	private static void capture(java.util.List<String> output,Object owner,Object current,Object source,String data) {
		if(LodWorldScope.accepts(owner,current,source)) output.add(data);
	}
}
