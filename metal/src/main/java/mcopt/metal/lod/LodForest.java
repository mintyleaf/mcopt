package mcopt.metal.lod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The game's own trees on terrain that was never generated: for a chunk, the vegetal decoration's tree features run exactly
 * as ChunkGenerator.applyBiomeDecoration runs them (the same decoration and feature seeds, the same placement modifiers and
 * tree shapes), against a stand-in level whose ground is the far terrain's own block-exact surface (LodNoise at level 0) and
 * which records every block the trees set. This predicts unvisited forests; matching feature seeds alone does not make
 * the result identical to a fully populated chunk.
 *
 * Each feature has a seed of its own (decoration seed, its index in the step, the step), so running only the tree features
 * leaves their randomness exactly the game's; within a feature the trees are placed in the game's order, which matters (each
 * consumes randomness). What differs from the real chunk: the ground ignores caves breaking the surface and earlier features
 * (lakes, rocks, structures), and trees of neighboring chunks don't see each other. Per chunk the result is cached (tiles
 * share border chunks); a tile asks for its own chunks and the ring around them (trees reach across chunk edges).
 * Captured real columns override these estimates when a tile is installed, including after eviction and disk reload.
 */
final class LodForest {
	/** A chunk's tree blocks: x, y, z (absolute) and the block state id, four ints each. */
	record Trees(int[] blocks) {
	}

	/** A chunk's ground, block exact: top face y, wet (sea over it), the surface block, the biome per column. */
	record Ground(short[] height, boolean[] wet, BlockState[] state) {
	}

	private static final int VEG = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
	private static final int CACHE_MAX = 40000;
	private static final int GROUND_CACHE = 0, TREE_CACHE = 1, ICE_CACHE = 2;
	private static final long GROUND_BYTES = 256L * (2 + 1 + 8) + 256;
	private final LodPredictionCache cache = new LodPredictionCache(
		Math.max(1, Integer.getInteger("mcopt.lod.forestCacheMiB", 64)) * 1048576L, CACHE_MAX);
	private final LodNoise noise;
	private final ServerLevel level;
	private final ChunkGenerator generator;
	private final long seed;
	private final long biomeSeed;
	private final FeatureSorter.@Nullable StepFeatureData step;
	record IceFeature(int stage, int index, PlacedFeature feature) { }
	private final List<IceFeature> iceFeatures;
	private final ConcurrentHashMap<Holder<Biome>, List<IceFeature>> iceBiomes = new ConcurrentHashMap<>();
	/** Iceberg features need a scratch volume while carving, but only their exposed columns are retained. Five ints each:
	 * world x, world z, top face y, top state, wall state. Keep this cache much smaller than the tree/ground caches. */
	/** Per global feature index of the vegetal step: whether it grows trees (big plants seen from afar). */
	private final boolean[] tree;
	/** Per global feature index: whether it grows plants (grass, ferns, flowers, bushes, cane, cacti, pumpkins). */
	private final boolean[] plants;
	/** In a chunk's block list: the block was set by a tree feature (else by a plant feature). */
	static final int FROM_TREE = 1 << 30;
	private final ThreadLocal<Stand> stands;
	final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong(), runNanos = new java.util.concurrent.atomic.AtomicLong(),
		failures = new java.util.concurrent.atomic.AtomicLong();

