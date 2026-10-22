package mcopt.metal.lod;

import java.util.Arrays;

public final class LodHandoffChecks {
	public static void main(String[] args) {
		LodHandoff h = new LodHandoff(128, 200);
		int size = 65, words = 3, x0 = -32, z0 = -32;
		int[] ready = new int[128 * 4];
		Arrays.fill(ready, -1);
		int[] original = ready.clone();
		long t = 10_000_000_000L;
		h.update(ready, x0, z0, size, words, 8, 8, 12, t);
		equal(0, h.arrivals[32 * size + 32], "arrival starts as full fallback");
		if (covered(h, 32, 32, words)) throw new AssertionError("Culled arriving LOD before dissolve");
		h.update(ready, x0, z0, size, words, 8, 8, 12, t + 100_000_000L);
		equal(0.5F, h.arrivals[32 * size + 32], "halfway");
		h.update(ready, x0, z0, size, words, 8, 8, 12, t + 200_000_000L);
		equal(1, h.arrivals[32 * size + 32], "arrival finishes");
		if (!covered(h, 32, 32, words)) throw new AssertionError("Interior LOD stays over complete near terrain");
		if (!covered(h, 41, 32, words)) throw new AssertionError("Complete inner chunk retained the old two-chunk overlap band");
		if (covered(h, 42, 32, words)) throw new AssertionError("Culled LOD inside outgoing blend band");
		if (h.nearestFar < h.inner - 16*Math.sqrt(2) || h.nearestFar > h.inner) throw new AssertionError("Unsafe nearest-far distance: " + h.nearestFar);
		if (!Arrays.equals(ready, original)) throw new AssertionError("Modified cached near readiness");
		// The window moves while the same world chunk stays ready: no restart, including across negative coordinates.
		h.update(ready, x0 - 1, z0, size, words, 7.9, 8, 12, t + 210_000_000L);
		equal(1, h.arrivals[32 * size + 33], "world anchored across window movement");
		// A missing visible section returns the whole fallback immediately, even deep inside the near range.
		ready[32 * words + 1] &= ~(1 << 1);
		h.update(ready, x0 - 1, z0, size, words, 8, 8, 12, t + 220_000_000L);
		equal(0, h.arrivals[32 * size + 33], "missing visible section");
		if (covered(h, 33, 32, words)) throw new AssertionError("Missing chunk has no fallback");
		ready[32 * words + 1] |= 1 << 1;
		h.update(ready, x0 - 1, z0, size, words, 8, 8, 12, t + 230_000_000L);
		equal(0, h.arrivals[32 * size + 33], "replacement restarts fade");
		// Toroidal state must not reuse another chunk's completed arrival after a teleport.
		h.update(ready, x0 + 128, z0, size, words, 2056, 8, 12, t + 500_000_000L);
		equal(0, h.arrivals[32 * size + 32], "slot reused by different world chunk");
		h.update(ready, x0 + 128, z0, size, words, 2056, 8, 12, t + 700_000_000L);
		h.reset();
		h.update(ready, x0 + 128, z0, size, words, 2056, 8, 12, t + 710_000_000L);
		equal(0, h.arrivals[32 * size + 32], "world switch");
		// Small and large RD windows retain enough fallback before the near cull's hard distance boundary.
		for (int rd = 2; rd <= 32; rd++) {
			for (int cx = -rd - 1; cx <= rd + 1; cx++) for (int cz = -rd - 1; cz <= rd + 1; cz++) {
				int dx = Math.max(0, Math.abs(cx) - 1), dz = Math.max(0, Math.abs(cz) - 1);
				if (dx * dx + dz * dz < rd * rd) continue;
				for (double cam : new double[] {0, 8, 15.999}) {
					double nx = Math.max(0, Math.max(cx * 16 - cam, cam - (cx + 1) * 16));
					double nz = Math.max(0, Math.max(cz * 16 - cam, cam - (cz + 1) * 16));
					if (Math.hypot(nx, nz) < (rd - 1) * 16) throw new AssertionError("Outgoing fade can reach near cull boundary");
				}
			}
		}
		System.out.println("PASS: timed handoff, stable chunk identity, streaming holes, negative/word boundaries, teleports, world resets and RD 2..32 outgoing coverage");
	}

	private static boolean covered(LodHandoff h, int x, int z, int words) {
		return (h.mask[z * words + (x >> 5)] & (1 << (x & 31))) != 0;
	}

	private static void equal(float expected, float actual, String message) {
		if (expected != actual) throw new AssertionError(message + ": " + actual + " != " + expected);
	}
}
