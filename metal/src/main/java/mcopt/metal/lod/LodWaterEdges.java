package mcopt.metal.lod;

import java.util.Arrays;

/** A small signed distance field around the actual near/LOD handoff, in blocks at chunk centers. */
final class LodWaterEdges {
	static final int WIDTH = 32;
	private int x = Integer.MIN_VALUE, z, size, words;
	private int[] previous = new int[0];
	private float[] distances = new float[0];

	float[] update(int[] mask, int x, int z, int size, int words) {
		int count = size * words;
		if (this.x == x && this.z == z && this.size == size && this.words == words
			&& Arrays.equals(this.previous, 0, count, mask, 0, count)) return this.distances;
		this.x = x;
		this.z = z;
		this.size = size;
		this.words = words;
		this.previous = Arrays.copyOf(mask, count);
		if (this.distances.length != size * size) this.distances = new float[size * size];
		for (int cz = 0; cz < size; cz++) {
			for (int cx = 0; cx < size; cx++) {
				boolean covered = covered(mask, cx, cz, size, words);
				float distance = WIDTH + 16;
				for (int dz = -3; dz <= 3; dz++) {
					for (int dx = -3; dx <= 3; dx++) {
						if (covered(mask, cx + dx, cz + dz, size, words) == covered) continue;
						int bx = Math.max(0, Math.abs(dx) * 16 - 8), bz = Math.max(0, Math.abs(dz) * 16 - 8);
						distance = Math.min(distance, (float) Math.hypot(bx, bz));
					}
				}
				this.distances[cz * size + cx] = covered ? distance : -distance;
			}
		}
		return this.distances;
	}

	private static boolean covered(int[] mask, int x, int z, int size, int words) {
		return x >= 0 && z >= 0 && x < size && z < size && (mask[z * words + (x >> 5)] & (1 << (x & 31))) != 0;
	}
}
