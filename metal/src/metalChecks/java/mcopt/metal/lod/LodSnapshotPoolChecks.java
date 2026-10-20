package mcopt.metal.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Readers racing map removal must never see arrays returned to the pool; idle storage stays bounded. */
public final class LodSnapshotPoolChecks {
	public static void main(String[] args) throws Exception {
		var pool = new LodSnapshotPool();
		var first = new LodClip.Snapshot(pool, 0, 0, 0, true, true, true);
		first.g[0] = 71;
		first.retain(); // delayed worker
		first.release(); // map removal
		var second = new LodClip.Snapshot(pool, 0, 0, 0, true, true, true);
		check(first.g != second.g, "A worker's planes were recycled while leased");
		second.release(); first.release();
		check(!first.tryRetain(), "Retired snapshot was resurrected");
		var reused = new LodClip.Snapshot(pool, 0, 1, 1, true, true, true);
		check(reused.g == first.g, "Completed snapshot did not return its arrays");
		reused.release();
		var held = new ArrayList<LodClip.Snapshot>();
		for (int i = 0; i < 100; i++) held.add(new LodClip.Snapshot(pool, 0, i, 0, true, true, true));
		for (var s : held) s.release();
		check(pool.idleBytes() > 0 && pool.idleBytes() <= 8L << 20, "Idle array budget exceeded 8 MiB");

		var map = new ConcurrentHashMap<Long, LodClip.Snapshot>();
		var stop = new AtomicBoolean();
		var failure = new AtomicReference<Throwable>();
		var ready = new CountDownLatch(1);
		Thread reader = new Thread(() -> {
			ready.countDown();
			try {
				while (!stop.get()) {
					var s = LodClip.retain(map, 7);
					if (s == null) continue;
					try {
						int v = s.g[0];
						Thread.yield();
						for (int word : s.g) check(word == v, "Concurrent snapshot planes were overwritten");
					} finally { s.release(); }
				}
			} catch (Throwable e) { failure.set(e); }
		}, "snapshot-delayed-reader");
		reader.start(); ready.await();
		try {
			for (int i = 1; i <= 10000; i++) {
				var s = new LodClip.Snapshot(pool, 0, i, 0, true, true, true);
				Arrays.fill(s.g, i);
				LodClip.release(map.put(7L, s));
			}
		} finally { stop.set(true); reader.join(); LodClip.release(map.remove(7L)); }
		if (failure.get() != null) throw new AssertionError("Concurrent reader failed", failure.get());
		check(pool.reused > 9900, "Repeated publications did not reuse tile storage");
		var late = new LodClip.Snapshot(pool, 1, 0, 0, false, false, false);
		pool.close(); late.release();
		check(pool.idleBytes() == 0, "World exit or late reader retained idle storage");
		System.out.println("PASS: delayed reader leases, 10,000 concurrent replacements, non-resurrection, 8 MiB cap and world exit");
	}

	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
