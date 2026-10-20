package mcopt.metal.lod;

/** Bounded adaptive admission. Temporary garbage reduces concurrency; it does not impose a five-second cooldown. */
final class LodRefineBudget {
	private final LodWorkload workload;
	private final int spacingMs;
	private long previousTick, recoverAt, increaseAt, fullAt, probeAt;
	private boolean heapStopped, fullAllowed, importsAllowed, spare;
	private int window = 1;
	private double gapMs, importTokens;
	private String reason = "starting";

	LodRefineBudget() { this(LodConfig.WORKLOAD, LodConfig.REFINE_INTERVAL_MS); }
	LodRefineBudget(LodWorkload workload, int spacingMs) {
		this.workload = workload; this.spacingMs = spacingMs; this.importTokens = workload.imports;
	}

	void idle() { previousTick = 0; }

	void sample(long now, double frameMs, double tickMs, long used, long max, long retained) {
		this.sample(now, frameMs, tickMs, used, max, retained, false);
	}

	void sample(long now, double frameMs, double tickMs, long used, long max, long retained, boolean nearbyBusy) {
		this.sample(now, frameMs, tickMs, used, max, retained, nearbyBusy, false);
	}

	void sample(long now, double frameMs, double tickMs, long used, long max, long retained, boolean nearbyBusy, boolean allocationPressure) {
		long elapsed = previousTick == 0 ? 0 : Math.max(0, now - previousTick);
		gapMs = elapsed / 1e6;
		previousTick = now;
		long free = max - used;
		// Raw occupancy near the heap limit needs an immediate stop; lower transient occupancy only limits concurrency.
		if (used >= max * .94 || free < (128L << 20)) heapStopped = true;
		else if (used < max * .88 && free >= (256L << 20)) heapStopped = false;
		boolean livePressure = retained >= 0 && retained >= max * .85;
		boolean cpuPressure = frameMs >= 30 || tickMs >= 35 || nearbyBusy;
		spare = used < max * .88 && free >= (384L << 20);
		if (gapMs > 250) recoverAt = now + (gapMs > 1000 ? 500_000_000L : 150_000_000L);
		if (heapStopped || allocationPressure || livePressure || cpuPressure || !spare || now < recoverAt) {
			window = Math.max(1, window / 2); increaseAt = now + 1_000_000_000L;
		} else if (increaseAt == 0) increaseAt = now + 1_000_000_000L;
		else if (now >= increaseAt) { window = Math.min(Math.max(workload.full, workload.probes), window + 1); increaseAt = now + 1_000_000_000L; }
		fullAllowed = !heapStopped && !allocationPressure && !livePressure && !cpuPressure && now >= recoverAt;
		// Decoding existing terrain can continue under mild pressure without adding vanilla world-generation dependencies.
		importsAllowed = !heapStopped && !allocationPressure && !livePressure && frameMs < 45 && tickMs < 45 && now >= recoverAt;
		int quota = spare && !cpuPressure ? workload.imports : 1;
		importTokens = Math.min(quota, importTokens + Math.min(elapsed, 100_000_000L) / 50_000_000.0 * quota);
		reason = heapStopped ? "heap near limit" : allocationPressure ? "allocation headroom" : livePressure ? "retained heap pressure" : nearbyBusy ? "nearby chunks loading" : cpuPressure ? "frame/server pressure"
			: now < recoverAt ? "brief stall recovery" : !spare ? "limited by transient heap use" : "ready";
	}

	boolean ready(long now) { return fullAllowed && now >= recoverAt; }
	boolean importsAllowed(long now) { return importsAllowed && now >= recoverAt; }
	int fullLimit(long now) { return ready(now) ? Math.min(workload.full, spare ? window : 1) : 0; }
	int probeLimit(long now) { return ready(now) ? Math.min(workload.probes, spare ? window : 1) : 0; }
	boolean generationReady(long now, int loaded, int rd) { return ready(now) && now >= fullAt && loaded <= chunkLimit(rd); }
	boolean probeReady(long now, int loaded, int rd) { return ready(now) && now >= probeAt && loaded <= chunkLimit(rd); }
	static int chunkLimit(int rd) { return Math.max(768, (2 * (rd + 8) + 1) * (2 * (rd + 8) + 1) + 512); }
	boolean importReady(long now) { return importsAllowed(now) && importTokens >= 1; }
	void imported() { importTokens = Math.max(0, importTokens - 1); }
	void started(long now, boolean probe) {
		// Starts are wall-clock limited too: catch-up ticks cannot fill the window in a single burst.
		if (probe) probeAt = now + 50_000_000L;
		else fullAt = now + Math.max(50, spacingMs) * 1_000_000L;
	}
	double gapMs() { return gapMs; }
	String reason() { return reason; }
}
