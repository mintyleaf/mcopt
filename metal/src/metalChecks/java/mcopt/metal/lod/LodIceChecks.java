package mcopt.metal.lod;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

public final class LodIceChecks {
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		var registries = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
		var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.FROZEN_OCEAN);
		var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
		var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), settings);
		var field = ChunkGenerator.class.getDeclaredField("featuresPerStep"); field.setAccessible(true);
		var steps = ((Supplier<List<FeatureSorter.StepFeatureData>>) field.get(generator)).get();
		List<LodForest.IceFeature> features = new ArrayList<>();
		for (String name : List.of("iceberg_packed", "iceberg_blue")) {
			PlacedFeature feature = registries.lookupOrThrow(Registries.PLACED_FEATURE)
				.getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.withDefaultNamespace(name))).value();
			int stage = GenerationStep.Decoration.LOCAL_MODIFICATIONS.ordinal();
			int index = steps.get(stage).indexMapping().applyAsInt(feature);
			if (!LodForest.iceFeature(name) || index < 0) throw new AssertionError("Real iceberg decoration stage was skipped");
			features.add(new LodForest.IceFeature(stage,index,feature));
		}
		if (!LodForest.iceFeature("ice_spike") || LodForest.iceFeature("amethyst_geode") || LodForest.iceFeature("ore_ice"))
			throw new AssertionError("Raised-ice selection includes underground features or misses spikes");
		Map<Long,BlockState> placed = new HashMap<>();
		WorldGenLevel world = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(p,m,a)->{
			switch(m.getName()) {
				case "getMinY": return -64;
				case "getHeight": return a==null||a.length==0?384:63;
				case "getBiome": return biome;
				case "getBlockState": {
					BlockPos pos=(BlockPos)a[0]; BlockState value=placed.get(pos.asLong());
					return value!=null?value:pos.getY()<40?Blocks.STONE.defaultBlockState():pos.getY()<63?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
				}
				case "setBlock": placed.put(((BlockPos)a[0]).asLong(),(BlockState)a[1]); return true;
				case "ensureCanWrite": return true;
				case "setCurrentlyGenerating": return null;
				default: if(m.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(p,m,a);throw new AssertionError("Unexpected iceberg world access: "+m);
			}
		});
		long seed=-7640742383989886661L, start=System.nanoTime();
		List<int[]> icebergs=new ArrayList<>(); int highest=0, iceChunks=0;
		for (int z=-4;z<4;z++) for (int x=-4;x<4;x++) {
			placed.clear(); LodForest.placeIce(world,generator,seed,x,z,features);
			int[] columns=LodForest.iceTops(placed,63);
			if (columns.length==0) continue;
			iceChunks++;icebergs.add(columns);
			for (int k=0;k<columns.length;k+=5) highest=Math.max(highest,columns[k+2]);
			Map<Long,BlockState> expected=new HashMap<>(placed);
			placed.clear();LodForest.placeIce(world,generator,seed,x,z,features);
			if (!expected.equals(placed)) throw new AssertionError("Iceberg placement seed changes across tile/reload order");
		}
		if (iceChunks==0 || highest<70) throw new AssertionError("Icebergs still contain only a flat frozen-water base");
		for (int level=0;level<5;level++) {
			int size=128;
			LodTile tile=new LodTile(level,-64,-64,size);
			Arrays.fill(tile.height,(short)40);Arrays.fill(tile.ground,(short)40);Arrays.fill(tile.water,(short)63);
			Arrays.fill(tile.biome,biome);Arrays.fill(tile.state,Blocks.STONE.defaultBlockState());
			for (int[] columns:icebergs) LodForest.applyIce(tile,columns);
			int max=Arrays.stream(toInts(tile.height)).max().orElseThrow();
			if (max!=highest) throw new AssertionError("Coarse LOD level "+level+" loses narrow iceberg peaks");
			int[] g=new int[tile.cells()],c=g.clone(),cr=g.clone(),runs=g.clone();
			LodField.pack(tile,g,c,cr,runs,null);
			for (int i=0;i<g.length;i++) if (tile.height[i]>63 && ((g[i]&4095)-512!=tile.height[i] || (g[i]&(LodClip.GEOM_WET|LodClip.GEOM_CROWN))!=0))
				throw new AssertionError("Iceberg becomes flat water or a floating tree crown");
		}
		placed.clear();placed.put(BlockPos.asLong(-1,80,-1),Blocks.PACKED_ICE.defaultBlockState());
		placed.put(BlockPos.asLong(-1,81,-1),Blocks.AIR.defaultBlockState()); // Carved-away historical top.
		placed.put(BlockPos.asLong(-1,79,-1),Blocks.BLUE_ICE.defaultBlockState());
		int[] carved=LodForest.iceTops(placed,63);
		if (carved.length!=5 || carved[2]!=81 || carved[4]!=Block.getId(Blocks.BLUE_ICE.defaultBlockState()))
			throw new AssertionError("Carved void or snow cap becomes the whole iceberg's wall material");
		int base=LodClip.geomWord(63,true),icy=123;
		if (!LodForest.generatedIceBase(base,icy,0,icy) || LodForest.generatedIceBase(base|LodClip.GEOM_CLEAR,icy,0,icy)
			|| LodForest.generatedIceBase(base,icy,LodClip.TEX_LIGHT,icy) || LodForest.generatedIceBase(LodClip.geomWord(80,false),icy,0,icy))
			throw new AssertionError("Ice-cache repair overwrites captured water, lighting or builds");
		var random=RandomState.create(registries.lookupOrThrow(Registries.NOISE),seed,settings.value());
		var system=random.surfaceSystem();
		var extension=system.getClass().getDeclaredMethod("frozenOceanExtension",int.class,Biome.class,net.minecraft.world.level.chunk.BlockColumn.class,BlockPos.MutableBlockPos.class,int.class,int.class,int.class);
		var depth=system.getClass().getDeclaredMethod("getSurfaceDepth",int.class,int.class);extension.setAccessible(true);depth.setAccessible(true);
		var column=new LodFrozenOcean.Column();var pos=new BlockPos.MutableBlockPos();int largePeak=0,bestX=0,bestZ=0;
		for(int z=-128;z<128;z+=8)for(int x=-128;x<128;x+=8) {
			column.reset(40,63,Blocks.STONE.defaultBlockState());LodFrozenOcean.sample(system,biome.value(),column,pos,x,z);
			BlockState[] blocks=new BlockState[384];
			for(int y=-64;y<320;y++)blocks[y+64]=y<40?Blocks.STONE.defaultBlockState():y<63?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
			extension.invoke(system,39+(int)depth.invoke(system,x,z)-8,biome.value(),new net.minecraft.world.level.NoiseColumn(-64,blocks),pos,x,z,63);
			int actual=Integer.MIN_VALUE;BlockState cap=null;
			for(int y=319;y>=63;y--)if(blocks[y+64].is(Blocks.PACKED_ICE)||blocks[y+64].is(Blocks.SNOW_BLOCK)){actual=y;cap=blocks[y+64];break;}
			if(actual!=Integer.MIN_VALUE && (column.top!=actual||column.cap!=cap) || actual==Integer.MIN_VALUE&&column.top>=63)
				throw new AssertionError("Bounded frozen-ocean column differs from the game's complete block-column extension");
			if(column.top+1>largePeak){largePeak=column.top+1;bestX=x;bestZ=z;}
			column.reset(62,63,Blocks.STONE.defaultBlockState());LodFrozenOcean.sample(system,biome.value(),column,pos,x,z);
			if(actual>=63 && column.top!=actual)throw new AssertionError("Legacy-cache repair needs full seabed to recover the visible iceberg crest");
		}
		if(largePeak<85)throw new AssertionError("Frozen-ocean surface pillars still missing; only small placed icebergs remain");
		for(int level=0;level<5;level++){
			int span=64<<level,x0=Math.floorDiv(bestX,span)*span,z0=Math.floorDiv(bestZ,span)*span;
			LodTile tile=new LodTile(level,x0,z0,64);
			Arrays.fill(tile.height,(short)40);Arrays.fill(tile.ground,(short)40);Arrays.fill(tile.water,(short)63);
			Arrays.fill(tile.biome,biome);Arrays.fill(tile.state,Blocks.STONE.defaultBlockState());
			LodFrozenOcean.apply(tile,system);
			int i=((bestZ-z0)>>level)*64+((bestX-x0)>>level);
			if(tile.height[i]<largePeak || tile.water[i]!=LodTile.DRY || !(tile.state[i].is(Blocks.PACKED_ICE)||tile.state[i].is(Blocks.SNOW_BLOCK)))
				throw new AssertionError("Frozen-ocean surface peak disappears in LOD tile level "+level);
		}
		System.out.println("PASS: 1024 frozen-ocean columns match vanilla's full surface extension; large peak y"+largePeak);
		System.out.printf("PASS: %d seeded vanilla icebergs, peak y%d, all five LOD scales, carved shapes and captured-cache guards (%.0f ms)%n",iceChunks,highest,(System.nanoTime()-start)/1e6);
	}
	private static int[] toInts(short[] values) { int[] out=new int[values.length];for(int i=0;i<out.length;i++)out[i]=values[i];return out; }
}
