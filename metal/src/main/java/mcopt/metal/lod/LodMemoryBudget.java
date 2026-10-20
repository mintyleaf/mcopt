package mcopt.metal.lod;

/** Optional producers yield before near-limit allocations; accepted captures still drain and release their copies. */
final class LodMemoryBudget {
	private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("mcopt.lod.memoryBudget", "true"));
	private static final LodMemoryBudget PROCESS = new LodMemoryBudget();
	private boolean busy;

	static boolean pressured() {
		if (!ENABLED) return false;
		Runtime runtime = Runtime.getRuntime();
		return PROCESS.sample(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory());
	}

	/** Occupancy hysteresis, no cooldown, GC request, MXBean allocation or timer thread. */
	synchronized boolean sample(long used, long max) {
		if (max <= 0 || used < 0) return this.busy = false;
		long free = max - used;
		if (!this.busy) this.busy = used >= max * .88 || free < Math.min(128L << 20, max / 8);
		else if (used < max * .78 && free >= Math.min(384L << 20, max / 4)) this.busy = false;
		return this.busy;
	}
}
