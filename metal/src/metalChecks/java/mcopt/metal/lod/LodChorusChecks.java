package mcopt.metal.lod;

import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

/** Distant vegetation must cross tile boundaries consistently without modifying an island or filling known void. */
public final class LodChorusChecks {
	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var registry = net.minecraft.data.registries.VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
		var highlands = registry.getOrThrow(Biomes.END_HIGHLANDS);
		var midlands = registry.getOrThrow(Biomes.END_MIDLANDS);
		for (int level = 0; level < 5; level++) {
			LodTile tile = fixture(level, -1376, 2048, 64, highlands);
			tile.height[0] = tile.ground[0] = tile.islandBottom[0] = LodIslands.EMPTY;
			tile.state[0] = Blocks.AIR.defaultBlockState();
			tile.state[1] = Blocks.STONE.defaultBlockState(); tile.biome[2] = midlands;
			short[] tops = tile.height.clone(), bottoms = tile.islandBottom.clone();
			LodChorus.approximate(tile, 12345);
			if (!Arrays.equals(tops, tile.height) || !Arrays.equals(bottoms, tile.islandBottom))
				throw new AssertionError("Chorus changes terrain height or finite island undersides");
			for (int i = 0; i < 3; i++) if (tile.canopyState[i] != null || tile.top[i] != 0xD8DBA8)
				throw new AssertionError("Chorus grows in void, on unsupported terrain or in a barren biome");
			int[] g = new int[tile.cells()], c = g.clone(), cr = g.clone(), runs = g.clone();
			LodField.pack(tile, g, c, cr, runs, null);
			int plants = 0, branches = 0, tinted = 0;
			for (int i = 3; i < tile.cells(); i++) {
				if (tile.canopyState[i] != null) {
					plants++; if (tile.canopyLo[i] > 64) branches++;
					if ((g[i] & LodClip.GEOM_CROWN) == 0 || (cr[i] & LodClip.CROWN_BOUNDED) == 0
						|| (cr[i] & 4095) - 512 != 64 || (runs[i] & 4095) - 512 != 40
						|| (runs[i] >>> 12 & 4095) - 512 != tile.canopyLo[i])
						throw new AssertionError("Coarse chorus loses its island body or fills the branch air gap at level " + level);
				}
				if (tile.top[i] != 0xD8DBA8) tinted++;
			}
			if (level < 4 && (plants == 0 || level < 3 && branches == 0)) throw new AssertionError("Missing coarse chorus silhouettes at level " + level);
			if (level == 4 && (plants != 0 || tinted == 0)) throw new AssertionError("Farthest chorus makes solid wide stalks instead of coverage");
			if ((g[0] & 4095) - 512 != LodIslands.EMPTY) throw new AssertionError("Vegetation fills known void");
			LodTile exact = fixture(level, -1376, 2048, 64, highlands); exact.impostorTrees = false;
			LodChorus.approximate(exact, 12345);
			for (int i = 0; i < exact.cells(); i++) if (exact.canopyState[i] != null || exact.top[i] != 0xD8DBA8)
				throw new AssertionError("Approximation overwrites exact vegetation");
		}
		LodTile whole = fixture(2, -1280, 2048, 64, highlands);
		LodTile piece = fixture(2, -1280 + 32 * 4, 2048, 32, highlands);
		LodTile otherSeed = fixture(2, -1280, 2048, 64, highlands);
		LodChorus.approximate(whole, 12345); LodChorus.approximate(piece, 12345); LodChorus.approximate(otherSeed, 54321);
		for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
			int a = z * 64 + 32 + x, b = z * 32 + x;
			if (whole.canopyLo[a] != piece.canopyLo[b] || whole.canopyHi[a] != piece.canopyHi[b])
				throw new AssertionError("Chorus placement depends on tile borders or generation order");
		}
		if (Arrays.equals(whole.canopyHi, otherSeed.canopyHi)) throw new AssertionError("Approximation ignores world seed");
		System.out.println("PASS: chorus silhouettes on levels 0..3, coverage on level 4, tile/seed consistency, exact vegetation precedence, finite islands, branch gaps and known void");
	}

	private static LodTile fixture(int level, int x, int z, int size, Holder<Biome> biome) {
		LodTile tile = new LodTile(level, x, z, size);
		tile.islandBottom = new short[tile.cells()];
		Arrays.fill(tile.height, (short)64); Arrays.fill(tile.ground, (short)64); Arrays.fill(tile.islandBottom, (short)40);
		Arrays.fill(tile.water, LodTile.DRY); Arrays.fill(tile.state, Blocks.END_STONE.defaultBlockState()); Arrays.fill(tile.biome, biome);
		Arrays.fill(tile.top, 0xD8DBA8); Arrays.fill(tile.side, 0xA6AC79); Arrays.fill(tile.below, 0xA6AC79);
		return tile;
	}
}
