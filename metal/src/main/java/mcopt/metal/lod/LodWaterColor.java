package mcopt.metal.lod;

/** Keep depth cues subtle on an opaque water heightfield: a deep cave must not look like a shadow on the surface. */
final class LodWaterColor {
	private LodWaterColor() { }

	static int surface(int floor, int water, int depth) {
		return soften(LodColors.mix(floor, water, Math.min(1.0F, 0.55F + Math.max(0, depth) / 24.0F)), water);
	}

	static int soften(int old, int water) {
		return LodColors.mix(old, water, 0.75F);
	}

	@FunctionalInterface
	interface Tint {
		/** Water surface color at this position, or -1 to preserve ice. */
		int at(int x, int y, int z);
	}

	/** Migrate colors only. Geometry, player structures, materials, plants and lighting stay intact. */
	static void migrate(long key, int[] g, int[] c, int[] cr, Tint tint) {
		int level = LodTile.levelOf(key), step = 1 << level;
		int x0 = LodTile.txOf(key) * (64 << level), z0 = LodTile.tzOf(key) * (64 << level);
		for (int i = 0; i < LodTile.CELLS; i++) {
			boolean crown = (g[i] & LodClip.GEOM_CROWN) != 0;
			boolean wet = crown ? cr != null && (cr[i] & LodClip.CROWN_WET) != 0 : (g[i] & LodClip.GEOM_WET) != 0;
			if (!wet || (g[i] & LodClip.GEOM_VALID) == 0) continue;
			int y = ((crown ? cr[i] : g[i]) & 4095) - 512;
			int water = tint.at(x0 + (i & 63) * step + step / 2, y, z0 + (i >> 6) * step + step / 2);
			if (water < 0) continue;
			int packed = crown ? (cr[i] >> 12) & 65535 : c[i] & 65535;
			int rgb = ((packed >> 11) * 255 / 31) << 16 | (((packed >> 5) & 63) * 255 / 63) << 8 | (packed & 31) * 255 / 31;
			int color = LodClip.rgb565(soften(rgb, water));
			if (crown) cr[i] = (cr[i] & ~0x0FFFF000) | color << 12;
			else c[i] = color | color << 16;
		}
	}
}
