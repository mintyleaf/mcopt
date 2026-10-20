package mcopt.metal.lod;

/** Ordinary outer-ring streaming may use one shared generation slot; near-player holes stop fine work. */
final class LodStreamingPressure {
	private boolean severe;
	boolean sample(int missing, int total, int nearMissing) {
		if (!this.severe) this.severe = nearMissing > 0 || missing >= Math.max(32, (total + 2) / 3);
		else if (nearMissing == 0 && missing <= Math.max(16, total / 4)) this.severe = false;
		return this.severe;
	}
}
