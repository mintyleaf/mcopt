package mcopt.metal.lod;

import java.util.function.IntPredicate;

/** A selected air interval, represented by a floor and a separate ceiling rather than the dimension's bedrock roof. */
final class LodCaverns {
	static final int NONE = Integer.MIN_VALUE;
	record Layer(int floor, int ceiling) { }
	private LodCaverns() { }

	/** A field rebuild is expensive. Wait for a settled altitude instead of rebuilding repeatedly during vertical flight. */
	static final class Selection {
		private int pending = NONE;
		private long pendingSince, changedAt;
		void reset() { this.pending = NONE; this.pendingSince = this.changedAt = 0; }
		int select(double y, int previous, int min, int roof, long now) {
			int desired = band(y, previous, min, roof);
			if (desired == previous) { this.pending = desired; this.pendingSince = now; return previous; }
			// Entering or leaving the roof must switch geometry mode immediately.
			if (previous == NONE || desired == NONE) {
				this.pending = desired; this.pendingSince = this.changedAt = now; return desired;
			}
			if (desired != this.pending) { this.pending = desired; this.pendingSince = now; }
			if (now - this.pendingSince < 1_000_000_000L || now - this.changedAt < 3_000_000_000L) return previous;
			this.changedAt = now;
			return desired;
		}
	}

	static int band(double y, int previous, int min, int roof) {
		if (y >= roof + 1) return NONE;
		if (previous != NONE && Math.abs(y - previous) <= 12) return previous;
		return Math.clamp((int)Math.round(y / 16) * 16, min + 1, roof - 1);
	}

	/** Prefer the gap containing the view band, then the closest one. Tiny cracks are not navigable caverns. */
	static Layer find(int min, int max, int viewY, IntPredicate air) {
		Layer best = null;
		int distance = Integer.MAX_VALUE;
		for (int y = max; y > min;) {
			if (!air.test(y)) { y--; continue; }
			int ceiling = y + 1;
			while (y > min && air.test(y)) y--;
			if (ceiling - y < 5) continue;
			int d = Math.max(0, Math.max(y + 1 - viewY, viewY - ceiling + 1));
			if (d < distance || (d == distance && best != null && ceiling - y > best.ceiling - best.floor)) {
				best = new Layer(y, ceiling);
				distance = d;
			}
		}
		return best;
	}
}