	LodForest(LodNoise noise) {
		this.noise = noise;
		this.level = noise.level;
		this.generator = this.level.getChunkSource().getGenerator();
		this.seed = this.level.getSeed();
		this.biomeSeed = BiomeManager.obfuscateSeed(this.seed);
		this.step = stepData(this.generator);
		this.iceFeatures = new ArrayList<>();
		var placedRegistry = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
		List<FeatureSorter.StepFeatureData> allSteps = steps(this.generator);
		for (int stage = 0; stage < allSteps.size(); stage++) {
			var features = allSteps.get(stage).features();
			for (int index = 0; index < features.size(); index++) {
				Identifier id = placedRegistry.getKey(features.get(index));
				if (id != null && iceFeature(id.getPath())) this.iceFeatures.add(new IceFeature(stage, index, features.get(index)));
			}
		}
		boolean[] t = new boolean[0], p = new boolean[0];
		if (this.step != null) {
			var registry = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
			t = new boolean[this.step.features().size()];
			p = new boolean[t.length];
			int count = 0;
			List<String> plantIds = new ArrayList<>();
			for (int i = 0; i < t.length; i++) {
				Identifier id = registry.getKey(this.step.features().get(i));
				t[i] = id != null && isTree(id.getPath());
				p[i] = LodConfig.PLANTS && id != null && !t[i] && isPlants(id.getPath());
				if (t[i]) count++;
				if (p[i]) plantIds.add(id.getPath());
			}
			System.out.println("mcopt-lod: trees from " + count + " of the game's " + t.length + " vegetal features, plants from " + plantIds.size() + ": " + plantIds);
		}
		this.tree = t;
		this.plants = p;
		this.stands = ThreadLocal.withInitial(Stand::new);
	}

	static boolean isTree(String path) {
		return path.startsWith("trees_") || path.equals("birch_tall") || path.equals("dark_forest_vegetation") || path.equals("pale_garden_vegetation")
			|| path.equals("mushroom_island_vegetation") || path.equals("bamboo_vegetation") || path.equals("chorus_plant");
	}

