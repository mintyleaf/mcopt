package mcopt.metal.lod;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.EndIslandFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;

/** Replay only vanilla's small End-island feature, with its actual seeds, placement and biome filters. No world chunks. */
final class LodEndIslets {
	private static final int[] EMPTY=new int[0];
	private final LodNoise noise;
	private final List<LodForest.IceFeature> features=new ArrayList<>();
	private final ConcurrentHashMap<Long,int[]> cache=new ConcurrentHashMap<>();
	private final ThreadLocal<Sample> samples;
	LodEndIslets(LodNoise noise) {
		this.noise=noise;
		var generator=noise.level.getChunkSource().getGenerator();
		var steps=LodForest.steps(generator);
		for(int s=0;s<steps.size();s++) for(int i=0;i<steps.get(s).features().size();i++) {
			var feature=steps.get(s).features().get(i);
			if(feature.feature().value() instanceof EndIslandFeature) features.add(new LodForest.IceFeature(s,i,feature));
		}
		this.samples=ThreadLocal.withInitial(Sample::new);
	}

	void apply(LodTile tile) {
		if(features.isEmpty() || tile.islandBottom==null) return;
		int cell=tile.cell(),endX=tile.x0+tile.size*cell,endZ=tile.z0+tile.size*cell;
		// Features reach seven blocks beyond their placement chunk. Never omit a neighbor at a tile boundary.
		for(int cz=Math.floorDiv(tile.z0-7,16);cz<=Math.floorDiv(endZ+6,16);cz++)
			for(int cx=Math.floorDiv(tile.x0-7,16);cx<=Math.floorDiv(endX+6,16);cx++) {
				int[] columns=columns(cx,cz);
				for(int k=0;k<columns.length;k+=4) {
					int x=columns[k],z=columns[k+1];
					if(x<tile.x0 || x>=endX || z<tile.z0 || z>=endZ) continue;
					int i=((z-tile.z0)/cell)*tile.size+(x-tile.x0)/cell;
					int bottom=columns[k+2],top=columns[k+3];
					if(tile.height[i]==LodIslands.EMPTY) {tile.height[i]=(short)top;tile.islandBottom[i]=(short)bottom;}
					else if(tile.islandBottom[i]>=40 && tile.height[i]<=71) {
						tile.height[i]=(short)Math.max(tile.height[i],top);tile.islandBottom[i]=(short)Math.min(tile.islandBottom[i],bottom);
					}
				}
			}
	}

	int[] columns(int cx,int cz) {
		long key=LodForest.chunkKey(cx,cz);int[] cached=cache.get(key);if(cached!=null) return cached;
		Sample sample=samples.get();sample.columns.clear();
		var level=noise.level;var generator=level.getChunkSource().getGenerator();
		var random=sample.random;
		long decoration=random.setDecorationSeed(level.getSeed(),cx*16,cz*16);
		var placer=new FeaturePlacer(sample.proxy,generator);
		for(var feature:features) {
			random.setFeatureSeed(decoration,feature.index(),feature.stage());
			// Most source chunks fail vanilla's first rarity check. Avoid constructing the placement stream for them.
			if(!feature.feature().placement().isEmpty() && feature.feature().placement().getFirst() instanceof net.minecraft.world.level.levelgen.placement.RarityFilter rarity) {
				if(random.nextFloat()>=1.0F/rarity.chance()) continue;
				random.setFeatureSeed(decoration,feature.index(),feature.stage());
			}
			placer.placeWithBiomeCheck(feature.feature(),random,new BlockPos(cx*16,level.getMinY(),cz*16));
		}
		int[] result=sample.columns.isEmpty()?EMPTY:new int[sample.columns.size()*4];int i=0;
		for(int[] column:sample.columns.values()) for(int v:column) result[i++]=v;
		if(cache.size()>=512) cache.clear();cache.put(key,result);return result;
	}

	private final class Sample implements InvocationHandler {
		final Map<Long,int[]> columns=new HashMap<>();
		final WorldgenRandom random=new WorldgenRandom(new XoroshiroRandomSource(0));
		final BiomeManager biomes=new BiomeManager(noise.resolver(),BiomeManager.obfuscateSeed(noise.level.getSeed()));
		final WorldGenLevel proxy=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
		@Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
			switch(method.getName()) {
				case "setBlock": {
					BlockPos p=(BlockPos)args[0];
					if(!((net.minecraft.world.level.block.state.BlockState)args[1]).is(Blocks.END_STONE)) throw new IllegalStateException("Unexpected End-island material");
					long key=BlockPos.asLong(p.getX(),0,p.getZ());int[] col=columns.get(key);
					if(col==null) columns.put(key,new int[]{p.getX(),p.getZ(),p.getY(),p.getY()+1});
					else {col[2]=Math.min(col[2],p.getY());col[3]=Math.max(col[3],p.getY()+1);}
					return true;
				}
				case "getBiome": return biomes.getBiome((BlockPos)args[0]);
				case "getMinY": return noise.level.getMinY();
				case "getHeight": return noise.level.getHeight();
				case "getLevel": return noise.level;
				case "getSeed": return noise.level.getSeed();
				case "registryAccess": return noise.level.registryAccess();
				case "ensureCanWrite": return true;
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy==args[0];
				case "toString": return "mcopt small End-island scratch";
			}
			if(method.isDefault()) return InvocationHandler.invokeDefault(proxy,method,args);
			throw new UnsupportedOperationException("End-island scratch: "+method);
		}
	}
}
