package mcopt.metal.lod;

import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

/** Seeded distant vegetation, independent of structure scouting. No chunk generation or feature volumes. */
final class LodChorus {
	private static final int PATCH = 12;
	private LodChorus() { }

	static void approximate(LodTile tile, long seed) {
		if (!LodConfig.TREES || !tile.impostorTrees || tile.islandBottom == null) return;
		int cell = tile.cell(), salt = mix((int)(seed ^ seed >>> 32));
		var plant = Blocks.CHORUS_PLANT.defaultBlockState();
		for (int i = 0; i < tile.cells(); i++) {
			int ground = tile.ground[i];
			if (ground == LodIslands.EMPTY || tile.canopyHi[i] >= tile.canopyLo[i]
				|| !tile.state[i].is(Blocks.END_STONE) || !tile.biome[i].is(Biomes.END_HIGHLANDS)) continue;
			int x = tile.x0 + i % tile.size * cell, z = tile.z0 + i / tile.size * cell;
			int color = LodColors.top(plant, tile.biome[i].value(), x, z);
			if (cell >= 16) {
				// Subpixel plants contribute coverage, rather than becoming sixteen-block-wide solid stalks.
				float cover = 0.10F + 0.06F * LodTrees.valueNoise(x / 64.0F, z / 64.0F);
				tile.top[i] = LodColors.mix(tile.top[i], color, cover);
				tile.side[i] = LodColors.mix(tile.side[i], color, cover * 0.5F);
				continue;
			}
			int lo = Integer.MAX_VALUE, hi = 0;
			// Include neighboring anchors whose arms reach this footprint. At most nine anchors for 1..8-block cells.
			for (int pz = Math.floorDiv(z - 4, PATCH); pz <= Math.floorDiv(z + cell + 3, PATCH); pz++) {
				for (int px = Math.floorDiv(x - 4, PATCH); px <= Math.floorDiv(x + cell + 3, PATCH); px++) {
					int hash = mix(px * 73428767 ^ pz * 912931 ^ salt);
					if ((hash & 7) == 0) continue;
					int ax = px * PATCH + 2 + (hash >>> 3 & 7), az = pz * PATCH + 2 + (hash >>> 6 & 7);
					int height = 5 + (hash >>> 9 & 7), branch = height / 2;
					if (inside(ax, az, x, z, cell)) { lo = 0; hi = Math.max(hi, height); }
					for (int arm = 0; arm < 4; arm++) {
						if ((hash >>> (12 + arm) & 1) == 0) continue;
						int reach = 2 + (hash >>> (16 + arm) & 1);
						int bx = ax + (arm == 0 ? reach : arm == 1 ? -reach : 0);
						int bz = az + (arm == 2 ? reach : arm == 3 ? -reach : 0);
						if (Math.min(ax, bx) < x + cell && Math.max(ax, bx) >= x
							&& Math.min(az, bz) < z + cell && Math.max(az, bz) >= z) {
							lo = Math.min(lo, branch); hi = Math.max(hi, branch + 1);
						}
						if (inside(bx, bz, x, z, cell)) { lo = Math.min(lo, branch); hi = Math.max(hi, height - 1); }
					}
				}
			}
			if (hi == 0) continue;
			tile.canopyLo[i] = (short)(ground + lo); tile.canopyHi[i] = (short)(ground + hi - 1);
			tile.canopyState[i] = plant;
			tile.crownTop[i] = tile.crownSide[i] = cell >= 8 ? LodColors.mix(tile.top[i], color, 0.55F) : color;
		}
	}

	private static boolean inside(int px, int pz, int x, int z, int cell) {
		return px >= x && px < x + cell && pz >= z && pz < z + cell;
	}
	private static int mix(int hash) {
		hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15; hash *= 0x846ca68b;
		return hash ^ hash >>> 16;
	}
}
