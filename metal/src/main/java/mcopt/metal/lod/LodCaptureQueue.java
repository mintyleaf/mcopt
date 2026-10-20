package mcopt.metal.lod;

import jdk.jfr.*;

/** Low-rate queue and render-copy counters, active only during an explicitly enabled recording. */
final class LodCaptureQueue {
	private static final boolean ENABLED = Boolean.getBoolean("mcopt.metal.jfr");
	private static final EventType TYPE = EventType.getEventType(Sample.class);
	@Name("mcopt.LodCaptureQueue")
	@Label("LOD capture pipeline")
	@Category("mcopt")
	@StackTrace(false)
	static final class Sample extends Event {
		int rawQueued, writesQueued;
		long snapshots, snapshotNanos, fallbacks, reusedUnloads, reusedEdits;
	}
	static long sample(long now, long next, LodCaptures captures, long snapshots, long nanos, long fallbacks, long reusedUnloads, long reusedEdits) {
		if (!ENABLED || now < next || !TYPE.isEnabled()) return next;
		var event = new Sample();
		event.rawQueued = captures.rawPendingCount(); event.writesQueued = captures.writePendingCount();
		event.snapshots = snapshots; event.snapshotNanos = nanos; event.fallbacks = fallbacks;
		event.reusedUnloads = reusedUnloads;
		event.reusedEdits = reusedEdits;
		event.commit();
		return now + 1_000_000_000L;
	}

	@Name("mcopt.LodClientCapture")
	@Label("LOD client chunk capture")
	@Category("mcopt")
	@Threshold("1 ms")
	@StackTrace(false)
	static final class Capture extends Event {
		int chunkX, chunkZ;
		String reason;
		double freezeMs;
		boolean fallback;
	}
	static Capture beginCapture(int x, int z, String reason) {
		if (!ENABLED) return null;
		var event = new Capture();
		if (!event.isEnabled()) return null;
		event.chunkX = x; event.chunkZ = z; event.reason = reason; event.begin();
		return event;
	}
}
