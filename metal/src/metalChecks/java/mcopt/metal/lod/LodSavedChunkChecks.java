package mcopt.metal.lod;

import com.mojang.serialization.Codec;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Real saved sections through the production snapshot/packing path, without loading a world or renderer. */
public final class LodSavedChunkChecks {
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class); bind.setAccessible(true);
		for (Block b : new Block[]{Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES}) bind.invoke(b.builtInRegistryHolder(), List.of(BlockTags.LEAVES));
		for (Block b : new Block[]{Blocks.OAK_LOG, Blocks.JUNGLE_LOG}) bind.invoke(b.builtInRegistryHolder(), List.of(BlockTags.LOGS));
		var biome = Holder.direct(new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8F).downfall(0.4F)
			.specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
			.generationSettings(BiomeGenerationSettings.EMPTY).mobSpawnSettings(MobSpawnSettings.EMPTY).build());
		IdMapper<Holder<Biome>> ids = new IdMapper<>(); ids.add(biome);
		var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
		var biomeStrategy = Strategy.createForBiomes(ids);
		var factory = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(),
			PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState()), biomeStrategy, biome,
			PalettedContainer.codecRO(Codec.STRING.xmap(s -> biome, b -> "minecraft:plains"), biomeStrategy, biome));
		var height = LevelHeightAccessor.create(-64, 384);
		var pos = new ChunkPos(-26,21);
		List<SerializableChunkData.SectionData> sections = new ArrayList<>();
		for (int sy=-4;sy<21;sy++) sections.add(new SerializableChunkData.SectionData(sy, new LevelChunkSection(factory), new DataLayer(), new DataLayer(15)));
		// The exact broken forest column: ground 70, a low leaf 72 and hanging vines 92..96. The old 8-step scan saved air at 88.
		set(sections,8,70,6,Blocks.GRASS_BLOCK); set(sections,8,72,6,Blocks.OAK_LEAVES);
		for (int y=92;y<=96;y++) set(sections,8,y,6,Blocks.VINE);
		// A tall canopy used to exhaust the separate 48-step crown scan and turn its remaining air into gray ground.
		set(sections,9,0,6,Blocks.GRASS_BLOCK); set(sections,9,110,6,Blocks.JUNGLE_LEAVES); set(sections,9,112,6,Blocks.JUNGLE_LEAVES);
		set(sections,10,70,6,Blocks.GRASS_BLOCK); set(sections,10,80,6,Blocks.STONE);
		// A populated tower with three widely separated floors over natural ground.
		for(int y=-64;y<64;y++) set(sections,12,y,6,Blocks.STONE);
		for(int y:new int[]{80,100,120}) set(sections,12,y,6,Blocks.OAK_PLANKS);
		set(sections,13,101,6,Blocks.END_ROD);
		for(int y=56;y<=63;y++) set(sections,13,y,6,Blocks.STONE);
		// A canopy over a sideways branch, with open air below; and four shelves over solid ground.
		for(int x:new int[]{0,1}) for(int y=56;y<=63;y++) set(sections,x,y,10,Blocks.STONE);
		set(sections,0,96,10,Blocks.OAK_LEAVES);set(sections,0,94,10,Blocks.OAK_LOG);
		for(int y:new int[]{80,94,108,122}) set(sections,1,y,10,Blocks.OAK_PLANKS);
		for(int y=56;y<=63;y++) set(sections,2,y,10,Blocks.STONE);
		for(int y=140;y<220;y++) set(sections,2,y,10,Blocks.STONE);
		for(int x:new int[]{2,3}) for(int y=40;y<64;y++) set(sections,x,y,2,Blocks.END_STONE);
		for(int y=64;y<73;y++) set(sections,2,y,2,Blocks.CHORUS_PLANT);
		set(sections,2,73,2,Blocks.CHORUS_FLOWER);
		for(int y=69;y<71;y++) set(sections,3,y,2,Blocks.CHORUS_PLANT);
		set(sections,3,71,2,Blocks.CHORUS_FLOWER);
		var raw = new SerializableChunkData(factory,pos,-4,0,0,ChunkStatus.FULL,null,null,UpgradeData.EMPTY,new HashMap<>(),null,null,true,sections,List.of(),List.of(),null);
		var detached = LodSavedChunks.detached(height,raw,true);
		Heightmap.primeHeightmaps(detached.chunk(), EnumSet.of(Heightmap.Types.WORLD_SURFACE));
		var snapshot = LodChunks.snapshot(detached.chunk(),detached.lighting());
		int branch=10*16,shelves=branch+1;
		if(snapshot.height()[branch]!=64 || snapshot.crownLo()[branch]!=94 || snapshot.crownHi()[branch]!=97)
			throw new AssertionError("Sideways branch became an infinite terrain column");
		var vertical=LodChunks.summarize(snapshot);
		int cliff=branch+2;
		if(snapshot.height()[cliff]!=64 || snapshot.crownLo()[cliff]!=140 || snapshot.crownHi()[cliff]!=220)
			throw new AssertionError("Thick natural overhang became a column");
		var cap=LodCaptures.pack(vertical);
		if(LodClip.crownThickness(cap.planes()[0][cliff])<80) throw new AssertionError("Overhang thickness clipped to 63");
		if(!vertical.segmented()[shelves] || vertical.runs()[shelves]==0 || vertical.runs()[shelves]>>>30!=0
			|| vertical.height()[shelves]!=64)
			throw new AssertionError("Fourth shelf lost its vertical gaps during snapshot/summarization");
		int a=6*16+8,b=a+1,build=a+2;
		if (snapshot.top()[a].isAir() || snapshot.height()[a]!=71 || snapshot.crownHi()[a]!=73 || snapshot.crownLo()[a]!=72)
			throw new AssertionError("Saved vines still become air/solid gray columns");
		if (snapshot.top()[b].isAir() || snapshot.height()[b]!=1 || snapshot.crownHi()[b]!=113 || snapshot.crownLo()[b]!=110)
			throw new AssertionError("Tall crown scan saves air as its lower terrain");
		if (snapshot.crown()[build]!=Blocks.STONE.defaultBlockState() || snapshot.height()[build]!=71 || snapshot.crownLo()[build]!=80)
			throw new AssertionError("Saved roof/build lost during targeted recovery");
		int gray=LodClip.colorWord(0x7F7F7F,0x7F7F7F), air=0x1002259;
		if (LodField.cachedAirSurface(air,gray,0,0,true)!=89
			|| LodField.cachedAirSurface(air,gray,0,1,true)!=Integer.MIN_VALUE
			|| LodField.cachedAirSurface(air|LodClip.GEOM_WET,gray,0,0,false)!=Integer.MIN_VALUE
			|| LodField.cachedAirSurface(air&~LodClip.GEOM_CLEAR,gray,0,0,true)!=Integer.MIN_VALUE
			|| LodField.cachedAirSurface(LodClip.crownGeomWord(113,3,false)|LodClip.GEOM_CLEAR,0,LodClip.crownWord(64,0x7F7F7F),0,true)!=64)
			throw new AssertionError("Legacy air candidate misses invalid data or includes genuine gray blocks/water");
		var summary=LodChunks.summarize(snapshot);
		var endSummary=LodChunks.summarize(LodChunks.snapshot(detached.chunk(),detached.lighting(),LodCaverns.NONE,true));
		var chorusCapture=LodCaptures.pack(endSummary);
		for(int x:new int[]{2,3}) {
			int i=2*16+x;int[][] data=chorusCapture.planes();int bound=data[LodCaptures.RUNS][i];
			if(endSummary.height()[i]!=64 || endSummary.islandBottom()[i]!=40 || endSummary.crownLo()[i]!=(x==2?64:69)
				|| (data[LodCaptures.GEOM][i]&LodClip.GEOM_CROWN)==0 || (data[LodCaptures.CROWN][i]&4095)-512!=64
				|| LodPalette.stateOf(data[LodCaptures.MATERIAL][i]>>>10&1023).getBlock()!=Blocks.CHORUS_PLANT)
				throw new AssertionError("Chorus root/branch loses its purple material, island body or air underneath at column "+x);
			for(int y=0;y<80;y++) {
				int cy=y;boolean[] present={false};
				LodIntervals.visit(data[0][i],data[2][i],bound,(lo,hi)->{present[0]|=cy>=lo&&cy<hi;});
				boolean expected=y>=40 && y<64 || y>=(x==2?64:69) && y<(x==2?74:72);
				if(present[0]!=expected) throw new AssertionError("Chorus interval changed at "+x+","+y);
			}
		}
		int tower=6*16+12;
		if(snapshot.height()[tower]!=64 || (snapshot.light()[tower]>>>8&15)<14 || !summary.segmented()[tower] || summary.crownLo()[tower]!=80
			|| summary.crownHi()[tower]!=121 || (summary.runs()[tower]>>>30)!=3)
			throw new AssertionError("Production snapshot collapses a tower into its intermediate floor or loses separated floors");
		var towerCapture=LodCaptures.pack(summary);
		for(int level=0;level<3;level++) {
			int[][] data=towerCapture.atLevel(level);int k=(6>>level)*(16>>level)+(12>>level);
			if((data[LodCaptures.CROWN][k]&LodClip.CROWN_SEGMENTED)==0 || (data[LodCaptures.RUNS][k]>>>30)!=3
				|| level>0 && (data[LodCaptures.CROWN][k]>>>24&15)<14)
				throw new AssertionError("Production capture loses populated structure intervals at level "+level);
		}
		int n=LodTile.CELLS; int[] g=new int[n],c=new int[n],cr=new int[n],tw=new int[2*n],runs=new int[n],plants=new int[2*n];
		Arrays.fill(g,123);Arrays.fill(tw,456);Arrays.fill(plants,789);
		LodField.packCapturedCell(0,summary,a,3,g,c,cr,tw,runs,plants);
		if ((g[3]&4095)-512!=73 || (cr[3]&4095)-512!=71 || g[2]!=123 || tw[2]!=456 || plants[2]!=789
			|| tw[n+3]!=summary.light()[a] || (g[3]&LodClip.GEOM_CROWN)==0)
			throw new AssertionError("Sparse cache recovery loses the actual canopy/ground/light or overwrites unrelated builds");
		Arrays.fill(g,0);Arrays.fill(c,0);Arrays.fill(cr,0);Arrays.fill(tw,0);Arrays.fill(runs,0);Arrays.fill(plants,0);
		int broken=(342&63)*64+(-408&63), realBuild=broken+2;
		g[broken]=air;c[broken]=gray;cr[broken]=LodClip.belowWord(0x7F7F7F);
		g[realBuild]=air;c[realBuild]=gray;cr[realBuild]=LodClip.belowWord(0x7F7F7F); // Same gray candidate, actual saved block at y80 is stone.
		g[realBuild]=(g[realBuild]&~4095)|(81+512);
		if (!LodField.repairAirSurfaces(LodTile.key(0,-7,5),-64,g,c,cr,tw,runs,plants,(x,z)->x==-26&&z==21?detached:null)
			|| (g[broken]&4095)-512!=73 || (cr[broken]&4095)-512!=71 || (g[realBuild]&4095)-512!=81 || c[realBuild]!=gray)
			throw new AssertionError("Saved cache repair fails to remove gray air or overwrites a genuine solid gray build");
		int[] untouched=g.clone();
		if (LodField.repairAirSurfaces(LodTile.key(0,-7,5),-64,g,c,cr,tw,runs,plants,(x,z)->null) || !Arrays.equals(g,untouched))
			throw new AssertionError("Missing saved chunks trigger procedural regeneration or overwrite the cache");
		// Empty sky sections above terrain are commonly omitted from disk; they are still fully sky lit.
		var lightSections = new ArrayList<SerializableChunkData.SectionData>();
		for (int sy=-4;sy<20;sy++) lightSections.add(new SerializableChunkData.SectionData(sy,new LevelChunkSection(factory),new DataLayer(),sy<4?new DataLayer(15):null));
		set(lightSections,15,63,6,Blocks.GRASS_BLOCK);
		var openRaw = new SerializableChunkData(factory,pos,-4,0,0,ChunkStatus.FULL,null,null,UpgradeData.EMPTY,new HashMap<>(),null,null,true,lightSections,List.of(),List.of(),null);
		var open = LodSavedChunks.detached(height,openRaw,true);
		Heightmap.primeHeightmaps(open.chunk(),EnumSet.of(Heightmap.Types.WORLD_SURFACE));
		var openSnapshot = LodChunks.snapshot(open.chunk(),open.lighting());
		if (!openSnapshot.lightValid()[6*16+15] || (openSnapshot.light()[6*16+15]&240)!=240) throw new AssertionError("Omitted sky sections become unlit terrain");
		// A boundary wall receives block light from the adjacent saved chunk, without requesting a live chunk.
		var sidePos = new ChunkPos(pos.x()+1,pos.z());
		var sideLighting = new LodChunks.LightAccess(true,true,p->15,p->p.getY()==63?13:0,p->Blocks.AIR.defaultBlockState());
		var side = new LodSavedChunks.Saved(new ProtoChunk(sidePos,UpgradeData.EMPTY,height,factory,null),sideLighting);
		var neighborhood = Map.of(LodForest.chunkKey(pos.x(),pos.z()),open,LodForest.chunkKey(sidePos.x(),sidePos.z()),side);
		var boundary = LodSavedChunks.snapshot(open,neighborhood);
		if (((boundary.light()[6*16+15]>>>8)&15)!=13) throw new AssertionError("Adjacent saved block light lost at chunk boundary");
		// A live update submitted while a saved read was in flight must invalidate that read before it is queued.
		var captureRoot = java.nio.file.Files.createTempDirectory("mcopt-import-race-");
		try (var store = new LodCaptures(captureRoot,6,false)) {
			long key=LodForest.chunkKey(pos.x(),pos.z()), before=store.stamp(key);
			store.put(LodCaptures.pack(summary));
			if (store.captureIfUnchanged(snapshot,before,(s,stamp)->{throw new AssertionError("Stale import callback");})!=-1) throw new AssertionError("Stale disk read overwrites live edit");
			long latest=store.stamp(key);
			if (store.captureIfUnchanged(snapshot,latest,(s,stamp)->{})<=latest) throw new AssertionError("Unchanged import rejected");
		} finally { java.nio.file.Files.delete(captureRoot); }
		String fixture=System.getenv("MCOPT_LOD_SAVED_FIXTURE");
		if (fixture!=null) {
			var tag=net.minecraft.nbt.NbtIo.read(java.nio.file.Path.of(fixture));
			var data=SerializableChunkData.parse(height,factory,tag);
			var saved=LodSavedChunks.detached(height,data,true);
			var actual=LodChunks.snapshot(saved.chunk(),saved.lighting());
			int i=(342&15)*16+(-408&15);
			if (actual.height()[i]!=71 || actual.top()[i].isAir() || actual.crownHi()[i]!=73)
				throw new AssertionError("Actual saved forest does not recover its ground/canopy");
			System.out.println("PASS: actual saved forest column -408,342 recovers ground 71 and canopy 73 instead of gray air surface 89");
		}
		String overhangFixture=System.getenv("MCOPT_LOD_OVERHANG_FIXTURE");
		if(overhangFixture!=null) {
			var tag=net.minecraft.nbt.NbtIo.read(java.nio.file.Path.of(overhangFixture));
			var data=SerializableChunkData.parse(height,factory,tag);var saved=LodSavedChunks.detached(height,data,true);
			var actual=LodChunks.snapshot(saved.chunk(),saved.lighting());
			if(actual.height()[0]!=91) throw new AssertionError("Actual stacked cliff stops at intermediate floor "+actual.height()[0]+" instead of 91");
			var capture=LodCaptures.pack(LodChunks.summarize(actual));
			int[][] packed=capture.planes();
			for(int y:new int[]{110,130,148,160}) {
				int cy=y;boolean[] present={false};LodIntervals.visit(packed[0][0],packed[2][0],packed[5][0],(lo,hi)->{present[0]|=cy>=lo&&cy<hi;});
				if(present[0]!=(y==130 || y==160)) throw new AssertionError("Actual cliff lost exterior gap or shelf at "+y);
			}
			System.out.println("PASS: actual screenshot cliff -320,1344 keeps ground at 91, both stone shelves and both exterior air gaps");
		}
		String cityFixture=System.getenv("MCOPT_LOD_CITY_FIXTURE");
		if(cityFixture!=null) {
			var tag=net.minecraft.nbt.NbtIo.read(java.nio.file.Path.of(cityFixture));
			var endHeight=LevelHeightAccessor.create(0,256);
			var data=SerializableChunkData.parse(endHeight,factory,tag);
			var saved=LodSavedChunks.detached(endHeight,data,false);
			var actual=LodChunks.snapshot(saved.chunk(),saved.lighting(),LodCaverns.NONE,true);
			var capture=LodCaptures.pack(LodChunks.summarize(actual));int checked=0;
			for(int y=0;y<256;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
				var state=saved.chunk().getBlockState(new BlockPos(data.chunkPos().getMinBlockX()+x,y,data.chunkPos().getMinBlockZ()+z));
				String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
				if(!name.startsWith("purpur_") && !name.startsWith("end_stone_brick")) continue;
				checked++;
				for(int l=0;l<=4;l++) {
					int[][] packed=capture.atLevel(l);int k=(z>>l)*(16>>l)+(x>>l),cy=y;boolean[] present={false};
					LodIntervals.visit(packed[0][k],packed[2][k],packed[5][k],(lo,hi)->{present[0]|=cy>=lo&&cy<hi;});
					if(!present[0]) throw new AssertionError("Actual End-city body missing at "+x+","+y+","+z+" level "+l
						+", snapshot ground="+actual.height()[z*16+x]+" crown="+actual.crownLo()[z*16+x]+".."+actual.crownHi()[z*16+x]
						+", packed="+Integer.toHexString(packed[0][k])+"/"+Integer.toHexString(packed[2][k])+"/"+Integer.toHexString(packed[5][k]));
				}
			}
			if(checked<100) throw new AssertionError("City fixture has no meaningful structure");
			System.out.println("PASS: all "+checked+" actual End-city blocks retain geometry on all five levels");
		}
		checkClientCopy(detached.chunk(), factory);
		checkPrepared(snapshot);
		checkDeferredQueue(snapshot);
		checkPipeline(snapshot);
		System.out.println("PASS: saved vines, tall crowns, real builds, checked light and sparse cache repair; no world chunk loading");
	}

	private static void checkClientCopy(ProtoChunk source, PalettedContainerFactory factory) {
		var sky = new DataLayer(15); var block = new DataLayer(3);
		var side = new ProtoChunk(new ChunkPos(source.getPos().x()+1,source.getPos().z()),UpgradeData.EMPTY,
			LevelHeightAccessor.create(source.getMinY(),source.getHeight()),factory,null);
		var lamp = new BlockPos(side.getPos().getMinBlockX(),70,side.getPos().getMinBlockZ()+6);
		side.setBlockState(lamp,Blocks.END_ROD.defaultBlockState(),0);
		ChunkAccess[] sides = {side,null,null,null};
		var lighting = new LodChunks.LightAccess(true,true,p->15,p->(p.getY()>>4)>=-5 && (p.getY()>>4)<21?3:0,
			p -> (p.getX()>>4)==side.getPos().x() && (p.getZ()>>4)==side.getPos().z() ? side.getBlockState(p) : Blocks.AIR.defaultBlockState());
		LodClientCapture.Frozen frozen = null;
		int[][] before = null;
		for(int mode=0;mode<3;mode++) {
			int cavern=mode==1?64:LodCaverns.NONE; boolean islands=mode==2;
			boolean hasSky=mode==0;
			var reference=new LodChunks.LightAccess(true,hasSky,p->hasSky?15:0,lighting.block(),lighting.outside());
			frozen=LodClientCapture.freeze(source,sides,factory,-5,26,true,hasSky,(layer,pos)->layer==LightLayer.SKY?sky:block,cavern,islands);
			before=LodCaptures.pack(LodChunks.summarize(LodChunks.snapshot(source,reference,cavern,islands))).planes();
			var actual=frozen.snapshot();
			int[][] after=LodCaptures.pack(LodChunks.summarize(actual)).planes();
			for(int p=0;p<before.length;p++) for(int i=0;i<256;i++) if(before[p][i]!=after[p][i])
				throw new AssertionError("Detached client capture changed mode "+mode+" plane "+p+" column "+i+": "+Integer.toHexString(before[p][i])+" -> "+Integer.toHexString(after[p][i]));
		}
		// Mutations and engine removal after copying must not change the worker's view, including boundary emitters.
		source.getSection(source.getSectionIndex(96)).setBlockState(8,0,6,Blocks.DIAMOND_BLOCK.defaultBlockState());
		side.setBlockState(lamp,Blocks.AIR.defaultBlockState(),0);sky.fill(0);block.fill(0);
		if(!Arrays.deepEquals(before,LodCaptures.pack(LodChunks.summarize(frozen.snapshot())).planes())
			|| frozen.lighting().block().applyAsInt(lamp)!=14)
			throw new AssertionError("Client capture retained mutable blocks or light data");
		var partial=new DataLayer(7);
		var holes=LodClientCapture.freeze(source,sides,factory,-5,26,true,true,
			(layer,pos)->layer==LightLayer.SKY && pos.y()==6?partial:null,LodCaverns.NONE,false);
		int x=source.getPos().getMinBlockX(),z=source.getPos().getMinBlockZ();
		if(holes.lighting().sky().applyAsInt(new BlockPos(x,80,z))!=7 || holes.lighting().sky().applyAsInt(new BlockPos(x,120,z))!=15)
			throw new AssertionError("Missing skylight sections no longer inherit the next section's bottom row");
		checkClientEdges(source,factory);
		checkBorrowedSections(frozen);
		checkFrozenBlocks(factory);
		System.out.println("PASS: detached client snapshots match all nine packed planes in Overworld/caverns/End; copied light/blocks survive mutation and unload");
	}

	private static volatile LodChunks.Snapshot retainedSnapshot;
	private static void checkFrozenBlocks(PalettedContainerFactory factory) {
		for (int minY : new int[]{-64, 0}) {
			var chunk = new ProtoChunk(new ChunkPos(-26,-21), UpgradeData.EMPTY,
				LevelHeightAccessor.create(minY, minY == 0 ? 256 : 384), factory, null);
			int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
			for (int y = minY; y <= chunk.getMaxY(); y += 7) {
				chunk.setBlockState(new BlockPos(x0+15,y,z0), Blocks.STONE.defaultBlockState(), 0);
				chunk.setBlockState(new BlockPos(x0,y,z0+15), Blocks.END_ROD.defaultBlockState(), 0);
			}
			// A section containing only cave air must still return ordinary AIR, as ProtoChunk does.
			var air = new LevelChunkSection(factory);
			air.setBlockState(0,0,0,Blocks.CAVE_AIR.defaultBlockState());
			chunk.getSections()[2] = air;
			var view = new LodChunks.FrozenBlocks(new LodClientCapture.Frozen(chunk,null,LodCaverns.NONE,false));
			for (int y = minY-17; y <= chunk.getMaxY()+17; y++)
				for (int x : new int[]{-17,-1,0,15,16,31}) for (int z : new int[]{-17,-1,0,15,16,31}) {
					var pos = new BlockPos(x0+x,y,z0+z);
					if (view.get(pos) != chunk.getBlockState(pos))
						throw new AssertionError("Frozen lookup differs from ProtoChunk at " + pos);
				}
		}
		System.out.println("PASS: frozen lookups match actual ProtoChunk across negative coordinates, section/height boundaries, empty sections and cave air");
	}

	private static void checkBorrowedSections(LodClientCapture.Frozen frozen) {
		var borrowed=frozen.snapshot();
		var copied=LodChunks.snapshot(frozen.chunk(),frozen.lighting(),frozen.cavernY(),frozen.islands());
		int count=0;
		for(int i=0;i<borrowed.sections().length;i++) if(borrowed.sections()[i]!=null) {
			if(borrowed.sections()[i]!=frozen.chunk().getSection(borrowed.secFirst()+i).getStates()
				|| copied.sections()[i]==borrowed.sections()[i]) throw new AssertionError("Frozen/live palette ownership differs from contract");
			count++;
		}
		if(count==0 || !Arrays.deepEquals(LodCaptures.pack(LodChunks.summarize(borrowed)).planes(),LodCaptures.pack(LodChunks.summarize(copied)).planes()))
			throw new AssertionError("Borrowed interval palettes change packed terrain");
		for(int i=0;i<16;i++) {
			retainedSnapshot=LodChunks.snapshot(frozen.chunk(),frozen.lighting(),frozen.cavernY(),frozen.islands());
			retainedSnapshot=frozen.snapshot();
		}
		var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
		long thread=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(thread);
		for(int i=0;i<64;i++) retainedSnapshot=LodChunks.snapshot(frozen.chunk(),frozen.lighting(),frozen.cavernY(),frozen.islands());
		long baseline=bean.getThreadAllocatedBytes(thread)-start;start=bean.getThreadAllocatedBytes(thread);
		for(int i=0;i<64;i++) retainedSnapshot=frozen.snapshot();
		long after=bean.getThreadAllocatedBytes(thread)-start;
		System.out.printf("PASS: %d detached palettes reused; nine planes identical; snapshot allocated %.1f -> %.1f KiB/call in fixture%n",count,baseline/65536.0,after/65536.0);
	}

	private static void checkClientEdges(ProtoChunk source,PalettedContainerFactory factory) {
		var sky=new DataLayer();var block=new DataLayer();
		for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
			sky.set(x,y,z,(x+3*y+5*z)&15);block.set(x,y,z,(3*x+5*y+z)&15);
		}
		ChunkAccess[] sides=new ChunkAccess[4];BlockPos[] lamps=new BlockPos[4];
		int cx=source.getPos().x(),cz=source.getPos().z();
		for(int d=0;d<4;d++) {
			int nx=cx+(d==0?1:d==1?-1:0),nz=cz+(d==2?1:d==3?-1:0);
			var side=new ProtoChunk(new ChunkPos(nx,nz),UpgradeData.EMPTY,LevelHeightAccessor.create(-64,384),factory,null);
			int lx=d<2?d==0?0:15:3,lz=d>=2?d==2?0:15:3;
			lamps[d]=new BlockPos(nx*16+lx,70,nz*16+lz);side.setBlockState(lamps[d],Blocks.END_ROD.defaultBlockState(),0);sides[d]=side;
		}
		var frozen=LodClientCapture.freeze(source,sides,factory,-5,26,true,true,(layer,pos)->layer==LightLayer.SKY?sky:block,LodCaverns.NONE,false);
		for(int d=0;d<4;d++) for(int y:new int[]{-96,-80,-64,0,70,319,320,335,336}) for(int along=0;along<16;along++) {
			int lx=d<2?d==0?0:15:along,lz=d>=2?d==2?0:15:along;
			var pos=new BlockPos(sides[d].getPos().getMinBlockX()+lx,y,sides[d].getPos().getMinBlockZ()+lz);
			int expectedSky=y>=336?15:sky.get(lx,y< -80?0:y&15,lz);
			int expectedBlock=y< -80||y>=336?0:Math.max(block.get(lx,y&15,lz),pos.equals(lamps[d])?14:0);
			if(frozen.lighting().sky().applyAsInt(pos)!=expectedSky || frozen.lighting().block().applyAsInt(pos)!=expectedBlock)
				throw new AssertionError("Boundary light/emission changed on side "+d+" at "+pos);
		}
		sky.fill(0);block.fill(0);
		for(var lamp:lamps) if(frozen.lighting().block().applyAsInt(lamp)<14) throw new AssertionError("Boundary emitter lost after source mutation");
		System.out.println("PASS: four detached boundary strips preserve heterogeneous sky/block light, emitters and out-of-height lookups");
	}

	private static void checkPrepared(LodChunks.Snapshot snapshot) throws Exception {
		Thread producer=Thread.currentThread();
		var completed=new java.util.concurrent.CompletableFuture<LodCaptures.Prepared>();
		try (var store=new LodCaptures(java.nio.file.Path.of("unused-prepared-check"),5,false)) {
			store.capturePrepared(snapshot,prepared->{
				if (Thread.currentThread()==producer) completed.completeExceptionally(new AssertionError("Packing on producer"));
				else completed.complete(prepared);
			});
			var prepared=completed.get(5,java.util.concurrent.TimeUnit.SECONDS);
			var get=LodCaptures.class.getDeclaredMethod("get",long.class);get.setAccessible(true);
			if(get.invoke(store,LodForest.chunkKey(snapshot.chunkX(),snapshot.chunkZ()))!=prepared.capture())
				throw new AssertionError("Persistence and completion do not share packed data");
			var field=LodCaptures.Capture.class.getDeclaredField("mips");field.setAccessible(true);
			var mips=(int[][][])field.get(prepared.capture());
			var reference=LodCaptures.pack(LodChunks.summarize(snapshot));
			if(!Arrays.deepEquals(prepared.capture().planes(),reference.planes())) throw new AssertionError("Prepared fine data differs");
			for(int level=1;level<5;level++) {
				if(mips[level]==null || !Arrays.deepEquals(mips[level],reference.atLevel(level))) throw new AssertionError("Worker omitted/damaged coarse planes");
				for(int repeat=0;repeat<1000;repeat++) if(prepared.capture().atLevel(level)!=mips[level]) throw new AssertionError("Render consumer recomputed mip");
			}
		}
		System.out.println("PASS: worker prepares all nine planes and four reductions once; persistence and live completion share the same immutable capture");
	}

	private static void checkDeferredQueue(LodChunks.Snapshot snapshot) throws Exception {
		var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
		var callbacks=new java.util.concurrent.atomic.AtomicInteger();var stale=new java.util.concurrent.atomic.AtomicBoolean();
		Thread caller=Thread.currentThread();
		var store=new LodCaptures(java.nio.file.Path.of("unused-client-capture-check"),5,false);
		try {
			store.captureDeferred(LodForest.chunkKey(0,0),()->{
				entered.countDown();try {release.await();} catch(InterruptedException e){throw new RuntimeException(e);}
				return at(snapshot,0);
			},(s,v)->stale.set(true));
			if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Capture worker did not start");
			for(int x=1;x<64;x++) {int cx=x;
				if(!store.captureDeferred(LodForest.chunkKey(cx,0),()->{
					if(Thread.currentThread()==caller) throw new AssertionError("Column scan ran on producer");return at(snapshot,cx);
				},(s,v)->callbacks.incrementAndGet())) throw new AssertionError("Raw queue filled too early");
			}
			if(store.captureDeferred(LodForest.chunkKey(64,0),()->{throw new AssertionError("Rejected raw capture evaluated");},(s,v)->{}))
				throw new AssertionError("Raw capture queue is unbounded");
			if(!store.captureDeferred(LodForest.chunkKey(0,0),()->at(snapshot,0),(s,v)->callbacks.incrementAndGet()))
				throw new AssertionError("Full raw queue cannot coalesce the latest edit");
		} finally {release.countDown();store.close();}
		if(stale.get() || callbacks.get()!=64) throw new AssertionError("Deferred captures lost an accepted request or published superseded data");
		System.out.println("PASS: raw capture queue bounded at 64, producer never scans columns, latest edit wins and close drains accepted work");
	}

	private static void checkPipeline(LodChunks.Snapshot snapshot) throws Exception {
		var root=java.nio.file.Files.createTempDirectory("mcopt-capture-pipeline-");
		var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
		var reference=new java.util.concurrent.atomic.AtomicReference<LodCaptures>();
		var blocked=new java.util.concurrent.atomic.AtomicBoolean();var callbacks=new java.util.concurrent.atomic.AtomicInteger();
		var stale=new java.util.concurrent.atomic.AtomicBoolean();Thread producer=Thread.currentThread();
		var write=LodCaptures.class.getDeclaredMethod("write",LodCaptures.Capture.class);write.setAccessible(true);
		var get=LodCaptures.class.getDeclaredMethod("get",long.class);get.setAccessible(true);
		var store=new LodCaptures(root,5,true,1,data->{
			if(blocked.compareAndSet(false,true)) {
				entered.countDown();try {release.await();} catch(InterruptedException e){throw new java.io.IOException(e);}
			}
			try {write.invoke(reference.get(),data);} catch(ReflectiveOperationException e){throw new java.io.IOException(e);}
		});reference.set(store);
		try {
			store.put(new LodCaptures.Capture(0,0,new int[LodCaptures.PLANES][256]));
			if(!entered.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Disk writer did not start");
			int[][] planes=new int[LodCaptures.PLANES][256];Arrays.fill(planes[LodCaptures.GEOM],LodClip.geomWord(91,false));
			var latest=new LodCaptures.Capture(0,0,planes);store.put(latest);
			await(()->{try{return get.invoke(store,0L)==latest;}catch(Exception e){throw new AssertionError(e);}},"New edit could not publish while old disk write blocked");
			for(int x=1;x<128;x++) store.put(new LodCaptures.Capture(x,0,new int[LodCaptures.PLANES][256]));
			await(()->store.writePendingCount()==LodCaptures.WRITE_LIMIT,"Packed disk stage did not fill independently");
			if(get.invoke(store,0L)!=latest || store.diskReadCount()!=0) throw new AssertionError("Eviction reopened an obsolete/unwritten file");
			store.put(new LodCaptures.Capture(128,0,new int[LodCaptures.PLANES][256])); // Worker now waits for a disk slot.
			for(int x=1000;x<1064;x++) {int cx=x;
				if(!store.captureDeferred(LodForest.chunkKey(cx,0),()->{
					if(Thread.currentThread()==producer) throw new AssertionError("Producer scanned raw capture");return at(snapshot,cx);
				},(s,v)->{if(cx==1000)stale.set(true);else callbacks.incrementAndGet();}))
					throw new AssertionError("Packed writes consumed a raw capture slot");
			}
			if(store.captureDeferred(LodForest.chunkKey(1064,0),()->{throw new AssertionError("Rejected supplier evaluated");},(s,v)->{}))
				throw new AssertionError("Raw stage exceeded bound");
			if(!store.captureDeferred(LodForest.chunkKey(1000,0),()->at(snapshot,1000),(s,v)->callbacks.incrementAndGet()))
				throw new AssertionError("Full stages cannot coalesce latest raw edit");
			if(store.rawPendingCount()!=64 || store.writePendingCount()!=128) throw new AssertionError("Pipeline bounds changed under saturation");
			if(Boolean.getBoolean("mcopt.metal.jfr")) checkCaptureQueueTelemetry(store,root);
			var closeFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
			var closer=new Thread(()->{
				try {store.close();if(!Thread.currentThread().isInterrupted())throw new AssertionError("Close swallowed interruption");}
				catch(Throwable e){closeFailure.set(e);}
			});closer.start();closer.interrupt();release.countDown();closer.join(15_000);
			if(closer.isAlive() || closeFailure.get()!=null) throw new AssertionError("Close did not drain both stages",closeFailure.get());
			if(stale.get() || callbacks.get()!=64 || store.rawPendingCount()!=0 || store.writePendingCount()!=0)
				throw new AssertionError("Pipeline lost or resurrected a capture");
			try(var reopened=new LodCaptures(root,5,true,1)) {
				reopened.awaitReady();var saved=(LodCaptures.Capture)get.invoke(reopened,0L);
				if(saved==null || !Arrays.deepEquals(saved.planes(),latest.planes())) throw new AssertionError("Old in-flight write overwrote newer edit");
				for(int x=1000;x<1064;x++) if(get.invoke(reopened,LodForest.chunkKey(x,0))==null) throw new AssertionError("Close lost raw capture "+x);
			}
			checkPublicationDuringRead(root.resolve("read-race"));
			System.out.println("PASS: blocked real disk writer leaves capture processing active; 128 packed/64 raw bounds, coalescing, eviction freshness, interrupted close and restart preserve latest edits");
		} finally {
			release.countDown();store.close();
			try(var paths=java.nio.file.Files.walk(root)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
		}
	}
	private static void checkPublicationDuringRead(java.nio.file.Path root) throws Exception {
		try(var seed=new LodCaptures(root,5,true,1)) {seed.put(new LodCaptures.Capture(0,0,new int[LodCaptures.PLANES][256]));}
		var atRevision=new java.util.concurrent.CountDownLatch(1);var allowRead=new java.util.concurrent.CountDownLatch(1);
		var writing=new java.util.concurrent.CountDownLatch(1);var allowWrite=new java.util.concurrent.CountDownLatch(1);
		var readerThread=new java.util.concurrent.atomic.AtomicReference<Thread>();var gated=new java.util.concurrent.atomic.AtomicBoolean();
		var owner=new java.util.concurrent.atomic.AtomicReference<LodCaptures>();
		var write=LodCaptures.class.getDeclaredMethod("write",LodCaptures.Capture.class);write.setAccessible(true);
		var get=LodCaptures.class.getDeclaredMethod("get",long.class);get.setAccessible(true);
		var store=new LodCaptures(root,5,true,1,data->{
			writing.countDown();try {allowWrite.await();write.invoke(owner.get(),data);}catch(Exception e){throw new java.io.IOException(e);}
		});owner.set(store);store.awaitReady();
		var revisions=new java.util.concurrent.ConcurrentHashMap<Long,Long>() {
			@Override public Long get(Object key) {
				if(Thread.currentThread()==readerThread.get() && gated.compareAndSet(false,true)) {
					atRevision.countDown();try {allowRead.await();}catch(InterruptedException e){throw new AssertionError(e);}
				}
				return super.get(key);
			}
		};
		var revisionField=LodCaptures.class.getDeclaredField("revisions");revisionField.setAccessible(true);revisionField.set(store,revisions);
		var result=new java.util.concurrent.atomic.AtomicReference<LodCaptures.Capture>();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
		var reader=new Thread(()->{try {result.set((LodCaptures.Capture)get.invoke(store,0L));}catch(Throwable e){failure.set(e);}});
		readerThread.set(reader);
		try {
			reader.start();if(!atRevision.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Read did not reach revision barrier");
			int[][] p=new int[LodCaptures.PLANES][256];Arrays.fill(p[0],LodClip.geomWord(97,false));var latest=new LodCaptures.Capture(0,0,p);
			store.put(latest);if(!writing.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("New capture did not publish");
			store.put(new LodCaptures.Capture(1,0,new int[LodCaptures.PLANES][256]));
			await(()->store.writePendingCount()==2,"New edit was not evicted while its write was pending");
			allowRead.countDown();reader.join(5000);
			if(reader.isAlive() || failure.get()!=null || result.get()!=latest || store.diskReadCount()!=0)
				throw new AssertionError("Read reinserted old file after a concurrent publication",failure.get());
			System.out.println("PASS: publication and eviction at the revision-read barrier cannot reinsert an older disk capture");
		} finally {allowRead.countDown();allowWrite.countDown();reader.join(5000);store.close();}
	}
	private static void checkCaptureQueueTelemetry(LodCaptures store,java.nio.file.Path root) throws Exception {
		var path=java.nio.file.Path.of(System.getProperty("mcopt.check.captureQueueOutput",root.resolve("queue.jfr").toString()));
		java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
		try(var recording=new jdk.jfr.Recording()) {
			recording.enable("mcopt.LodCaptureQueue");recording.start();long now=System.nanoTime();
			long next=LodCaptureQueue.sample(now,0,store,123,456,7,42,17);
			LodCaptureQueue.sample(now+1,next,store,999,999,999,999,999);
			recording.stop();recording.dump(path);
		}
		var events=jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream().filter(e->e.getEventType().getName().equals("mcopt.LodCaptureQueue")).toList();
		if(events.size()!=1 || events.getFirst().getInt("rawQueued")!=64 || events.getFirst().getInt("writesQueued")!=128
			|| events.getFirst().getLong("snapshots")!=123 || events.getFirst().getLong("snapshotNanos")!=456 || events.getFirst().getLong("fallbacks")!=7
			|| events.getFirst().getLong("reusedUnloads")!=42 || events.getFirst().getLong("reusedEdits")!=17)
			throw new AssertionError("Capture pipeline telemetry missing, unthrottled or incorrect");
		System.out.println("PASS: throttled JFR capture queue event records raw/write backlog, copy counters and fallbacks");
	}
	private static void await(java.util.function.BooleanSupplier condition,String message) throws Exception {
		long until=System.nanoTime()+5_000_000_000L;
		while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError(message);Thread.sleep(1);}
	}

	private static LodChunks.Snapshot at(LodChunks.Snapshot source,int x) {
		try {
			var components=LodChunks.Snapshot.class.getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
			for(int i=0;i<values.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(source);}
			values[0]=x;values[1]=0;return LodChunks.Snapshot.class.getDeclaredConstructor(types).newInstance(values);
		} catch(ReflectiveOperationException e){throw new AssertionError(e);}
	}

	private static void set(List<SerializableChunkData.SectionData> sections,int x,int y,int z,Block b) {
		sections.get((y>>4)+4).chunkSection().setBlockState(x,y&15,z,b.defaultBlockState());
	}
}
