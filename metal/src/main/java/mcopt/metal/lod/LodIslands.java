package mcopt.metal.lod;

/** A floating solid interval and known empty space; neither represents an infinite column down to the world floor. */
final class LodIslands {
	static final short EMPTY = -512;
	private LodIslands() { }

	record Interval(int bottom, int top) { }
	static Interval interval(float[] density, int minY, int step) {
		int top = density.length - 1;
		while (top >= 0 && !(density[top] > 0)) top--;
		if (top < 0) return null;
		int bottom = top;
		while (bottom > 0 && density[bottom - 1] > 0) bottom--;
		int hi = top == density.length - 1 ? minY + top * step + 1
			: crossing(density[top], density[top + 1], minY + top * step, step);
		int lo = bottom == 0 ? minY
			: crossing(density[bottom - 1], density[bottom], minY + (bottom - 1) * step, step);
		return new Interval(lo, Math.max(lo + 1, hi));
	}

	private static int crossing(float a, float b, int y, int step) {
		return (int)Math.floor(y + a / (a - b) * step) + 1;
	}
}
