package mcopt.metal.lod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.material.MaterialSystem;

/** The seeded frozen-ocean surface extension is separate from placed iceberg features and from density sampling. */
final class LodFrozenOcean {
	private static final MethodHandle EXTEND, DEPTH;
	static {
		try {
			var lookup = MethodHandles.privateLookupIn(MaterialSystem.class, MethodHandles.lookup());
			EXTEND = lookup.findVirtual(MaterialSystem.class, "frozenOceanExtension", MethodType.methodType(void.class,
				int.class, Biome.class, BlockColumn.class, BlockPos.MutableBlockPos.class, int.class, int.class, int.class));
			DEPTH = lookup.findVirtual(MaterialSystem.class, "getSurfaceDepth", MethodType.methodType(int.class, int.class, int.class));
		} catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
	}

	private LodFrozenOcean() { }
	static boolean frozen(Holder<Biome> biome) { return biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN); }

	/** The vanilla routine descends one column. Its writes never require a chunk, a full block array or a lighting engine. */
	static final class Column implements BlockColumn {
		int ground, water, top;
		BlockState surface, cap;
		void reset(int ground, int water, BlockState surface) {
			this.ground=ground;this.water=water;this.surface=surface;this.top=Integer.MIN_VALUE;this.cap=null;
		}
		@Override public BlockState getBlock(int y) {
			if (y==this.top) return this.cap;
			return y<this.ground ? this.surface : y<this.water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}
		@Override public void setBlock(int y, BlockState state) {
			if (y>=this.top) { this.top=y;this.cap=state; }
		}
	}

	static void sample(MaterialSystem system, Biome biome, Column column, BlockPos.MutableBlockPos pos, int x, int z) {
		try {
			int depth=(int)DEPTH.invokeExact(system,x,z);
			int minSurface=column.ground-1+depth-8;
			EXTEND.invokeExact(system,minSurface,biome,(BlockColumn)column,pos,x,z,Math.max(column.ground,column.water));
		} catch (Throwable e) { throw e instanceof RuntimeException r ? r : new IllegalStateException(e); }
	}

	static void apply(LodTile tile, MaterialSystem system) {
		Column column=new Column();BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
		int cell=1<<tile.level;
		for (int i=0;i<tile.cells();i++) {
			if (!frozen(tile.biome[i])) continue;
			int x=tile.x0+(i%tile.size)*cell,z=tile.z0+(i/tile.size)*cell;
			int top=Math.max(tile.height[i],tile.water[i]);BlockState cap=null;
			for (int dz=0;dz<cell;dz+=Math.max(1,cell/2)) for (int dx=0;dx<cell;dx+=Math.max(1,cell/2)) {
				column.reset(tile.ground[i],tile.water[i]==LodTile.DRY?system.getSeaLevel():tile.water[i],tile.state[i]);
				sample(system,tile.biome[i].value(),column,pos,x+dx,z+dz);
				if (column.top+1>top) { top=column.top+1;cap=column.cap; }
			}
			if (cap==null) continue;
			int[] packed={x,z,top,net.minecraft.world.level.block.Block.getId(cap),net.minecraft.world.level.block.Block.getId(Blocks.PACKED_ICE.defaultBlockState())};
			LodForest.applyIce(tile,packed);
		}
	}

	/** Upgrade only the original generated frozen-water candidates, including a peak already raised by another stage. */
	static boolean repair(LodNoise noise, long key, java.util.BitSet eligible, int[] g,int[] c,int[] cr,int[] tw,int[] runs) {
		int level=LodTile.levelOf(key),cell=1<<level,x0=LodTile.txOf(key)*64*cell,z0=LodTile.tzOf(key)*64*cell;
		Column column=new Column();BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
		var resolver=noise.resolver();boolean changed=false;
		int wall=LodColors.look(Blocks.PACKED_ICE.defaultBlockState()).side();
		for(int i=eligible.nextSetBit(0);i>=0;i=eligible.nextSetBit(i+1)) {
			int x=x0+(i&63)*cell,z=z0+(i>>6)*cell;
			Holder<Biome> biome=resolver.getNoiseBiome(x>>2,noise.seaLevel>>2,z>>2);
			if(!frozen(biome))continue;
			int top=(g[i]&4095)-512;BlockState cap=null;
			for(int dz=0;dz<cell;dz+=Math.max(1,cell/2))for(int dx=0;dx<cell;dx+=Math.max(1,cell/2)) {
				column.reset(noise.seaLevel-1,noise.seaLevel,Blocks.STONE.defaultBlockState());
				sample(noise.random.surfaceSystem(),biome.value(),column,pos,x+dx,z+dz);
				if(column.top+1>top){top=column.top+1;cap=column.cap;}
			}
			if(cap==null)continue;
			g[i]=LodClip.geomWord(top,false);c[i]=LodClip.colorWord(LodColors.look(cap).top(),wall);
			if(cr!=null)cr[i]=LodClip.belowWord(wall);if(runs!=null)runs[i]=0;
			if(tw!=null){int side=LodPalette.id(Blocks.PACKED_ICE.defaultBlockState());tw[i]=LodPalette.word(LodPalette.id(cap),side,side);if(tw.length>LodTile.CELLS)tw[LodTile.CELLS+i]=0;}
			changed=true;
		}
		return changed;
	}
}
