package mcopt.metal.lod;

/** Hysteresis keeps distant population from starting bursts while nearby real terrain is substantially missing. */
final class LodChunkPressure {
	private boolean busy;
	boolean sample(int missing, int total, int nearMissing) {
		if (!this.busy) this.busy = nearMissing > 0 || missing >= Math.max(16, (total + 7) / 8);
		else if (nearMissing == 0 && missing <= Math.max(4, total / 32)) this.busy = false;
		return this.busy;
	}
}
