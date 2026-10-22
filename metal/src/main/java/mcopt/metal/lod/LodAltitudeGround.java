package mcopt.metal.lod;

/** A small loaded-heightmap neighborhood. Isolated towers must not make an ocean look like a mountain. */
final class LodAltitudeGround {
	static final int CAPACITY = 25;
	private LodAltitudeGround() { }

	/** Upper quartile retains broad ridges/canopies; fewer than half the samples is unknown ground. */
	static double surface(int[] samples, int count) {
		if (count < 13) return Double.NaN;
		java.util.Arrays.sort(samples, 0, count);
		return samples[count * 3 / 4];
	}
}