	/** Features that grow small plants on the surface (not under water, not in caves, not on trees). */
	private static boolean isPlants(String path) {
		for (String no : new String[] {"sea", "kelp", "lily", "mushroom", "vine", "lichen", "spring", "bamboo", "cave", "dripleaf", "spore", "moss", "lush"}) {
			if (path.contains(no)) return false;
		}
		for (String yes : new String[] {"patch_", "flower", "grass", "fern", "bush", "cane", "cactus", "pumpkin", "melon", "berr", "leaf_litter", "petal"}) {
			if (path.contains(yes)) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	static List<FeatureSorter.StepFeatureData> steps(ChunkGenerator generator) {
		try {
			java.lang.reflect.Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
			f.setAccessible(true);
			return ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
		} catch (ReflectiveOperationException | RuntimeException e) {
			System.out.println("mcopt-lod: no feature list (" + e + "): far trees are impostors");
			return List.of();
		}
	}

	private static FeatureSorter.@Nullable StepFeatureData stepData(ChunkGenerator generator) {
		var steps = steps(generator);
		return VEG < steps.size() ? steps.get(VEG) : null;
	}

	static boolean iceFeature(String path) {
		return path.equals("iceberg_packed") || path.equals("iceberg_blue") || path.equals("ice_spike");
	}

	boolean available() {
		return LodConfig.TREES && this.step != null;
	}

	/** Raised ice is a decoration feature, not terrain density or a floating tree crown. Apply its solid silhouette on
	 * every LOD level; coarse cells retain the tallest ice column in their footprint so narrow peaks do not disappear. */
	void ice(LodTile tile) {
		boolean cold = false;
		for (Holder<Biome> biome : tile.biome) if (!this.wantedIce(biome).isEmpty()) { cold = true; break; }
		if (cold) this.iceColumns(tile.level, tile.x0, tile.z0, tile.size, tile, columns -> applyIce(tile, columns));
	}

	static void applyIce(LodTile tile, int[] columns) {
		for (int k = 0; k < columns.length; k += 5) {
			int x = columns[k] - tile.x0, z = columns[k+1] - tile.z0;
			if (x < 0 || z < 0 || x >= tile.size << tile.level || z >= tile.size << tile.level) continue;
			int i = (z >> tile.level) * tile.size + (x >> tile.level), top = columns[k+2];
			if (top <= Math.max(tile.height[i], tile.water[i])) continue;
			BlockState state = Block.stateById(columns[k+3]), side = Block.stateById(columns[k+4]);
			Biome biome = tile.biome[i].value();
			tile.height[i] = tile.ground[i] = (short) top;
			tile.water[i] = LodTile.DRY;
			tile.state[i] = state;
			tile.top[i] = LodColors.top(state, biome, columns[k], columns[k+1]);
			tile.side[i] = tile.below[i] = LodColors.side(side, biome, columns[k], columns[k+1]);
			tile.fringe[i] = false;
			if (tile.level == 0) tile.tex[i] = LodPalette.word(LodPalette.id(state), LodPalette.id(side), LodPalette.id(side));
		}
	}

	private List<IceFeature> wantedIce(Holder<Biome> biome) {
		return this.iceBiomes.computeIfAbsent(biome, b -> {
			var stages = this.generator.getBiomeGenerationSettings(b).features();
			return this.iceFeatures.stream().filter(f -> f.stage() < stages.size()
				&& stages.get(f.stage()).stream().anyMatch(p -> p.value() == f.feature())).toList();
		});
	}

	private void iceColumns(int level, int x0, int z0, int size, @Nullable LodTile tile, java.util.function.Consumer<int[]> consumer) {
		if (this.iceFeatures.isEmpty()) return;
		int span = size << level;
		// Very coarse small-GPU rings sample feature origins too: never expand a tile into tens of thousands of chunks.
		int stride = 1 << Math.max(0, level - 4);
		var resolver = this.noise.resolver();
		for (int cz = Math.floorDiv((z0 >> 4) - 1, stride) * stride; cz <= (z0 + span - 1 >> 4) + 1; cz += stride) {
			for (int cx = Math.floorDiv((x0 >> 4) - 1, stride) * stride; cx <= (x0 + span - 1 >> 4) + 1; cx += stride) {
				if (tile != null) {
					int tx = Math.clamp((cx * 16 + 8 - x0) >> level, 0, size-1), tz = Math.clamp((cz * 16 + 8 - z0) >> level, 0, size-1);
					if (this.wantedIce(tile.biome[tz * size + tx]).isEmpty()) continue;
				}
				Holder<Biome> biome = resolver.getNoiseBiome((cx * 16 + 8) >> 2, this.noise.seaLevel >> 2, (cz * 16 + 8) >> 2);
				List<IceFeature> wanted = this.wantedIce(biome);
				if (wanted.isEmpty()) continue;
				long key = chunkKey(cx, cz);
				int chunkX = cx, chunkZ = cz;
				int[] columns = this.cache.get(ICE_CACHE, key, () -> this.runIce(chunkX, chunkZ, wanted),
					value -> 128L + 4L * value.length);
				consumer.accept(columns);
			}
		}
	}

	private int[] runIce(int cx, int cz, List<IceFeature> wanted) {
		Stand stand = this.stands.get(); stand.reset(); stand.treeFeature = false;
		placeIce(stand.proxy, this.generator, this.seed, cx, cz, wanted);
		return iceTops(stand.placed, this.noise.seaLevel);
	}

	static void placeIce(WorldGenLevel world, ChunkGenerator generator, long seed, int cx, int cz, List<IceFeature> wanted) {
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		BlockPos origin = new BlockPos(cx * 16, world.getMinY(), cz * 16);
		long decoration = random.setDecorationSeed(seed, origin.getX(), origin.getZ());
		FeaturePlacer placer = new FeaturePlacer(world, generator);
		for (IceFeature feature : wanted) {
			random.setFeatureSeed(decoration, feature.index(), feature.stage());
			placer.placeWithBiomeCheck(feature.feature(), random, origin);
		}
	}

	/** Summarize the final carved shape, not the highest historical setBlock call (carving can remove that block). */
	static int[] iceTops(java.util.Map<Long, BlockState> placed, int seaLevel) {
		var tops = new HashMap<Long, BlockPos>();
		placed.forEach((key, state) -> {
			int y = BlockPos.getY(key);
			if (y < seaLevel || !(state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.SNOW_BLOCK))) return;
			long column = BlockPos.asLong(BlockPos.getX(key), 0, BlockPos.getZ(key));
			BlockPos previous = tops.get(column);
			if (previous == null || y > previous.getY()) tops.put(column, BlockPos.of(key));
		});
		int[] result = new int[tops.size() * 5]; int i = 0;
		for (BlockPos pos : tops.values()) {
			BlockState top = placed.get(pos.asLong()), below = placed.get(pos.below().asLong());
			if (below == null || below.isAir() || below.is(Blocks.WATER) || below.is(Blocks.SNOW_BLOCK))
				below = top.is(Blocks.SNOW_BLOCK) ? Blocks.PACKED_ICE.defaultBlockState() : top;
			result[i++] = pos.getX(); result[i++] = pos.getZ(); result[i++] = pos.getY() + 1;
			result[i++] = Block.getId(top); result[i++] = Block.getId(below);
		}
		return result;
	}

	/** Upgrade only generated flat frozen-water cells. Captured chunks/builds retain their own geometry and lighting. */
	boolean repairIce(long key, int[] g, int[] c, int[] cr, int[] tw, int[] runs) {
		int icy = LodClip.rgb565(LodColors.look(Blocks.ICE.defaultBlockState()).top());
		java.util.BitSet eligible = new java.util.BitSet(LodTile.CELLS);
		for (int i = 0; i < LodTile.CELLS; i++) if (generatedIceBase(g[i], c[i], tw == null ? 0 : tw[i], icy)) eligible.set(i);
		if (eligible.isEmpty()) return false;
		boolean surface = LodFrozenOcean.repair(this.noise, key, eligible, g, c, cr, tw, runs);
		int level = LodTile.levelOf(key), x0 = LodTile.txOf(key) * (64 << level), z0 = LodTile.tzOf(key) * (64 << level);
		java.util.BitSet changed = new java.util.BitSet(LodTile.CELLS);
		this.iceColumns(level, x0, z0, 64, null, columns -> {
			for (int k = 0; k < columns.length; k += 5) {
				int x = columns[k] - x0, z = columns[k+1] - z0;
				if (x < 0 || z < 0 || x >= 64 << level || z >= 64 << level) continue;
				int i = (z >> level) * 64 + (x >> level), top = columns[k+2];
				// Later columns in this same coarse footprint may be taller than an earlier iceberg column.
				if (!eligible.get(i)) continue;
				if (top <= (g[i] & 4095) - 512) continue;
				BlockState state = Block.stateById(columns[k+3]), side = Block.stateById(columns[k+4]);
				g[i] = LodClip.geomWord(top, false);
				c[i] = LodClip.colorWord(LodColors.look(state).top(), LodColors.look(side).side());
				if (cr != null) cr[i] = LodClip.belowWord(LodColors.look(side).side());
				if (runs != null) runs[i] = 0;
				if (tw != null) { tw[i] = LodPalette.word(LodPalette.id(state), LodPalette.id(side), LodPalette.id(side)); if (tw.length > LodTile.CELLS) tw[LodTile.CELLS+i] = 0; }
				changed.set(i);
			}
		});
		return surface || !changed.isEmpty();
	}

	static boolean generatedIceBase(int geom, int color, int material, int icy) {
		return (geom & (LodClip.GEOM_VALID | LodClip.GEOM_WET | LodClip.GEOM_CLEAR | LodClip.GEOM_CROWN)) == (LodClip.GEOM_VALID | LodClip.GEOM_WET)
			&& (material & (LodClip.TEX_LIGHT | LodClip.TEX_OVERHANG)) == 0 && (color & 65535) == icy;
	}

	/** The chunk's ground at block resolution (cached). */
	Ground ground(int chunkX, int chunkZ) {
		return this.cache.get(GROUND_CACHE, chunkKey(chunkX, chunkZ), () -> {
			LodTile t = new LodTile(0, chunkX * 16, chunkZ * 16, 16);
			t.impostorTrees = false;
			this.noise.generate(t);
			return groundOf(t, 0, 0, 16);
		}, value -> GROUND_BYTES);
	}

	/** A level-0 tile's chunks go into the ground cache as it is generated (tile cells from (cx, cz), 16 x 16). */
	void offer(LodTile t) {
		if (t.level != 0 || !this.available() || LodConfig.TREE_LEVELS <= 0) return;
		for (int cz = 0; cz < t.size / 16; cz++) {
			for (int cx = 0; cx < t.size / 16; cx++) {
				long key = chunkKey((t.x0 >> 4) + cx, (t.z0 >> 4) + cz);
				if (!this.cache.contains(GROUND_CACHE, key)) this.cache.offer(GROUND_CACHE, key, groundOf(t, cx * 16, cz * 16, t.size), GROUND_BYTES);
			}
		}
	}

	private Ground groundOf(LodTile t, int ox, int oz, int stride) {
		short[] h = new short[256];
		boolean[] wet = new boolean[256];
		BlockState[] st = new BlockState[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = (oz + z) * stride + ox + x;
				h[z * 16 + x] = t.ground[i];
				wet[z * 16 + x] = t.water[i] != LodTile.DRY && t.water[i] > t.ground[i];
				st[z * 16 + x] = t.state[i] != null ? t.state[i] : Blocks.STONE.defaultBlockState();
			}
		}
		return new Ground(h, wet, st);
	}

