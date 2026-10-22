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

/** Multi-storey farms must not turn their lowest thin deck into an infinite terrain foundation. */
public final class LodFarmPlatformChecks {
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
		for(int roof:new int[]{190,200,319}) for(boolean trapdoorOnTop:new boolean[]{false,true}) for(boolean room:new boolean[]{false,true}) {
			var chunk=new ProtoChunk(new ChunkPos(-2,3),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),factory,null);
			int bx=chunk.getPos().getMinBlockX(),bz=chunk.getPos().getMinBlockZ();
			for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
				for(int y=-64;y<63;y++) chunk.setBlockState(new BlockPos(bx+x,y,bz+z),
					(y<40?Blocks.STONE:Blocks.WATER).defaultBlockState(),0);
				chunk.setBlockState(new BlockPos(bx+x,100,bz+z),(trapdoorOnTop?Blocks.COBBLESTONE_SLAB:Blocks.OAK_TRAPDOOR).defaultBlockState(),0);
				chunk.setBlockState(new BlockPos(bx+x,101,bz+z),(trapdoorOnTop?Blocks.OAK_TRAPDOOR:Blocks.COBBLESTONE_SLAB).defaultBlockState(),0);
				if(room) {
					chunk.setBlockState(new BlockPos(bx+x,roof-3,bz+z),Blocks.COBBLESTONE.defaultBlockState(),0);
					chunk.setBlockState(new BlockPos(bx+x,roof-1,bz+z),Blocks.OAK_TRAPDOOR.defaultBlockState()
						.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,net.minecraft.world.level.block.state.properties.Half.TOP),0);
				}
				chunk.setBlockState(new BlockPos(bx+x,roof,bz+z),Blocks.COBBLESTONE_SLAB.defaultBlockState(),0);
			}
			chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
			Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
			var snapshot=LodChunks.snapshot(chunk,new LodChunks.LightAccess(true,true,p->room && p.getY()==roof-2?0:15,p->0,p->Blocks.AIR.defaultBlockState()));
			var summary=LodChunks.summarize(snapshot);
			if(summary.height()[0]!=40 || summary.water()[0]!=63)
				throw new AssertionError("Thin farm floor became infinite ground: roof="+roof+" height="+summary.height()[0]+" water="+summary.water()[0]);
			var capture=LodCaptures.pack(summary);
			for(int level=0;level<=4;level++) {
				var planes=capture.atLevel(level);int size=16>>level;
				for(int i=0;i<size*size;i++) {
					if(!occupied(planes,i,62) || !occupied(planes,i,100) || !occupied(planes,i,roof))
						throw new AssertionError("Farm/ocean lost roof="+roof+" level="+level);
					for(int y=64;y<90;y++) if(occupied(planes,i,y))
						throw new AssertionError("Trapdoor deck fills ocean air: roof="+roof+" level="+level+" Y="+y);
				}
			}
			var sealed=LodChunks.snapshot(chunk,new LodChunks.LightAccess(true,true,p->p.getY()>roof?15:0,p->0,p->Blocks.AIR.defaultBlockState()));
			if(sealed.crown()[0]!=null) throw new AssertionError("Fully sealed farm/cave opened without exterior light");
		}
		if(args.length>0) savedColumns(java.nio.file.Path.of(args[0]),factory);
		System.out.println("PASS: multi-storey farms with trapdoor/slab lower decks over ocean retain water, both floors and the lower air gap at all five levels");
	}
	private static void savedColumns(java.nio.file.Path file,PalettedContainerFactory factory) throws Exception {
		var fixtures=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonArray();
		for(var entry:fixtures) {
			var column=entry.getAsJsonObject();int x=column.get("x").getAsInt(),z=column.get("z").getAsInt();
			boolean support=column.get("support").getAsBoolean(),wallAt175=false;
			var chunk=new ProtoChunk(new ChunkPos(x>>4,z>>4),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),factory,null);
			for(var part:column.getAsJsonArray("runs")) {
				var run=part.getAsJsonObject();var state=BlockState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,run.get("state")).getOrThrow();
				if(state.isAir()) continue;
				if(run.get("min").getAsInt()<=175 && run.get("max").getAsInt()>=175) wallAt175=true;
				for(int dz=0;dz<16;dz++) for(int dx=0;dx<16;dx++) for(int y=run.get("min").getAsInt();y<=run.get("max").getAsInt();y++)
					chunk.setBlockState(new BlockPos(chunk.getPos().getMinBlockX()+dx,y,chunk.getPos().getMinBlockZ()+dz),state,0);
			}
			chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
			Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE));
			var sky=column.getAsJsonArray("sky");var block=column.getAsJsonArray("block");
			var snapshot=LodChunks.snapshot(chunk,new LodChunks.LightAccess(true,true,
				p->p.getY()>=320?15:p.getY()<-64?0:sky.get(p.getY()+64).getAsInt(),
				p->p.getY()>=320 || p.getY()<-64?0:block.get(p.getY()+64).getAsInt(),p->Blocks.AIR.defaultBlockState()));
			var summary=LodChunks.summarize(snapshot);
			if((support?summary.height()[0]!=163:summary.water()[0]!=63) || summary.crownLo()[0]<100) throw new AssertionError("Saved farm column still collapses: "+x+","+z+" ground="+summary.height()[0]+" water="+summary.water()[0]+" roof="+summary.crownLo()[0]);
			var capture=LodCaptures.pack(summary);
			for(int level=0;level<=4;level++) {
				var planes=capture.atLevel(level);
				if(occupied(planes,0,100)!=support || occupied(planes,0,175)!=wallAt175 || !occupied(planes,0,62) || !occupied(planes,0,190))
					throw new AssertionError("Saved farm column lost exterior air, ocean or roof at level "+level+" position "+x+","+z);
			}
			System.out.println("PASS: saved farm column "+x+","+z+" keeps lower surface "+(support?summary.height()[0]:summary.water()[0])+" and upper layers "+summary.crownLo()[0]+".."+summary.crownHi()[0]+" at all five levels");
		}
	}
	private static boolean occupied(int[][] p,int i,int y) {
		boolean[] result={false};
		LodIntervals.visit(p[LodCaptures.GEOM][i],p[LodCaptures.CROWN][i],p[LodCaptures.RUNS][i],(lo,hi)->result[0]|=y>=lo && y<hi);
		return result[0];
	}
}
