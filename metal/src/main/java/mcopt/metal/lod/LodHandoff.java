package mcopt.metal.lod;

import java.util.Arrays;

/** Bounded, world-chunk keyed arrivals and a conservative mask for the opaque LOD's soft handoff. */
final class LodHandoff {
	final int[] mask;
	final float[] arrivals;
	float inner, outer;
	double nearestFar;
	private final int capacity;
	private final long duration;
	private final int[] cx, cz;
	private final long[] started, seen;
	private long frame;

	LodHandoff(int capacity, int milliseconds) {
		this.capacity = capacity;
		this.duration = Math.max(1, milliseconds) * 1_000_000L;
		this.mask = new int[capacity * ((capacity + 31) / 32)];
		this.arrivals = new float[capacity * capacity];
		this.cx = new int[capacity * capacity];
		this.cz = new int[capacity * capacity];
		this.started = new long[capacity * capacity];
		this.seen = new long[capacity * capacity];
		this.reset();
	}

	void reset() {
		Arrays.fill(this.seen, -1);
		Arrays.fill(this.mask, 0);
		Arrays.fill(this.arrivals, 0);
		this.frame = 0;
	}

	/** Never changes the renderer's cached readiness mask. Missing chunks regain the complete fallback immediately. */
	void update(int[] ready, int x0, int z0, int size, int words, double camX, double camZ, int distance, long now) {
		if (size < 1 || size > this.capacity || words != (size + 31) / 32) throw new IllegalArgumentException("Handoff window");
		Arrays.fill(this.mask, 0);
		Arrays.fill(this.arrivals, 0);
		long f = ++this.frame;
		// Finish restoring the fallback before the near renderer's section-distance test can remove any chunk.
		this.outer = Math.max(16, (distance - 1) * 16);
		this.inner = Math.max(0, this.outer - 16);
		this.nearestFar = Math.max(0, (size / 2 - 1) * 16.0);
		for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
			int wx = x0 + x, wz = z0 + z;
			int slot = Math.floorMod(wz, this.capacity) * this.capacity + Math.floorMod(wx, this.capacity);
			boolean covered = (ready[z * words + (x >> 5)] & (1 << (x & 31))) != 0;
			float arrival = 0;
			if (covered) {
				if (this.seen[slot] != f - 1 || this.cx[slot] != wx || this.cz[slot] != wz) this.started[slot] = now;
				this.cx[slot] = wx;
				this.cz[slot] = wz;
				this.seen[slot] = f;
				arrival = smooth((float) ((double) (now - this.started[slot]) / this.duration));
			} else this.seen[slot] = -1;
			this.arrivals[z * size + x] = arrival;
			double ax = wx * 16.0 - camX, az = wz * 16.0 - camZ;
			double farX = Math.max(Math.abs(ax), Math.abs(ax + 16)), farZ = Math.max(Math.abs(az), Math.abs(az + 16));
			if (arrival == 1 && Math.hypot(farX, farZ) <= this.inner) {
				this.mask[z * words + (x >> 5)] |= 1 << (x & 31);
			} else {
				double nearX = Math.max(0, Math.max(ax, -ax - 16)), nearZ = Math.max(0, Math.max(az, -az - 16));
				this.nearestFar = Math.min(this.nearestFar, Math.hypot(nearX, nearZ));
			}
		}
	}

	static float smooth(float x) {
		x = Math.clamp(x, 0, 1);
		return x * x * (3 - 2 * x);
	}
}
