package mcopt.metal.lod;

import com.mojang.serialization.Codec;
import java.util.EnumSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Actual slab-roof capture over land/water, with continuous supports at both sample corners and interior cells. */
public final class LodOceanPlatformChecks {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var bind=Holder.Reference.class.getDeclaredMethod("bindTags",java.util.Collection.class);bind.setAccessible(true);
		for(var fluid:new net.minecraft.world.level.material.Fluid[]{net.minecraft.world.level.material.Fluids.WATER,net.minecraft.world.level.material.Fluids.FLOWING_WATER})
			bind.invoke(net.minecraft.core.registries.BuiltInRegistries.FLUID.wrapAsHolder(fluid),java.util.List.of(net.minecraft.tags.FluidTags.WATER));
		var biome=Holder.direct(new Biome.BiomeBuilder().hasPrecipitation(true).temperature(.8F).downfall(.4F)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
			.generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY).build());
		IdMapper<Holder<Biome>> ids=new IdMapper<>();ids.add(biome);
		var blockStrategy=Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		var biomeStrategy=Strategy.createForBiomes(ids);
		var factory=new PalettedContainerFactory(blockStrategy,Blocks.AIR.defaultBlockState(),
			PalettedContainer.codecRW(BlockState.CODEC,blockStrategy,Blocks.AIR.defaultBlockState()),biomeStrategy,biome,
			PalettedContainer.codecRO(Codec.STRING.xmap(s->biome,b->"minecraft:plains"),biomeStrategy,biome));
		for(int roof:new int[]{190,200,319}) for(boolean ocean:new boolean[]{false,true})
		for(int sx:new int[]{0,8}) for(Block support:new Block[]{Blocks.AIR,Blocks.SCAFFOLDING,Blocks.STONE}) {
			int surface=ocean?63:40;
			var chunk=new ProtoChunk(new ChunkPos(-2,3),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),factory,null);
			int bx=chunk.getPos().getMinBlockX(),bz=chunk.getPos().getMinBlockZ();
			for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
				for(int y=-64;y<63;y++) chunk.setBlockState(new BlockPos(bx+x,y,bz+z),
					(y<40?Blocks.STONE:ocean?Blocks.WATER:Blocks.AIR).defaultBlockState(),0);
				chunk.setBlockState(new BlockPos(bx+x,roof,bz+z),Blocks.COBBLESTONE_SLAB.defaultBlockState(),0);
			}
			if(support!=Blocks.AIR) for(int y=surface;y<roof;y++)
				chunk.setBlockState(new BlockPos(bx+sx,y,bz+sx),support.defaultBlockState(),0);
			chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
			Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
			var lighting=new LodChunks.LightAccess(true,true,p->15,p->0,p->Blocks.AIR.defaultBlockState());
			var snapshot=LodChunks.snapshot(chunk,lighting);
			var summary=LodChunks.summarize(snapshot);
			for(int i=0;i<256;i++) {
				if(i==sx*16+sx && support!=Blocks.AIR) {
					if(snapshot.crown()[i]!=null) throw new AssertionError("Continuous support was treated as an air gap");
					continue;
				}
				if(summary.height()[i]!=40 || summary.water()[i]!=(ocean?63:LodTile.DRY) || summary.crownLo()[i]!=roof || summary.crownHi()[i]!=roof+1)
					throw new AssertionError("Roof/ocean/seabed collapsed at roof "+roof+" column "+i+": "+summary.crownLo()[i]+".."+summary.crownHi()[i]);
			}
			var capture=LodCaptures.pack(summary);
			for(int level=0;level<=4;level++) {
				int size=16>>level;int[][] planes=capture.atLevel(level);
				for(int z=0;z<size;z++) for(int x=0;x<size;x++) {
					if(support!=Blocks.AIR && level<2 && x==(sx>>level) && z==(sx>>level)) continue; // Retain the close support; omit it only once sparse.
					int i=z*size+x;
					if(!occupied(planes,i,surface-1) || !occupied(planes,i,roof)) throw new AssertionError("Roof/water lost at level "+level);
					for(int y=surface+1;y<roof;y++) if(occupied(planes,i,y)) throw new AssertionError("Platform filled the air at level "+level+" Y="+y);
				}
			}
			var dark=LodChunks.snapshot(chunk,new LodChunks.LightAccess(true,true,p->0,p->0,p->Blocks.AIR.defaultBlockState()));
			if(dark.crown()[0]!=null) throw new AssertionError("Sealed unlit cavity passed exterior-gap detection");
		}
		System.out.println("PASS: 36 real slab-platform fixtures at Y190/200/319 preserve land/water and roofs on all five levels; sparse corner/interior scaffold/stone supports no longer fill coarse gaps, fine supports and sealed cavities remain unchanged");
	}
	private static boolean occupied(int[][] p,int i,int y) {
		boolean[] result={false};
		LodIntervals.visit(p[LodCaptures.GEOM][i],p[LodCaptures.CROWN][i],p[LodCaptures.RUNS][i],(lo,hi)->result[0]|=y>=lo && y<hi);
		return result[0];
	}
}
