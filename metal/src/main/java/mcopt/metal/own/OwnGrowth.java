package mcopt.metal.own;

/** Rare growth events only; timestamps share the bench's System.nanoTime clock. */
final class OwnGrowth {
	private static final boolean ON = Boolean.getBoolean("mcopt.own.growthStats");
	private static final boolean JFR = Boolean.getBoolean("mcopt.metal.jfr");

	@jdk.jfr.Name("mcopt.OwnArenaAllocation")
	@jdk.jfr.Label("Terrain render allocation reserve")
	@jdk.jfr.Category("mcopt")
	@jdk.jfr.StackTrace(false)
	static final class Allocation extends jdk.jfr.Event {
		long reserveBytes, reserveUsedBytes, reserveHits, sharedFallbacks, filterDeferrals;
	}
	private static final jdk.jfr.EventType ALLOCATION = jdk.jfr.EventType.getEventType(Allocation.class);
	static long allocation(long now, long next, OwnAlloc alloc, long filterDeferrals) {
		if (!JFR || now < next || !ALLOCATION.isEnabled()) return next;
		var event = new Allocation();
		event.reserveBytes = (long) alloc.reserveUnits() * OwnTerrain.ARENA_QUAD;
		event.reserveUsedBytes = alloc.reserveUsed() * OwnTerrain.ARENA_QUAD;
		event.reserveHits = alloc.reserveHits(); event.sharedFallbacks = alloc.reserveMisses(); event.filterDeferrals = filterDeferrals;
		event.commit();
		return now + 1_000_000_000L;
	}

	private OwnGrowth() {
	}

	@jdk.jfr.Name("mcopt.OwnArenaGrowth")
	@jdk.jfr.Label("Terrain arena growth")
	@jdk.jfr.Category("mcopt")
	@jdk.jfr.StackTrace(false)
	static final class Arena extends jdk.jfr.Event {
		String kind;
		long oldBytes, newBytes;
		double preparationMs, copyMs, writerHoldMs, writerWaitMs;
	}

	static void arena(String kind, long oldBytes, long newBytes, long preparation, long copy, long hold, long wait) {
		if (!Boolean.getBoolean("mcopt.metal.jfr")) return;
		Arena event = new Arena();
		if (!event.isEnabled()) return;
		event.kind = kind; event.oldBytes = oldBytes; event.newBytes = newBytes;
		event.preparationMs = preparation / 1e6; event.copyMs = copy / 1e6;
		event.writerHoldMs = hold / 1e6; event.writerWaitMs = wait / 1e6;
		event.commit();
	}

	static long begin() {
		return ON ? System.nanoTime() : 0;
	}

	static void end(String kind, long start, long oldBytes, long newBytes, long physicalBytes) {
		if (!ON) return;
		long end = System.nanoTime();
		// Format once before println: printf's fragmented writes can interleave with another thread's log event.
		System.out.println(String.format(java.util.Locale.ROOT,
			"mcopt-own growth: kind=%s startNs=%d endNs=%d epochMs=%d oldBytes=%d newBytes=%d physicalBytes=%d durationUs=%.3f thread=%s",
			kind, start, end, System.currentTimeMillis(), oldBytes, newBytes, physicalBytes, (end - start) / 1e3, Thread.currentThread().getName()));
	}
}