	static long chunkKey(int x, int z) {
		return (long) x << 32 | (z & 0xFFFFFFFFL);
	}

	/** The chunk's trees (cached): every block the game's tree features set when decorating it. */
	Trees trees(int chunkX, int chunkZ) {
		return this.cache.get(TREE_CACHE, chunkKey(chunkX, chunkZ), () -> this.run(chunkX, chunkZ),
			value -> 128L + 4L * value.blocks.length);
	}

	private Trees run(int chunkX, int chunkZ) {
		FeatureSorter.StepFeatureData step = this.step;
		if (step == null) return new Trees(new int[0]);
		long start = System.nanoTime();
		Stand s = this.stands.get();
		s.reset();
		// the features of the biomes at the chunk's surface (a feature whose biome isn't there places nothing: its biome check fails)
		Ground g = this.ground(chunkX, chunkZ);
		java.util.BitSet wanted = new java.util.BitSet();
		java.util.Set<Holder<Biome>> seen = new java.util.HashSet<>();
		// a 6 x 6 grid over the chunk and 4 blocks around it (the game's biome lookup jitters between neighboring 4-block cells)
		for (int gz = 0; gz < 6; gz++) {
			for (int gx = 0; gx < 6; gx++) {
				int lx = -4 + gx * 24 / 5, lz = -4 + gz * 24 / 5;
				int i = Math.clamp(lz, 0, 15) * 16 + Math.clamp(lx, 0, 15);
				Holder<Biome> b = s.biomes.getBiome(chunkX * 16 + lx, g.height()[i], chunkZ * 16 + lz);
				if (!seen.add(b)) continue;
				List<net.minecraft.core.HolderSet<PlacedFeature>> steps = this.generator.getBiomeGenerationSettings(b).features();
				if (steps.size() <= VEG) continue;
				for (Holder<PlacedFeature> pf : steps.get(VEG)) {
					int idx = step.indexMapping().applyAsInt(pf.value());
					if (idx >= 0 && idx < this.tree.length && (this.tree[idx] || this.plants[idx])) wanted.set(idx);
				}
			}
		}
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		BlockPos origin = new BlockPos(chunkX * 16, this.level.getMinY(), chunkZ * 16);
		long decoration = random.setDecorationSeed(this.seed, origin.getX(), origin.getZ());
		FeaturePlacer placer = new FeaturePlacer(s.proxy, this.generator);
		for (int idx = wanted.nextSetBit(0); idx >= 0; idx = wanted.nextSetBit(idx + 1)) {
			random.setFeatureSeed(decoration, idx, VEG);
			s.treeFeature = this.tree[idx];
			try {
				placer.placeWithBiomeCheck(step.features().get(idx), random, origin);
			} catch (RuntimeException e) {
				if (this.failures.incrementAndGet() <= 5) {
					var id = this.level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(step.features().get(idx));
					System.out.println("mcopt-lod: tree feature " + id + " failed in chunk " + chunkX + "," + chunkZ + ": " + e);
				}
			}
		}
		int[] out = new int[s.placed.size() * 4];
		int n = 0;
		for (var e : s.placed.entrySet()) {
			long p = e.getKey();
			out[n++] = BlockPos.getX(p);
			out[n++] = BlockPos.getY(p);
			out[n++] = BlockPos.getZ(p);
			out[n++] = Block.getId(e.getValue()) | (s.fromTree.contains(p) ? FROM_TREE : 0);
		}
		this.runs.incrementAndGet();
		this.runNanos.addAndGet(System.nanoTime() - start);
		return new Trees(out);
	}

