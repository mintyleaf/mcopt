package mcopt.metal.lod;

import java.util.function.IntPredicate;

/** The highest water body above a generated floor, including ocean above a separate underground aquifer. */
final class LodWaterSurface {
	private LodWaterSurface() { }

	static short find(int top, int seaLevel, IntPredicate water) {
		if (top >= seaLevel) return LodTile.DRY;
		int y = seaLevel - 1;
		if (!water.test(y)) {
			y = top;
			if (!water.test(y)) return LodTile.DRY;
		}
		while (y < seaLevel + 16 && water.test(y + 1)) y++;
		return (short) (y + 1);
	}
}
