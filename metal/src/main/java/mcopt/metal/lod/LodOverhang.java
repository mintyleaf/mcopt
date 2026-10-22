package mcopt.metal.lod;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Bounded extraction of a floating solid layer above a lower surface; shares the crown geometry format. */
final class LodOverhang {
	static final int MAX_THICKNESS = LodStructureLayers.RANGE;
	record Layer(int bottom, int ground) { }
	record Cap(int bottom, int ground, long occupied) { }

	private LodOverhang() { }

	/** Daylight in the gap distinguishes an exterior eave/cliff from the roof of an enclosed underground cave. */
	static boolean exposedGap(int bottomSky, int groundSky) {
		return bottomSky > 0 || groundSky > 0;
	}

	static boolean exposedGap(int light) {
		return exposedGap((light >>> 20) & 15, (light >>> 28) & 15);
	}

	/** Close a legacy cave cut without changing its surface height/color or exposing its underground materials. */
	static void closeCached(int i, int[] g, int[] c, int[] cr, int[] tw, int[] runs, int sideColor, int topId, int belowId, int belowColor) {
		g[i] &= ~(LodClip.GEOM_CROWN_BITS | 127 << LodClip.GEOM_DEPTH_SHIFT);
		c[i] = (c[i] & 65535) | LodClip.rgb565(sideColor) << 16;
		cr[i] = LodClip.belowWord(belowColor);
		if (runs != null) runs[i] = 0;
		// Keep the metadata flag as provenance for the coarse-cache upgrade; it no longer denotes split geometry.
		tw[i] = LodPalette.word(topId, topId, belowId) | (tw[i] & LodClip.TEX_OVERHANG);
	}

	static Layer find(int top, int minY, IntPredicate occupied) {
		int y = top - 1;
		while (y >= minY && top - y < MAX_THICKNESS && occupied.test(y)) y--;
		if (y < minY || occupied.test(y)) return null;
		int bottom = y + 1;
		// The empty gap is not part of the encoded roof's thickness. Bound it by the dimension,
		// not 128 blocks: a sky platform can be close to build height above an ocean or valley.
		while (y >= minY && !occupied.test(y)) y--;
		if (y < minY) return null;
		return new Layer(bottom, y);
	}

	/** An unlit room inside a floating build is not its underside. Continue to the next gap, while
	 * retaining the total upper-body budget and requiring actual exterior light before opening it. */
	static Layer findExterior(int top, int minY, IntPredicate occupied, IntPredicate exposed) {
		int surface = top;
		while (surface >= minY && top - surface < MAX_THICKNESS) {
			Layer layer = find(surface, minY, occupied);
			if (layer == null || top - layer.bottom() >= MAX_THICKNESS) return null;
			if (exposed.test(layer.bottom() - 1) || exposed.test(layer.ground() + 1)) return layer;
			surface = layer.ground();
		}
		return null;
	}

	/** 1: mushroom cap, 0: air/decoration/leaves, 2: supporting surface (including water). */
	static Cap mushroom(int top, int minY, IntUnaryOperator material) {
		int bottom = top;
		long down = 1;
		boolean gap = false;
		for (int y = top - 1; y >= minY && top - y < 63; y--) {
			int kind = material.applyAsInt(y);
			if (kind == 1) {
				bottom = y;
				down |= 1L << (top - y);
				gap = false;
			} else if (kind == 0) {
				gap = true;
			} else {
				if (!gap) return null;
				long up = Long.reverse(down) >>> (63 - (top - bottom));
				return new Cap(bottom, y, up);
			}
		}
		return null;
	}
}