	/**
	 * Adds the trees of the tile's chunks and of the ring of chunks around them to a tile of level 0 or 1 (-Dmcopt.lod.treeLevels):
	 * per column (per cell: the blocks of its 2 x 2 columns at level 1, where at least two of them must hold a tree), a trunk
	 * or low growth standing on the ground raises the column; a crown over air is a floating canopy (canopyLo..canopyHi).
	 * The ground a tree changed (podzol, dirt under trunks) becomes the column's surface at level 0.
	 */
	void plant(LodTile t) {
		if (t.level >= LodConfig.TREE_LEVELS || t.level > 1 || this.step == null) return;
		int L = t.level, span = t.size << L;
		int c0x = (t.x0 >> 4) - 1, c0z = (t.z0 >> 4) - 1, c1x = ((t.x0 + span) >> 4), c1z = ((t.z0 + span) >> 4);
		int n = t.cells();
		int[] lo = new int[n], hi = new int[n], cols = new int[n];
		// the blocks a tree fills, up from the ground (bit k: ground + k), for the crown's runs (a spruce's tiers)
		long[] occ = new long[n];
		BlockState[] topState = new BlockState[n];
		boolean[] standing = new boolean[n];
		// level 0: plants standing on the ground, up to 3 blocks (k: ground + k)
		BlockState[] plantAt = L == 0 && LodConfig.PLANTS ? new BlockState[n * 3] : null;
		// level 1: which of a cell's 4 columns hold a tree block (bit per column)
		java.util.Arrays.fill(lo, Integer.MAX_VALUE);
		java.util.Arrays.fill(hi, Integer.MIN_VALUE);
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				int[] b = this.trees(cx, cz).blocks();
				for (int k = 0; k < b.length; k += 4) {
					int bx = b[k] - t.x0, y = b[k + 1], bz = b[k + 2] - t.z0;
					if (bx < 0 || bz < 0 || bx >= span || bz >= span) continue;
					int i = (bz >> L) * t.size + (bx >> L);
					BlockState st = Block.stateById(b[k + 3] & ~FROM_TREE);
					boolean fromTree = (b[k + 3] & FROM_TREE) != 0;
					if (st.isAir()) continue;
					// Vines placed by a tree decorate its faces; they do not make a solid trunk/canopy column of their own.
					if (LodChunks.hangingDecoration(st)) continue;
					int g = t.ground[i];
					if (L == 0 && y < g) {
						// the ground itself changed (podzol, rooted dirt, mud under mangroves)
						if (y == g - 1 && fromTree) t.state[i] = st;
						continue;
					}
					if (y < g) continue;
					if (!fromTree) {
						// a plant (crossed quads) on the ground; solid growth (cacti, pumpkins, melons) stands like a tree; flat
						// growth (petals, leaf litter) isn't seen from afar
						if (LodColors.look(st).cross() && st.getFluidState().isEmpty()) {
							if (plantAt != null && y - g < 3) plantAt[i * 3 + (y - g)] = st;
							continue;
						}
						var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
						if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5) continue;
					}
					cols[i] |= 1 << (((bz & L) << 1) | (bx & L));
					if (y - g < 64) occ[i] |= 1L << (y - g);
					if (st.is(BlockTags.LOGS)) t.trunk[i] = st;
					if (y == g) standing[i] = true;
					if (y < lo[i]) lo[i] = y;
					if (y > hi[i]) {
						hi[i] = y;
						topState[i] = st;
					}
				}
			}
		}
		if (plantAt != null) {
			for (int i = 0; i < n; i++) {
				if (plantAt[i * 3] == null || standing[i] || t.water[i] != LodTile.DRY && t.water[i] > t.ground[i]) continue;
				int blocks = 1;
				while (blocks < 3 && plantAt[i * 3 + blocks] != null) blocks++;
				t.plantLower[i] = plantAt[i * 3];
				t.plantUpper[i] = blocks > 1 ? plantAt[i * 3 + blocks - 1] : null;
				t.plantBlocks[i] = (byte) blocks;
			}
		}
		for (int i = 0; i < n; i++) {
			if (hi[i] == Integer.MIN_VALUE || L == 1 && Integer.bitCount(cols[i]) < 2) continue;
			t.canopyLo[i] = (short) (standing[i] ? t.ground[i] : lo[i]);
			t.canopyHi[i] = (short) hi[i];
			t.canopyState[i] = topState[i];
			t.standing[i] = standing[i] || lo[i] <= t.ground[i];
			if (!t.standing[i] && occ[i] != 0) t.crownRuns[i] = LodTile.runs(occ[i] >>> Long.numberOfTrailingZeros(occ[i]));
		}
	}

	/** The stand-in level a chunk's trees grow in: its ground from the far terrain, what the trees set in a map. */
	private final class Stand implements InvocationHandler {
		final HashMap<Long, BlockState> placed = new HashMap<>();
		/** Blocks the tree features set (the rest: plant features). */
		final java.util.HashSet<Long> fromTree = new java.util.HashSet<>();
		boolean treeFeature;
		/** Per column (x, z packed), per heightmap type: the highest block set that the heightmap counts (heightmaps rise with them). */
		final HashMap<Long, int[]> tops = new HashMap<>();
		final WorldGenLevel proxy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, this);
		final BiomeManager biomes = new BiomeManager(LodForest.this.noise.resolver(), LodForest.this.biomeSeed);
		final RandomSource random = RandomSource.create(0L);
		private final java.util.Set<String> unknown = new java.util.HashSet<>();

		void reset() {
			this.placed.clear();
			this.fromTree.clear();
			this.tops.clear();
			this.scratch = null;
		}

		private net.minecraft.world.level.chunk.@Nullable ProtoChunk scratch;

		net.minecraft.world.level.chunk.ProtoChunk scratch() {
			if (this.scratch == null) {
				this.scratch = new net.minecraft.world.level.chunk.ProtoChunk(net.minecraft.world.level.ChunkPos.ZERO, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
					LodForest.this.level, LodForest.this.level.palettedContainerFactory(), null);
			}
			return this.scratch;
		}

		BlockState state(int x, int y, int z) {
			BlockState p = this.placed.get(BlockPos.asLong(x, y, z));
			if (p != null) return p;
			Ground g = LodForest.this.ground(x >> 4, z >> 4);
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			if (y < h) {
				if (y == h - 1) return g.state()[i];
				if (y >= h - 4) {
					BlockState s = g.state()[i];
					return s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : s;
				}
				return Blocks.STONE.defaultBlockState();
			}
			if (g.wet()[i] && y < LodForest.this.noise.seaLevel) return Blocks.WATER.defaultBlockState();
			return Blocks.AIR.defaultBlockState();
		}

		int height(Heightmap.Types type, int x, int z) {
			Ground g = LodForest.this.ground(x >> 4, z >> 4);
			int i = (z & 15) * 16 + (x & 15), h = g.height()[i];
			int[] top = this.tops.get(BlockPos.asLong(x, 0, z));
			int placedTop = top == null || top[type.ordinal()] == Integer.MIN_VALUE ? Integer.MIN_VALUE : top[type.ordinal()] + 1;
			int water = g.wet()[i] ? Math.max(h, LodForest.this.noise.seaLevel) : h;
			return switch (type) {
				case OCEAN_FLOOR, OCEAN_FLOOR_WG -> Math.max(h, placedTop);
				default -> Math.max(water, placedTop);
			};
		}

		@Override
		public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
			String name = m.getName();
			int n = a == null ? 0 : a.length;
			switch (name) {
				case "getBlockState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ());
					}
					break;
				case "getFluidState":
					if (n == 1) {
						BlockPos p = (BlockPos) a[0];
						return this.state(p.getX(), p.getY(), p.getZ()).getFluidState();
					}
					break;
				case "isStateAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<BlockState> pred = (Predicate<BlockState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()));
					}
					break;
				case "isFluidAtPosition":
					if (n == 2) {
						BlockPos p = (BlockPos) a[0];
						@SuppressWarnings("unchecked")
						Predicate<FluidState> pred = (Predicate<FluidState>) a[1];
						return pred.test(this.state(p.getX(), p.getY(), p.getZ()).getFluidState());
					}
					break;
				case "setBlock":
					if (n >= 2) {
						BlockPos p = (BlockPos) a[0];
						BlockState st = (BlockState) a[1];
						this.placed.put(p.asLong(), st);
						if (this.treeFeature) this.fromTree.add(p.asLong());
						else this.fromTree.remove(p.asLong());
						if (!st.isAir()) {
							int[] top = this.tops.computeIfAbsent(BlockPos.asLong(p.getX(), 0, p.getZ()), k -> {
								int[] none = new int[Heightmap.Types.values().length];
								java.util.Arrays.fill(none, Integer.MIN_VALUE);
								return none;
							});
							for (Heightmap.Types t : Heightmap.Types.values()) if (t.isOpaque().test(st)) top[t.ordinal()] = Math.max(top[t.ordinal()], p.getY());
						}
						return true;
					}
					break;
				case "removeBlock":
				case "destroyBlock":
					if (n >= 1) {
						BlockPos p = (BlockPos) a[0];
						this.placed.put(p.asLong(), Blocks.AIR.defaultBlockState());
						return true;
					}
					break;
				case "getHeight":
					if (n == 3) return this.height((Heightmap.Types) a[0], (Integer) a[1], (Integer) a[2]);
					if (n == 0) return LodForest.this.level.getHeight();
					break;
				case "getMinY":
					return LodForest.this.level.getMinY();
				case "getBiome":
					if (n == 1) return this.biomes.getBiome((BlockPos) a[0]);
					break;
				case "getSeed":
					return LodForest.this.seed;
				case "getLevel":
					return LodForest.this.level;
				case "registryAccess":
					return LodForest.this.level.registryAccess();
				case "getRandom":
					return this.random;
				case "getBlockEntity":
					if (n == 1) return null;
					break;
				case "getChunk":
					// a feature marking blocks for post-processing (fallen trees, multiface growth): a scratch chunk takes the marks
					return this.scratch();
				case "getLightEngine":
					return net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
				case "ensureCanWrite":
				case "hasChunk":
				case "isAreaLoaded":
					return true;
				case "setCurrentlyGenerating":
				case "scheduleTick":
				case "blockUpdated":
				case "updateNeighborsAt":
				case "playSound":
				case "levelEvent":
				case "gameEvent":
				case "addParticle":
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == a[0];
				case "toString":
					return "LodForest.Stand";
				default:
					break;
			}
			if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
			if (this.unknown.add(name + "/" + n)) System.out.println("mcopt-lod: tree stand-in level asked " + name + "/" + n + " (answering a default)");
			Class<?> r = m.getReturnType();
			if (r == boolean.class) return false;
			if (r == int.class) return 0;
			if (r == long.class) return 0L;
			if (r == float.class) return 0.0F;
			if (r == double.class) return 0.0;
			if (r == java.util.Optional.class) return java.util.Optional.empty();
			if (r == List.class) return new ArrayList<>();
			return null;
		}
	}
}
